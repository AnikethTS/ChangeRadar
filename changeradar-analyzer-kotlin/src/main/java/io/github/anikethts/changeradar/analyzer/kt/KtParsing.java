package io.github.anikethts.changeradar.analyzer.kt;

import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.StructuralScan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class KtParsing {

  static final Pattern TYPE =
      Pattern.compile(
          "^(?:(?:public|private|protected|internal|open|abstract|data|sealed|inner|value|"
              + "annotation|enum|fun)\\s+)*(?:class|object|interface)\\s+([A-Za-z_][\\w]*)\\b(.*)");
  static final Pattern FUN =
      Pattern.compile(
          "^(?:(?:public|private|protected|internal|open|abstract|override|suspend|inline|operator)\\s+)*"
              + "fun\\s+(?:<[^>]+>\\s+)?(?:[A-Za-z_][\\w]*\\.)?([A-Za-z_][\\w]*)\\s*(?:<[^>]*>)?\\s*\\(");
  static final Pattern FIELD =
      Pattern.compile(
          "^(?:(?:public|private|protected|internal|open|override|const|lateinit)\\s+)*"
              + "(?:val|var)\\s+([A-Za-z_][\\w]*)");
  static final Pattern IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?");
  static final Set<String> SUPER_KEYWORDS = Set.of(":", "by");
  static final Set<String> NOT_FUN =
      Set.of("if", "for", "while", "when", "catch", "constructor", "init", "class", "object");

  private KtParsing() {}

  static ParsedFile parseBase(
      String path, Map<String, String> baseFiles, List<Limitation> limitations) {
    String source = baseFiles.get(path);
    if (source == null) {
      return null;
    }
    Map<String, ParsedFile> parsed = new LinkedHashMap<>();
    parse(path, source, parsed, limitations);
    return parsed.get(path);
  }

  static void parse(
      String path, String source, Map<String, ParsedFile> into, List<Limitation> limitations) {
    List<String> codeLines = new ArrayList<>();
    int depth = stripAndMeasure(source, codeLines);
    if (depth != 0) {
      limitations.add(
          new Limitation(
              "KT_PARSE_FAILED",
              "Could not parse " + path + ": unmatched braces (depth " + depth + ")."));
      return;
    }
    Map<String, List<String>> supers = new LinkedHashMap<>();
    List<Decl> decls = extractDecls(path, codeLines, supers);
    into.put(
        path,
        new ParsedFile(
            path, codeLines, decls, KtSupport.isTestPath(path), extractImports(source), supers));
  }

  static int stripAndMeasure(String source, List<String> codeLines) {
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inLineComment = false;
    boolean inBlockComment = false;
    boolean inSingle = false;
    boolean inDouble = false;
    boolean inRaw = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
      char third = i + 2 < source.length() ? source.charAt(i + 2) : '\0';
      if (c == '\n') {
        codeLines.add(current.toString());
        current.setLength(0);
        inLineComment = false;
        continue;
      }
      if (inLineComment) {
        continue;
      }
      if (inBlockComment) {
        if (c == '*' && next == '/') {
          inBlockComment = false;
          i++;
        }
        continue;
      }
      if (inRaw) {
        if (c == '"' && next == '"' && third == '"') {
          inRaw = false;
          i += 2;
        }
        current.append(' ');
        continue;
      }
      if (inSingle) {
        if (c == '\\') {
          i++;
        } else if (c == '\'') {
          inSingle = false;
        }
        current.append(' ');
        continue;
      }
      if (inDouble) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          inDouble = false;
        }
        current.append(' ');
        continue;
      }
      if (c == '/' && next == '/') {
        inLineComment = true;
        i++;
        continue;
      }
      if (c == '/' && next == '*') {
        inBlockComment = true;
        i++;
        continue;
      }
      if (c == '"' && next == '"' && third == '"') {
        inRaw = true;
        i += 2;
        current.append(' ');
        continue;
      }
      if (c == '\'') {
        inSingle = true;
        current.append(' ');
        continue;
      }
      if (c == '"') {
        inDouble = true;
        current.append(' ');
        continue;
      }
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
      }
      current.append(c);
    }
    codeLines.add(current.toString());
    return depth;
  }

  static List<Decl> extractDecls(
      String path, List<String> lines, Map<String, List<String>> supers) {
    List<Decl> decls = new ArrayList<>();
    int brace = 0;
    String currentClass = null;
    int classLevel = -1;
    boolean classPublic = true;
    boolean testFile = KtSupport.isTestPath(path);
    for (int i = 0; i < lines.size(); i++) {
      int lineNo = i + 1;
      String trimmed = lines.get(i).trim();
      if (currentClass == null) {
        Matcher type = TYPE.matcher(trimmed);
        if (type.find()) {
          String name = type.group(1);
          boolean pub = KtSupport.isPublic(trimmed);
          decls.add(new Decl("type", name, name, pub, testFile, path, lineNo, lineNo));
          supers.put(name, superNames(name, type.group(2)));
          currentClass = name;
          classPublic = pub;
          classLevel = brace + 1;
        } else {
          Matcher fn = FUN.matcher(trimmed);
          if (fn.find() && !NOT_FUN.contains(fn.group(1))) {
            decls.add(
                new Decl(
                    "function",
                    fn.group(1),
                    "",
                    KtSupport.isPublic(trimmed),
                    testFile,
                    path,
                    lineNo,
                    lineNo));
          }
        }
      } else if (brace == classLevel) {
        Matcher fn = FUN.matcher(trimmed);
        if (fn.find() && !NOT_FUN.contains(fn.group(1))) {
          decls.add(
              new Decl(
                  "method",
                  fn.group(1),
                  currentClass,
                  classPublic && KtSupport.isPublic(trimmed),
                  testFile,
                  path,
                  lineNo,
                  lineNo));
        } else {
          Matcher field = FIELD.matcher(trimmed);
          if (field.find()) {
            decls.add(
                new Decl(
                    "field",
                    field.group(1),
                    currentClass,
                    classPublic && KtSupport.isPublic(trimmed),
                    testFile,
                    path,
                    lineNo,
                    lineNo));
          }
        }
      }
      brace += netBraces(lines.get(i));
      if (currentClass != null && brace < classLevel) {
        currentClass = null;
        classLevel = -1;
        classPublic = true;
      }
    }
    return closeBodies(decls, lines);
  }

  static List<Decl> closeBodies(List<Decl> decls, List<String> lines) {
    int[] after = new int[lines.size()];
    int depth = 0;
    for (int i = 0; i < lines.size(); i++) {
      depth += netBraces(lines.get(i));
      after[i] = depth;
    }
    List<Decl> closed = new ArrayList<>();
    for (Decl decl : decls) {
      int startIdx = Math.max(0, decl.startLine() - 1);
      int depthBefore = startIdx == 0 ? 0 : after[startIdx - 1];
      int end = lines.size();
      if (after[startIdx] <= depthBefore) {
        end = decl.startLine();
      } else {
        for (int j = startIdx + 1; j < lines.size(); j++) {
          if (after[j] <= depthBefore) {
            end = j + 1;
            break;
          }
        }
      }
      closed.add(
          new Decl(
              decl.kind(),
              decl.name(),
              decl.owner(),
              decl.publiclyVisible(),
              decl.test(),
              decl.path(),
              decl.startLine(),
              Math.max(decl.startLine(), end)));
    }
    return closed;
  }

  static List<String> superNames(String typeName, String tail) {
    List<String> names = new ArrayList<>();
    for (String id : StructuralScan.identifiers(tail)) {
      if (!SUPER_KEYWORDS.contains(id) && !id.equals(typeName)) {
        names.add(id);
      }
    }
    return names;
  }

  static int netBraces(String line) {
    int n = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '{') {
        n++;
      } else if (c == '}') {
        n--;
      }
    }
    return n;
  }

  static Imports extractImports(String source) {
    Map<String, String> bindings = new LinkedHashMap<>();
    Set<String> modules = new LinkedHashSet<>();
    Matcher imp = IMPORT.matcher(source);
    while (imp.find()) {
      String spec = imp.group(1);
      if (spec.endsWith(".*")) {
        modules.add(spec.substring(0, spec.length() - 2));
        continue;
      }
      modules.add(spec);
      String local =
          imp.group(2) != null ? imp.group(2) : spec.substring(spec.lastIndexOf('.') + 1);
      bindings.put(local, spec);
    }
    return new Imports(bindings, modules);
  }
}
