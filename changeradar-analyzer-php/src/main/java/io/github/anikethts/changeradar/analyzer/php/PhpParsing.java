package io.github.anikethts.changeradar.analyzer.php;

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

final class PhpParsing {

  static final Pattern CLASS =
      Pattern.compile("^(?:(?:abstract|final)\\s+)?class\\s+([A-Za-z_][\\w]*)\\b(.*)");
  static final Pattern INTERFACE_OR_ENUM =
      Pattern.compile("^(?:interface|enum)\\s+([A-Za-z_][\\w]*)\\b(.*)");
  static final Pattern FIELD =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|readonly|var)\\s+)+"
              + "(?:[\\\\A-Za-z_][\\w\\\\]*\\s+)?\\$([A-Za-z_][\\w]*)");
  static final Pattern TYPED_PROP =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|readonly)\\s+)+([A-Za-z_][\\w]*)\\s+\\$");
  static final Pattern ATTRIBUTE = Pattern.compile("#\\[\\\\?([A-Za-z_][\\w]*)");
  static final Pattern FIELD_ACCESS = Pattern.compile("(?:->|::)\\$?([A-Za-z_][\\w]*)\\s*(?!\\()");
  static final Set<String> SUPER_KEYWORDS = Set.of("extends", "implements");
  static final Set<String> INJECT_ATTRS = Set.of("Inject", "Autowired", "Resource");
  static final Pattern FUNCTION =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|final|abstract)\\s+)*function\\s+&?\\s*([A-Za-z_][\\w]*)");
  static final Pattern CALL =
      Pattern.compile("(?:([A-Za-z_][\\w]*)\\s*(?:->|::))?([A-Za-z_][\\w]*)\\s*\\(");
  static final Pattern NEW_TYPE = Pattern.compile("\\bnew\\s+\\\\?([A-Za-z_][\\w]*)\\s*\\(");
  static final Pattern USE = Pattern.compile("(?m)^\\s*use\\s+(?:function\\s+|const\\s+)?([^;]+);");
  static final Pattern REQUIRE =
      Pattern.compile("\\b(?:require|include)(?:_once)?\\s*\\(?\\s*['\"]([^'\"]+)['\"]");

  private PhpParsing() {}

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
              "PHP_PARSE_FAILED",
              "Could not parse " + path + ": unmatched braces (depth " + depth + ")."));
      return;
    }
    Map<String, List<String>> supers = new LinkedHashMap<>();
    List<Decl> decls = extractDecls(path, codeLines, supers);
    into.put(
        path,
        new ParsedFile(
            path, codeLines, decls, PhpSupport.isTestPath(path), extractImports(source), supers));
  }

  static int stripAndMeasure(String source, List<String> codeLines) {
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inLineComment = false;
    boolean inBlockComment = false;
    boolean inSingle = false;
    boolean inDouble = false;
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
      if (c == '/' && next == '/') {
        inLineComment = true;
        i++;
        continue;
      }
      if (c == '#' && next != '[') {
        inLineComment = true;
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
    boolean testFile = PhpSupport.isTestPath(path);
    for (int i = 0; i < lines.size(); i++) {
      int lineNo = i + 1;
      String trimmed = lines.get(i).trim();
      if (currentClass == null) {
        String typeName = typeName(trimmed);
        if (typeName != null) {
          decls.add(new Decl("type", typeName, typeName, true, testFile, path, lineNo, lineNo));
          Matcher cls = CLASS.matcher(trimmed);
          Matcher iface = INTERFACE_OR_ENUM.matcher(trimmed);
          if (cls.find()) {
            supers.put(typeName, superNames(typeName, cls.group(2)));
          } else if (iface.find()) {
            supers.put(typeName, superNames(typeName, iface.group(2)));
          }
          currentClass = typeName;
          classLevel = brace + 1;
        } else {
          Matcher fn = FUNCTION.matcher(trimmed);
          if (fn.find()) {
            String name = fn.group(1);
            decls.add(
                new Decl(
                    "function",
                    name,
                    "",
                    isPublicFunction(trimmed, name),
                    testFile,
                    path,
                    lineNo,
                    lineNo));
          }
        }
      } else if (brace == classLevel) {
        Matcher fn = FUNCTION.matcher(trimmed);
        if (fn.find()) {
          String name = fn.group(1);
          String kind = "__construct".equals(name) ? "constructor" : "method";
          decls.add(
              new Decl(
                  kind,
                  name,
                  currentClass,
                  isPublicFunction(trimmed, name),
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
                    !trimmed.contains("private") && !trimmed.contains("protected"),
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

  static String typeName(String trimmed) {
    Matcher cls = CLASS.matcher(trimmed);
    if (cls.find()) {
      return cls.group(1);
    }
    Matcher iface = INTERFACE_OR_ENUM.matcher(trimmed);
    if (iface.find()) {
      return iface.group(1);
    }
    return null;
  }

  static boolean isPublicFunction(String trimmed, String name) {
    if (trimmed.contains("private") || trimmed.contains("protected")) {
      return false;
    }
    return !name.startsWith("_") || name.startsWith("__");
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
    Matcher use = USE.matcher(source);
    while (use.find()) {
      String clause = use.group(1).trim();
      String spec = clause;
      String local = clause;
      int as = clause.toLowerCase().lastIndexOf(" as ");
      if (as >= 0) {
        spec = clause.substring(0, as).trim();
        local = clause.substring(as + 4).trim();
      }
      int slash = spec.lastIndexOf('\\');
      if (as < 0 && slash >= 0) {
        local = spec.substring(slash + 1);
      }
      modules.add(spec);
      if (!local.isEmpty()) {
        bindings.put(local, spec);
      }
    }
    Matcher req = REQUIRE.matcher(source);
    while (req.find()) {
      modules.add(req.group(1));
    }
    return new Imports(bindings, modules);
  }
}
