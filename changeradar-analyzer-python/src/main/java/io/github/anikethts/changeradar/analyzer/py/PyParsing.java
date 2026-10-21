package io.github.anikethts.changeradar.analyzer.py;

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

final class PyParsing {

  static final Pattern CLASS = Pattern.compile("^class\\s+([A-Za-z_][\\w]*)\\s*(?:\\(([^)]*)\\))?");
  static final Pattern FIELD = Pattern.compile("^([A-Za-z_][\\w]*)\\s*(:[^=]+)?\\s*=");
  static final Pattern TYPED_ATTR = Pattern.compile("^([A-Za-z_][\\w]*)\\s*:\\s*([A-Za-z_][\\w]*)");
  static final Pattern DECORATOR = Pattern.compile("^@([A-Za-z_][\\w]*)");
  static final Pattern FIELD_ACCESS =
      Pattern.compile("([A-Za-z_][\\w]*)\\s*\\.\\s*([A-Za-z_][\\w]*)\\s*(?!\\()");
  static final Set<String> INJECT_DECORATORS = Set.of("inject", "Inject", "autowired");
  static final Pattern DEF = Pattern.compile("^(?:async\\s+)?def\\s+([A-Za-z_][\\w]*)");
  static final Pattern CALL =
      Pattern.compile("(?:([A-Za-z_][\\w]*)\\s*\\.)?([A-Za-z_][\\w]*)\\s*\\(");
  static final Pattern FROM_IMPORT =
      Pattern.compile("(?m)^\\s*from\\s+([\\w.]+)\\s+import\\s+(.+)$");
  static final Pattern IMPORT_MODULE =
      Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?");

  private PyParsing() {}

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
    if (!mask(source, codeLines)) {
      limitations.add(
          new Limitation(
              "PY_PARSE_FAILED", "Could not parse " + path + ": unclosed string literal."));
      return;
    }
    Map<String, List<String>> supers = new LinkedHashMap<>();
    List<Decl> decls = extractDecls(path, codeLines, supers);
    into.put(
        path,
        new ParsedFile(
            path, codeLines, decls, PySupport.isTestPath(path), extractImports(source), supers));
  }

  static boolean mask(String source, List<String> codeLines) {
    StringBuilder current = new StringBuilder();
    boolean inHash = false;
    boolean inSq = false;
    boolean inDq = false;
    int triple = 0;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\n') {
        codeLines.add(current.toString());
        current.setLength(0);
        inHash = false;
        continue;
      }
      if (inHash) {
        continue;
      }
      if (triple == 3) {
        if (i + 2 < source.length()
            && c == '\''
            && source.charAt(i + 1) == '\''
            && source.charAt(i + 2) == '\'') {
          triple = 0;
          i += 2;
        }
        current.append(' ');
        continue;
      }
      if (triple == 6) {
        if (i + 2 < source.length()
            && c == '"'
            && source.charAt(i + 1) == '"'
            && source.charAt(i + 2) == '"') {
          triple = 0;
          i += 2;
        }
        current.append(' ');
        continue;
      }
      if (inSq) {
        if (c == '\\') {
          i++;
        } else if (c == '\'') {
          inSq = false;
        }
        current.append(' ');
        continue;
      }
      if (inDq) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          inDq = false;
        }
        current.append(' ');
        continue;
      }
      if (c == '#') {
        inHash = true;
        continue;
      }
      if (i + 2 < source.length()
          && c == '\''
          && source.charAt(i + 1) == '\''
          && source.charAt(i + 2) == '\'') {
        triple = 3;
        i += 2;
        current.append(' ');
        continue;
      }
      if (i + 2 < source.length()
          && c == '"'
          && source.charAt(i + 1) == '"'
          && source.charAt(i + 2) == '"') {
        triple = 6;
        i += 2;
        current.append(' ');
        continue;
      }
      if (c == '\'') {
        inSq = true;
        current.append(' ');
        continue;
      }
      if (c == '"') {
        inDq = true;
        current.append(' ');
        continue;
      }
      current.append(c);
    }
    codeLines.add(current.toString());
    return !inSq && !inDq && triple == 0;
  }

  static List<Decl> extractDecls(
      String path, List<String> lines, Map<String, List<String>> supers) {
    List<Decl> decls = new ArrayList<>();
    String currentClass = null;
    int classIndent = -1;
    boolean testFile = PySupport.isTestPath(path);
    for (int i = 0; i < lines.size(); i++) {
      String raw = lines.get(i);
      if (raw.trim().isEmpty()) {
        continue;
      }
      int indent = leadingIndent(raw);
      String trimmed = raw.trim();
      int lineNo = i + 1;
      if (currentClass != null && indent <= classIndent) {
        currentClass = null;
        classIndent = -1;
      }
      if (currentClass == null) {
        Matcher cls = CLASS.matcher(trimmed);
        if (cls.find()) {
          String name = cls.group(1);
          decls.add(
              new Decl(
                  "type",
                  name,
                  name,
                  PySupport.isPublicName(name),
                  testFile,
                  path,
                  lineNo,
                  lineNo,
                  indent));
          supers.put(name, superNames(name, cls.group(2)));
          currentClass = name;
          classIndent = indent;
          continue;
        }
        Matcher def = DEF.matcher(trimmed);
        if (def.find()) {
          String name = def.group(1);
          decls.add(
              new Decl(
                  "function",
                  name,
                  "",
                  PySupport.isPublicName(name),
                  testFile,
                  path,
                  lineNo,
                  lineNo,
                  indent));
        }
      } else {
        Matcher def = DEF.matcher(trimmed);
        if (def.find()) {
          String name = def.group(1);
          String kind = "__init__".equals(name) ? "constructor" : "method";
          decls.add(
              new Decl(
                  kind,
                  name,
                  currentClass,
                  PySupport.isPublicName(name),
                  testFile,
                  path,
                  lineNo,
                  lineNo,
                  indent));
        } else {
          Matcher field = FIELD.matcher(trimmed);
          if (field.find() && !"def".equals(field.group(1)) && !"class".equals(field.group(1))) {
            decls.add(
                new Decl(
                    "field",
                    field.group(1),
                    currentClass,
                    PySupport.isPublicName(field.group(1)),
                    testFile,
                    path,
                    lineNo,
                    lineNo,
                    indent));
          }
        }
      }
    }
    return closeBodies(decls, lines.size());
  }

  static List<String> superNames(String typeName, String bases) {
    List<String> names = new ArrayList<>();
    for (String id : StructuralScan.identifiers(bases)) {
      if (!id.equals(typeName) && !"object".equals(id) && !"metaclass".equals(id)) {
        names.add(id);
      }
    }
    return names;
  }

  static List<Decl> closeBodies(List<Decl> decls, int lineCount) {
    List<Decl> closed = new ArrayList<>();
    for (int i = 0; i < decls.size(); i++) {
      Decl decl = decls.get(i);
      int end = lineCount;
      for (int j = i + 1; j < decls.size(); j++) {
        if (decls.get(j).indent() <= decl.indent()) {
          end = decls.get(j).startLine() - 1;
          break;
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
              Math.max(decl.startLine(), end),
              decl.indent()));
    }
    return closed;
  }

  static int leadingIndent(String line) {
    int n = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == ' ') {
        n++;
      } else if (c == '\t') {
        n += 8;
      } else {
        break;
      }
    }
    return n;
  }

  static Imports extractImports(String source) {
    Map<String, String> bindings = new LinkedHashMap<>();
    Set<String> modules = new LinkedHashSet<>();
    Matcher from = FROM_IMPORT.matcher(source);
    while (from.find()) {
      String spec = from.group(1);
      modules.add(spec);
      for (String part : from.group(2).split(",")) {
        String bit = part.trim();
        if (bit.isEmpty() || "*".equals(bit)) {
          continue;
        }
        String[] alias = bit.split("\\s+as\\s+");
        String local = alias[alias.length - 1].trim();
        if (!local.isEmpty()) {
          bindings.put(local, spec);
        }
      }
    }
    Matcher imp = IMPORT_MODULE.matcher(source);
    while (imp.find()) {
      String spec = imp.group(1);
      modules.add(spec);
      String local =
          imp.group(2) != null ? imp.group(2) : spec.substring(spec.lastIndexOf('.') + 1);
      bindings.put(local, spec);
    }
    return new Imports(bindings, modules);
  }
}
