package io.github.anikethts.changeradar.analyzer.sql;

import io.github.anikethts.changeradar.core.Limitation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SqlParsing {

  private static final Set<String> CREATE_MODIFIERS =
      Set.of("or", "replace", "temp", "temporary", "global", "local", "unlogged", "materialized");
  private static final Set<String> OBJECT_KINDS = Set.of("table", "view", "function", "procedure");

  private SqlParsing() {}

  static ParsedFile parseBase(
      String path, Map<String, String> baseFiles, List<Limitation> limitations) {
    String source = baseFiles.get(path);
    if (source == null) {
      return null;
    }
    return parse(path, source, limitations);
  }

  static ParsedFile parse(String path, String source, List<Limitation> limitations) {
    Mask mask = mask(source);
    if (mask.failed()) {
      limitations.add(
          new Limitation(
              "SQL_PARSE_FAILED",
              "Could not parse "
                  + path
                  + ": unclosed comment or string. Dollar-quoted bodies are not parsed."));
      return null;
    }
    return new ParsedFile(
        path, mask.text(), extractDecls(path, mask.text()), SqlSupport.isTestPath(path));
  }

  static Mask mask(String source) {
    StringBuilder out = new StringBuilder(source.length());
    boolean failed = false;
    int i = 0;
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      char next = i + 1 < n ? source.charAt(i + 1) : '\0';
      if (c == '-' && next == '-') {
        i = blankUntilNewline(source, out, i);
        continue;
      }
      if (c == '#') {
        i = blankUntilNewline(source, out, i);
        continue;
      }
      if (c == '/' && next == '*') {
        int close = source.indexOf("*/", i + 2);
        if (close < 0) {
          blankRange(source, out, i, n);
          failed = true;
          break;
        }
        blankRange(source, out, i, close + 2);
        i = close + 2;
        continue;
      }
      if (c == '"' || c == '`' || c == '[' || c == ']') {
        out.append(' ');
        i++;
        continue;
      }
      if (c == '\'') {
        int end = skipSqlString(source, i);
        if (end < 0) {
          blankRange(source, out, i, n);
          failed = true;
          break;
        }
        blankRange(source, out, i, end);
        i = end;
        continue;
      }
      out.append(c);
      i++;
    }
    return new Mask(out.toString(), failed);
  }

  static List<Decl> extractDecls(String path, String masked) {
    List<Decl> decls = new ArrayList<>();
    int i = 0;
    int n = masked.length();
    boolean test = SqlSupport.isTestPath(path);
    while (i < n) {
      if (!isIdentStart(masked.charAt(i))) {
        i++;
        continue;
      }
      int wordStart = i;
      String word = readIdent(masked, i);
      i += word.length();
      if (word.equalsIgnoreCase("create")) {
        Decl decl = parseCreate(path, masked, wordStart, i, test);
        if (decl != null) {
          decls.add(decl);
          i = statementEnd(masked, wordStart) + 1;
        }
      } else if (word.equalsIgnoreCase("alter")) {
        Decl decl = parseAlter(path, masked, wordStart, i, test);
        if (decl != null) {
          decls.add(decl);
          i = statementEnd(masked, wordStart) + 1;
        }
      }
    }
    return decls;
  }

  private static Decl parseCreate(String path, String masked, int keyword, int i, boolean test) {
    while (i < masked.length()) {
      i = skipSpace(masked, i);
      if (i >= masked.length() || !isIdentStart(masked.charAt(i))) {
        return null;
      }
      String word = readIdent(masked, i);
      i += word.length();
      String lower = word.toLowerCase(Locale.ROOT);
      if (CREATE_MODIFIERS.contains(lower)) {
        continue;
      }
      if (!OBJECT_KINDS.contains(lower)) {
        return null;
      }
      i = skipIfNotExists(masked, i);
      Qualified name = readQualified(masked, i);
      if (name == null) {
        return null;
      }
      int end = statementEnd(masked, keyword);
      return new Decl(
          lower,
          name.name(),
          name.owner(),
          test,
          path,
          SqlSupport.lineOf(masked, keyword),
          SqlSupport.lineOf(masked, end));
    }
    return null;
  }

  private static Decl parseAlter(String path, String masked, int keyword, int i, boolean test) {
    i = skipSpace(masked, i);
    if (i >= masked.length() || !isIdentStart(masked.charAt(i))) {
      return null;
    }
    String kind = readIdent(masked, i);
    if (!kind.equalsIgnoreCase("table")) {
      return null;
    }
    i += kind.length();
    i = skipWords(masked, i, Set.of("if", "exists", "only"));
    Qualified name = readQualified(masked, i);
    if (name == null) {
      return null;
    }
    int end = statementEnd(masked, keyword);
    return new Decl(
        "table",
        name.name(),
        name.owner(),
        test,
        path,
        SqlSupport.lineOf(masked, keyword),
        SqlSupport.lineOf(masked, end));
  }

  private static int skipIfNotExists(String masked, int i) {
    i = skipSpace(masked, i);
    if (!startsWithWord(masked, i, "if")) {
      return i;
    }
    int afterIf = i + 2;
    int notAt = skipSpace(masked, afterIf);
    if (!startsWithWord(masked, notAt, "not")) {
      return i;
    }
    int existsAt = skipSpace(masked, notAt + 3);
    if (!startsWithWord(masked, existsAt, "exists")) {
      return i;
    }
    return existsAt + "exists".length();
  }

  private static int skipWords(String masked, int i, Set<String> words) {
    while (i < masked.length()) {
      int next = skipSpace(masked, i);
      if (next >= masked.length() || !isIdentStart(masked.charAt(next))) {
        return next;
      }
      String word = readIdent(masked, next);
      if (!words.contains(word.toLowerCase(Locale.ROOT))) {
        return next;
      }
      i = next + word.length();
    }
    return i;
  }

  private static Qualified readQualified(String masked, int i) {
    Atom first = readAtom(masked, i);
    if (first == null) {
      return null;
    }
    String owner = "";
    String name = first.text();
    int cursor = first.next();
    while (true) {
      int dot = skipSpace(masked, cursor);
      if (dot >= masked.length() || masked.charAt(dot) != '.') {
        break;
      }
      Atom next = readAtom(masked, dot + 1);
      if (next == null) {
        break;
      }
      owner = name;
      name = next.text();
      cursor = next.next();
    }
    if (name.isEmpty()) {
      return null;
    }
    return new Qualified(owner, name);
  }

  private static Atom readAtom(String masked, int i) {
    i = skipSpace(masked, i);
    if (i >= masked.length()) {
      return null;
    }
    char c = masked.charAt(i);
    if (c == '"' || c == '`' || c == '[') {
      char end = c == '[' ? ']' : c;
      int close = masked.indexOf(end, i + 1);
      if (close < 0) {
        return null;
      }
      return new Atom(masked.substring(i + 1, close), close + 1);
    }
    if (!isIdentStart(c)) {
      return null;
    }
    String ident = readIdent(masked, i);
    return new Atom(ident, i + ident.length());
  }

  static int statementEnd(String masked, int from) {
    int depth = 0;
    for (int i = from; i < masked.length(); i++) {
      char c = masked.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')' && depth > 0) {
        depth--;
      } else if (c == ';' && depth == 0) {
        return i;
      }
    }
    return Math.max(from, masked.length() - 1);
  }

  private static int blankUntilNewline(String source, StringBuilder out, int i) {
    while (i < source.length() && source.charAt(i) != '\n') {
      out.append(' ');
      i++;
    }
    return i;
  }

  private static void blankRange(String source, StringBuilder out, int from, int to) {
    for (int i = from; i < to; i++) {
      out.append(source.charAt(i) == '\n' ? '\n' : ' ');
    }
  }

  private static int skipSqlString(String source, int i) {
    i++;
    while (i < source.length()) {
      if (source.charAt(i) == '\'') {
        if (i + 1 < source.length() && source.charAt(i + 1) == '\'') {
          i += 2;
          continue;
        }
        return i + 1;
      }
      i++;
    }
    return -1;
  }

  private static int skipSpace(String masked, int i) {
    while (i < masked.length() && Character.isWhitespace(masked.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean startsWithWord(String masked, int i, String word) {
    if (i + word.length() > masked.length()) {
      return false;
    }
    if (!masked.regionMatches(true, i, word, 0, word.length())) {
      return false;
    }
    int end = i + word.length();
    return end == masked.length() || !isIdentPart(masked.charAt(end));
  }

  private static String readIdent(String masked, int i) {
    int start = i;
    i++;
    while (i < masked.length() && isIdentPart(masked.charAt(i))) {
      i++;
    }
    return masked.substring(start, i);
  }

  private static boolean isIdentStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  private static boolean isIdentPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  record Mask(String text, boolean failed) {}

  private record Qualified(String owner, String name) {}

  private record Atom(String text, int next) {}
}
