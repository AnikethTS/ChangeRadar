package io.github.anikethts.changeradar.analyzer.ts;

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

final class TsParsing {

  static final Pattern CLASS =
      Pattern.compile(
          "^(?:export\\s+(?:default\\s+)?)?(?:abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)\\b(.*)");
  static final Pattern FUNCTION =
      Pattern.compile(
          "^(?:export\\s+(?:default\\s+)?)?(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)");
  static final Pattern CONST_FN =
      Pattern.compile(
          "^(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\(|function\\b)");
  static final Pattern TYPE_ALIAS = Pattern.compile("^(?:export\\s+)?type\\s+([A-Za-z_$][\\w$]*)");
  static final Pattern INTERFACE =
      Pattern.compile("^(?:export\\s+)?interface\\s+([A-Za-z_$][\\w$]*)\\b(.*)");
  static final Pattern FIELD =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|readonly|declare|override|abstract)\\s+)*"
              + "([A-Za-z_$][\\w$]*)\\s*(?:\\?\\s*)?(?::[^=;(]+)?\\s*(?:=|;)");
  static final Pattern DECORATOR = Pattern.compile("^@([A-Za-z_$][\\w$]*)");
  static final Pattern FIELD_ACCESS =
      Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*(?!\\()");
  static final Pattern TYPED_FIELD =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|readonly|declare)\\s+)*"
              + "[A-Za-z_$][\\w$]*\\s*\\??\\s*:\\s*([A-Za-z_$][\\w$]*)");
  static final Set<String> SUPER_KEYWORDS = Set.of("extends", "implements");
  static final Set<String> INJECT_DECORATORS =
      Set.of("Inject", "Injectable", "Autowired", "Resource");
  static final Pattern METHOD =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|async|readonly|override|abstract|get|set)\\s+)*"
              + "(?:async\\s+)?([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(");
  static final Pattern CALL =
      Pattern.compile("(?:([A-Za-z_$][\\w$]*)\\s*\\.)?([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(");
  static final Pattern NEW_TYPE =
      Pattern.compile("\\bnew\\s+([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(");
  static final Pattern IMPORT_NAMED =
      Pattern.compile(
          "(?:import|export)\\s+(?:type\\s+)?\\{([^}]+)\\}\\s+from\\s+['\"]([^'\"]+)['\"]");
  static final Pattern IMPORT_DEFAULT =
      Pattern.compile("import\\s+(?:type\\s+)?([A-Za-z_$][\\w$]*)\\s+from\\s+['\"]([^'\"]+)['\"]");
  static final Pattern IMPORT_STAR =
      Pattern.compile("import\\s+\\*\\s+as\\s+([A-Za-z_$][\\w$]*)\\s+from\\s+['\"]([^'\"]+)['\"]");
  static final Pattern IMPORT_REQUIRE =
      Pattern.compile(
          "(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*require\\s*\\(\\s*['\"]([^'\"]+)['\"]");
  static final Pattern EXPORT_STAR =
      Pattern.compile("export\\s+\\*\\s+from\\s+['\"]([^'\"]+)['\"]");

  private TsParsing() {}

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
              "TS_PARSE_FAILED",
              "Could not parse " + path + ": unmatched braces (depth " + depth + ")."));
      return;
    }
    Map<String, List<String>> supers = new LinkedHashMap<>();
    List<Decl> decls = extractDecls(path, codeLines, supers);
    into.put(
        path,
        new ParsedFile(
            path, codeLines, decls, TsSupport.isTestPath(path), extractImports(source), supers));
  }

  static int stripAndMeasure(String source, List<String> codeLines) {
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inLineComment = false;
    boolean inBlockComment = false;
    boolean inSingle = false;
    boolean inDouble = false;
    boolean inTemplate = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
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
      if (inTemplate) {
        if (c == '\\') {
          i++;
        } else if (c == '`') {
          inTemplate = false;
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
      if (c == '`') {
        inTemplate = true;
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
    boolean classExported = false;
    boolean testFile = TsSupport.isTestPath(path);
    for (int i = 0; i < lines.size(); i++) {
      int lineNo = i + 1;
      String trimmed = lines.get(i).trim();
      if (currentClass == null) {
        Matcher cls = CLASS.matcher(trimmed);
        if (cls.find()) {
          boolean exported = trimmed.startsWith("export");
          String name = cls.group(1);
          decls.add(
              new Decl(
                  "type",
                  name,
                  name,
                  exported,
                  testFile || TsSupport.isTestName(name),
                  path,
                  lineNo,
                  lineNo));
          supers.put(name, superNames(name, cls.group(2)));
          currentClass = name;
          classExported = exported;
          classLevel = brace + 1;
        } else {
          addTopLevel(decls, path, trimmed, lineNo, testFile, supers);
        }
      } else if (brace == classLevel) {
        Matcher method = METHOD.matcher(trimmed);
        if (method.find() && !TsCallers.NOT_METHODS.contains(method.group(1))) {
          decls.add(
              new Decl(
                  "constructor".equals(method.group(1)) ? "constructor" : "method",
                  method.group(1),
                  currentClass,
                  classExported,
                  testFile || TsSupport.isTestName(currentClass),
                  path,
                  lineNo,
                  lineNo));
        } else {
          Matcher field = FIELD.matcher(trimmed);
          if (field.find() && !TsCallers.NOT_METHODS.contains(field.group(1))) {
            decls.add(
                new Decl(
                    "field",
                    field.group(1),
                    currentClass,
                    classExported,
                    testFile || TsSupport.isTestName(currentClass),
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
        classExported = false;
      }
    }
    return decls;
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

  static void addTopLevel(
      List<Decl> decls,
      String path,
      String trimmed,
      int lineNo,
      boolean testFile,
      Map<String, List<String>> supers) {
    Matcher fn = FUNCTION.matcher(trimmed);
    if (fn.find()) {
      decls.add(
          new Decl(
              "function",
              fn.group(1),
              "",
              trimmed.startsWith("export"),
              testFile,
              path,
              lineNo,
              lineNo));
      return;
    }
    Matcher constFn = CONST_FN.matcher(trimmed);
    if (constFn.find()) {
      decls.add(
          new Decl(
              "function",
              constFn.group(1),
              "",
              trimmed.startsWith("export"),
              testFile,
              path,
              lineNo,
              lineNo));
      return;
    }
    Matcher iface = INTERFACE.matcher(trimmed);
    if (iface.find()) {
      decls.add(
          new Decl(
              "type",
              iface.group(1),
              iface.group(1),
              trimmed.startsWith("export"),
              testFile || TsSupport.isTestName(iface.group(1)),
              path,
              lineNo,
              lineNo));
      supers.put(iface.group(1), superNames(iface.group(1), iface.group(2)));
      return;
    }
    Matcher type = TYPE_ALIAS.matcher(trimmed);
    if (type.find()) {
      decls.add(
          new Decl(
              "type",
              type.group(1),
              type.group(1),
              trimmed.startsWith("export"),
              testFile || TsSupport.isTestName(type.group(1)),
              path,
              lineNo,
              lineNo));
    }
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
    Set<String> starExports = new LinkedHashSet<>();
    Matcher named = IMPORT_NAMED.matcher(source);
    while (named.find()) {
      String spec = named.group(2);
      modules.add(spec);
      for (String part : named.group(1).split(",")) {
        String bit = part.trim().replaceFirst("^type\\s+", "");
        if (bit.isEmpty()) {
          continue;
        }
        String[] alias = bit.split("\\s+as\\s+");
        String local = alias[alias.length - 1].trim();
        if (!local.isEmpty()) {
          bindings.put(local, spec);
        }
      }
    }
    Matcher def = IMPORT_DEFAULT.matcher(source);
    while (def.find()) {
      modules.add(def.group(2));
      bindings.put(def.group(1), def.group(2));
    }
    Matcher star = IMPORT_STAR.matcher(source);
    while (star.find()) {
      modules.add(star.group(2));
      bindings.put(star.group(1), star.group(2));
    }
    Matcher req = IMPORT_REQUIRE.matcher(source);
    while (req.find()) {
      modules.add(req.group(2));
      bindings.put(req.group(1), req.group(2));
    }
    Matcher exportStar = EXPORT_STAR.matcher(source);
    while (exportStar.find()) {
      starExports.add(exportStar.group(1));
      modules.add(exportStar.group(1));
    }
    return new Imports(bindings, modules, starExports);
  }
}
