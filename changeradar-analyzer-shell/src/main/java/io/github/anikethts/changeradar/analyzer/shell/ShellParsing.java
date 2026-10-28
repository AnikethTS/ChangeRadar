package io.github.anikethts.changeradar.analyzer.shell;

import io.github.anikethts.changeradar.core.Limitation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShellParsing {

  private static final Pattern FUNCTION_KEYWORD =
      Pattern.compile("^function\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
  private static final Pattern FUNCTION_PARENS =
      Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
  private static final Pattern SOURCE = Pattern.compile("(?:^|[;&|])\\s*(?:source|\\.)\\s+(\\S+)");
  private static final Set<String> RESERVED =
      Set.of("if", "for", "while", "until", "case", "select", "time", "coproc");

  private ShellParsing() {}

  static ParsedShell parse(String path, String source, List<Limitation> limitations) {
    try {
      return parse(path, source);
    } catch (IllegalArgumentException ex) {
      limitations.add(
          new Limitation(
              "SHELL_PARSE_FAILED",
              "Could not parse "
                  + path
                  + ": unclosed quote. eval and generated functions are not parsed."));
      return null;
    }
  }

  static ParsedShell parse(String path, String source) {
    String unix = path.replace('\\', '/');
    String masked = mask(source);
    String[] lines = source.split("\n", -1);
    List<Func> functions = new ArrayList<>();
    List<SourceRef> sources = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      String rest = stripComment(lines[i]).trim();
      if (rest.isEmpty()) {
        continue;
      }
      String name = functionName(rest);
      if (name != null) {
        functions.add(new Func(name, i + 1, i + 1));
      }
      Matcher sourceMatch = SOURCE.matcher(rest);
      while (sourceMatch.find()) {
        String resolved = resolve(unix, sourceMatch.group(1));
        if (resolved != null) {
          sources.add(new SourceRef(resolved, i + 1));
        }
      }
    }
    List<Func> spanned = new ArrayList<>();
    for (int i = 0; i < functions.size(); i++) {
      Func func = functions.get(i);
      int end =
          i + 1 < functions.size()
              ? functions.get(i + 1).startLine() - 1
              : ShellSupport.lineCount(source);
      spanned.add(new Func(func.name(), func.startLine(), Math.max(func.startLine(), end)));
    }
    return new ParsedShell(unix, masked, spanned, sources);
  }

  static String resolve(String fromFile, String token) {
    String value = unquote(token.trim());
    if (value.isEmpty()
        || value.startsWith("/")
        || value.contains("$")
        || value.contains("*")
        || value.contains("`")) {
      return null;
    }
    String parent = parent(fromFile);
    String joined =
        value.startsWith("./") || value.startsWith("../") || parent.isEmpty()
            ? (parent.isEmpty() ? value : parent + "/" + value)
            : parent + "/" + value;
    return normalize(joined);
  }

  private static String functionName(String rest) {
    Matcher keyword = FUNCTION_KEYWORD.matcher(rest);
    if (keyword.find()) {
      return keyword.group(1);
    }
    Matcher parens = FUNCTION_PARENS.matcher(rest);
    if (parens.find() && !RESERVED.contains(parens.group(1))) {
      return parens.group(1);
    }
    return null;
  }

  private static String mask(String source) {
    StringBuilder out = new StringBuilder(source.length());
    int i = 0;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '#') {
        while (i < source.length() && source.charAt(i) != '\n') {
          out.append(' ');
          i++;
        }
        continue;
      }
      if (c == '\'' || c == '"' || c == '`') {
        char quote = c;
        out.append(' ');
        i++;
        while (i < source.length()) {
          char inner = source.charAt(i);
          if (quote == '"' && inner == '\\' && i + 1 < source.length()) {
            out.append(' ');
            out.append(' ');
            i += 2;
            continue;
          }
          out.append(' ');
          i++;
          if (inner == quote) {
            break;
          }
        }
        if (i >= source.length() || source.charAt(i - 1) != quote) {
          throw new IllegalArgumentException("unclosed quote");
        }
        continue;
      }
      out.append(c);
      i++;
    }
    if (out.length() != source.length()) {
      throw new IllegalArgumentException("mask length");
    }
    return out.toString();
  }

  private static String stripComment(String line) {
    boolean single = false;
    boolean doub = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\'' && !doub) {
        single = !single;
      } else if (c == '"' && !single) {
        doub = !doub;
      } else if (c == '\\' && doub && i + 1 < line.length()) {
        i++;
      } else if (c == '#' && !single && !doub) {
        line = line.substring(0, i);
        break;
      }
    }
    if (single || doub) {
      throw new IllegalArgumentException("unclosed quote");
    }
    return line;
  }

  private static String unquote(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' || first == '\'') && last == first) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private static String normalize(String path) {
    String[] parts = path.split("/");
    List<String> stack = new ArrayList<>();
    for (String part : parts) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        if (stack.isEmpty()) {
          return null;
        }
        stack.remove(stack.size() - 1);
        continue;
      }
      stack.add(part);
    }
    return String.join("/", stack);
  }
}
