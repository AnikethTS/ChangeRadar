package io.github.anikethts.changeradar.analyzer.php;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.LanguageAnalyzer;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.LineRange;
import io.github.anikethts.changeradar.core.SourceLocation;
import io.github.anikethts.changeradar.core.SourcePaths;
import io.github.anikethts.changeradar.core.StructuralScan;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHP analyzer: maps changed lines to classes and functions, then finds name-resolvable callers and
 * related tests. This is not php-parser; unmatched braces fail the file. {@code private}/{@code
 * protected} members are not public API.
 */
public final class PhpLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "php";

  private static final Pattern CLASS =
      Pattern.compile("^(?:(?:abstract|final)\\s+)?class\\s+([A-Za-z_][\\w]*)\\b(.*)");
  private static final Pattern INTERFACE_OR_ENUM =
      Pattern.compile("^(?:interface|enum)\\s+([A-Za-z_][\\w]*)\\b(.*)");
  private static final Pattern FIELD =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|readonly|var)\\s+)+"
              + "(?:[\\\\A-Za-z_][\\w\\\\]*\\s+)?\\$([A-Za-z_][\\w]*)");
  private static final Pattern TYPED_PROP =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|readonly)\\s+)+([A-Za-z_][\\w]*)\\s+\\$");
  private static final Pattern ATTRIBUTE = Pattern.compile("#\\[\\\\?([A-Za-z_][\\w]*)");
  private static final Pattern FIELD_ACCESS =
      Pattern.compile("(?:->|::)\\$?([A-Za-z_][\\w]*)\\s*(?!\\()");
  private static final Set<String> SUPER_KEYWORDS = Set.of("extends", "implements");
  private static final Set<String> INJECT_ATTRS = Set.of("Inject", "Autowired", "Resource");
  private static final Pattern FUNCTION =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|final|abstract)\\s+)*function\\s+&?\\s*([A-Za-z_][\\w]*)");
  private static final Pattern CALL =
      Pattern.compile("(?:([A-Za-z_][\\w]*)\\s*(?:->|::))?([A-Za-z_][\\w]*)\\s*\\(");
  private static final Pattern NEW_TYPE =
      Pattern.compile("\\bnew\\s+\\\\?([A-Za-z_][\\w]*)\\s*\\(");
  private static final Pattern USE =
      Pattern.compile("(?m)^\\s*use\\s+(?:function\\s+|const\\s+)?([^;]+);");
  private static final Pattern REQUIRE =
      Pattern.compile("\\b(?:require|include)(?:_once)?\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
  private static final List<String> MODULE_SUFFIXES = List.of(".php");
  private static final Set<String> NOT_CALLS =
      Set.of(
          "if",
          "elseif",
          "for",
          "foreach",
          "while",
          "switch",
          "catch",
          "function",
          "class",
          "return",
          "isset",
          "empty",
          "unset",
          "echo",
          "print",
          "array",
          "list",
          "match");

  @Override
  public String languageId() {
    return LANGUAGE_ID;
  }

  @Override
  public boolean supports(ChangedFile file) {
    return file.isPhpSource();
  }

  @Override
  public List<Limitation> limitationsFor(Path repositoryRoot, List<ChangedFile> changedFiles) {
    return List.of();
  }

  @Override
  public AnalysisResult analyze(
      List<ChangedFile> changedFiles,
      Map<String, String> headFiles,
      Map<String, String> baseFiles) {
    List<Limitation> limitations = new ArrayList<>();
    List<ChangedSymbol> changedSymbols = new ArrayList<>();
    Map<String, ParsedFile> parsedHead = new LinkedHashMap<>();

    for (Map.Entry<String, String> entry : headFiles.entrySet()) {
      if (!isPhpPath(entry.getKey())) {
        continue;
      }
      parse(entry.getKey(), entry.getValue(), parsedHead, limitations);
    }

    for (ChangedFile changed : changedFiles) {
      if (!supports(changed)) {
        continue;
      }
      if (changed.changeType() == ChangeType.DELETE) {
        ParsedFile baseFile = parseBase(changed.path(), baseFiles, limitations);
        if (baseFile != null) {
          for (Decl decl : baseFile.decls) {
            changedSymbols.add(decl.toSymbol(ChangeType.DELETE));
          }
        }
        continue;
      }
      ParsedFile file = parsedHead.get(changed.path());
      if (file == null) {
        continue;
      }
      List<LineRange> hunks = changed.headLineRanges();
      boolean wholeFile = hunks.isEmpty() || changed.changeType() == ChangeType.ADD;
      Set<String> headKeys = new LinkedHashSet<>();
      for (Decl decl : file.decls) {
        headKeys.add(decl.identity());
        if (wholeFile || overlaps(hunks, decl.startLine, decl.endLine)) {
          ChangeType type =
              changed.changeType() == ChangeType.ADD ? ChangeType.ADD : ChangeType.MODIFY;
          changedSymbols.add(decl.toSymbol(type));
        }
      }
      if (changed.changeType() == ChangeType.MODIFY || changed.changeType() == ChangeType.RENAME) {
        ParsedFile baseFile =
            parseBase(
                changed.previousPath() != null ? changed.previousPath() : changed.path(),
                baseFiles,
                limitations);
        if (baseFile != null) {
          for (Decl decl : baseFile.decls) {
            if (!headKeys.contains(decl.identity())) {
              changedSymbols.add(decl.toSymbol(ChangeType.DELETE));
            }
          }
        }
      }
    }

    changedSymbols.sort(
        Comparator.comparing((ChangedSymbol s) -> s.location().path())
            .thenComparing(s -> s.location().startLine())
            .thenComparing(ChangedSymbol::id));

    List<ImpactFinding> findings = findCallers(parsedHead, changedSymbols);
    findings.addAll(findStructural(parsedHead, changedSymbols));
    findings.addAll(publicApiFindings(changedSymbols));
    findings.sort(
        Comparator.comparing((ImpactFinding f) -> f.location().path())
            .thenComparing(f -> f.location().startLine())
            .thenComparing(ImpactFinding::summary));

    List<TestRecommendation> tests = recommendTests(parsedHead, changedSymbols, findings);
    tests.sort(
        Comparator.comparing(TestRecommendation::path).thenComparing(TestRecommendation::name));

    boolean anyPhp = !parsedHead.isEmpty() || changedFiles.stream().anyMatch(this::supports);
    if (anyPhp) {
      limitations.add(
          new Limitation(
              "PHP_IMPORTS",
              "PHP callers are matched using use/require specifiers against HEAD files, not"
                  + " php-parser or Composer autoload. call_user_func may be missed or"
                  + " over-reported."));
      limitations.add(
          new Limitation(
              "PHP_STRUCTURAL_EDGES",
              "PHP overrides, property reads, and attributes are matched by name and use/require"
                  + " specifiers against HEAD files, not php-parser or Composer autoload."));
    }
    if (findings.stream()
        .anyMatch(
            finding ->
                "CALLER".equals(finding.type()) && finding.confidence() == Confidence.HEURISTIC)) {
      limitations.add(
          new Limitation(
              "PHP_NAME_BASED_DISPATCH",
              "Some PHP call sites could not be tied to a use/require of the changed symbol and"
                  + " were labeled HEURISTIC."));
    }

    return new AnalysisResult(changedSymbols, findings, tests, limitations);
  }

  private static ParsedFile parseBase(
      String path, Map<String, String> baseFiles, List<Limitation> limitations) {
    String source = baseFiles.get(path);
    if (source == null) {
      return null;
    }
    Map<String, ParsedFile> parsed = new LinkedHashMap<>();
    parse(path, source, parsed, limitations);
    return parsed.get(path);
  }

  private static void parse(
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
        new ParsedFile(path, codeLines, decls, isTestPath(path), extractImports(source), supers));
  }

  private static int stripAndMeasure(String source, List<String> codeLines) {
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

  private static List<Decl> extractDecls(
      String path, List<String> lines, Map<String, List<String>> supers) {
    List<Decl> decls = new ArrayList<>();
    int brace = 0;
    String currentClass = null;
    int classLevel = -1;
    boolean testFile = isTestPath(path);
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
    return decls;
  }

  private static List<String> superNames(String typeName, String tail) {
    List<String> names = new ArrayList<>();
    for (String id : StructuralScan.identifiers(tail)) {
      if (!SUPER_KEYWORDS.contains(id) && !id.equals(typeName)) {
        names.add(id);
      }
    }
    return names;
  }

  private static String typeName(String trimmed) {
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

  private static boolean isPublicFunction(String trimmed, String name) {
    if (trimmed.contains("private") || trimmed.contains("protected")) {
      return false;
    }
    return !name.startsWith("_") || name.startsWith("__");
  }

  private static int netBraces(String line) {
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

  private static List<ImpactFinding> findCallers(
      Map<String, ParsedFile> parsedHead, List<ChangedSymbol> changedSymbols) {
    Set<String> changedFns = new LinkedHashSet<>();
    Set<String> changedTypes = new LinkedHashSet<>();
    Map<String, Set<String>> changedNamesByPath = new LinkedHashMap<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("function".equals(symbol.kind())
          || "method".equals(symbol.kind())
          || "constructor".equals(symbol.kind())) {
        changedFns.add(symbol.name());
      }
      if ("type".equals(symbol.kind())) {
        changedTypes.add(symbol.name());
      } else if (!symbol.owner().isEmpty()) {
        changedTypes.add(symbol.owner());
      }
      changedNamesByPath
          .computeIfAbsent(symbol.location().path(), key -> new LinkedHashSet<>())
          .add(symbol.name());
    }
    List<ImpactFinding> findings = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (ParsedFile file : parsedHead.values()) {
      for (int i = 0; i < file.lines.size(); i++) {
        String line = file.lines.get(i);
        int lineNo = i + 1;
        Matcher neu = NEW_TYPE.matcher(line);
        while (neu.find()) {
          String typeName = neu.group(1);
          if (!changedTypes.contains(typeName)) {
            continue;
          }
          Optional<Confidence> confidence =
              importConfidence(
                  file, typeName, typeName, parsedHead, changedNamesByPath, file.testFile);
          if (confidence.isEmpty()) {
            continue;
          }
          String key = file.path + ":" + lineNo + ":new " + typeName;
          if (seen.add(key)) {
            findings.add(
                new ImpactFinding(
                    "CALLER",
                    confidence.get(),
                    file.path + " constructs " + typeName,
                    typeName,
                    new SourceLocation(file.path, lineNo, lineNo)));
          }
        }
        Matcher call = CALL.matcher(line);
        while (call.find()) {
          String receiver = call.group(1);
          String name = call.group(2);
          if (NOT_CALLS.contains(name) || !changedFns.contains(name)) {
            continue;
          }
          boolean definition =
              file.decls.stream()
                  .anyMatch(decl -> decl.startLine == lineNo && decl.name.equals(name));
          if (definition && receiver == null) {
            continue;
          }
          String binding = receiver != null ? receiver : name;
          Optional<Confidence> confidence =
              importConfidence(file, binding, name, parsedHead, changedNamesByPath, file.testFile);
          if (confidence.isEmpty()) {
            continue;
          }
          String symbolId = receiver != null ? receiver + "#" + name : name;
          String key = file.path + ":" + lineNo + ":" + symbolId;
          if (seen.add(key)) {
            findings.add(
                new ImpactFinding(
                    "CALLER",
                    confidence.get(),
                    file.path
                        + " calls "
                        + symbolId
                        + " ("
                        + confidence.get().name().toLowerCase()
                        + ")",
                    symbolId,
                    new SourceLocation(file.path, lineNo, lineNo)));
          }
        }
      }
    }
    return findings;
  }

  private static List<ImpactFinding> findStructural(
      Map<String, ParsedFile> parsedHead, List<ChangedSymbol> changedSymbols) {
    Set<String> changedFields = new LinkedHashSet<>();
    Set<String> changedTypeDecls = StructuralScan.typeDeclsWithoutNestedMembers(changedSymbols);
    Map<String, Set<String>> changedNamesByPath = new LinkedHashMap<>();
    Set<String> changedOwnerMethods = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("field".equals(symbol.kind())) {
        changedFields.add(symbol.name());
      }
      if ("method".equals(symbol.kind()) || "constructor".equals(symbol.kind())) {
        if (!symbol.owner().isEmpty()) {
          changedOwnerMethods.add(symbol.owner() + "#" + symbol.name());
        }
      }
      changedNamesByPath
          .computeIfAbsent(symbol.location().path(), key -> new LinkedHashSet<>())
          .add(symbol.name());
    }
    List<ImpactFinding> findings = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (ParsedFile file : parsedHead.values()) {
      for (Decl decl : file.decls) {
        if (!"method".equals(decl.kind) && !"constructor".equals(decl.kind)) {
          continue;
        }
        for (String parent : file.supers.getOrDefault(decl.owner, List.of())) {
          boolean parentChanged = changedOwnerMethods.contains(parent + "#" + decl.name);
          boolean childChanged = changedOwnerMethods.contains(decl.owner + "#" + decl.name);
          if (!parentChanged && !childChanged) {
            continue;
          }
          Optional<Confidence> confidence =
              importConfidence(
                  file, parent, decl.name, parsedHead, changedNamesByPath, file.testFile);
          if (confidence.isEmpty()) {
            continue;
          }
          StructuralScan.addFinding(
              seen,
              findings,
              new ImpactFinding(
                  "OVERRIDE",
                  confidence.get(),
                  decl.owner + "#" + decl.name + " overrides " + parent + "#" + decl.name,
                  parent + "#" + decl.name,
                  new SourceLocation(file.path, decl.startLine, decl.endLine)));
        }
      }
      for (int i = 0; i < file.lines.size(); i++) {
        String trimmed = file.lines.get(i).trim();
        int lineNo = i + 1;
        Matcher attribute = ATTRIBUTE.matcher(trimmed);
        if (attribute.find() && changedTypeDecls.contains(attribute.group(1))) {
          String name = attribute.group(1);
          Optional<Confidence> confidence =
              importConfidence(file, name, name, parsedHead, changedNamesByPath, file.testFile);
          if (confidence.isPresent()) {
            StructuralScan.addFinding(
                seen,
                findings,
                new ImpactFinding(
                    "ANNOTATION_USE",
                    confidence.get(),
                    file.path + " uses #[" + name + "]",
                    name,
                    new SourceLocation(file.path, lineNo, lineNo)));
          }
        }
        Matcher typed = TYPED_PROP.matcher(trimmed);
        if (typed.find() && changedTypeDecls.contains(typed.group(1))) {
          String typeName = typed.group(1);
          final int lineIndex = i;
          Optional<Confidence> confidence =
              importConfidence(
                  file, typeName, typeName, parsedHead, changedNamesByPath, file.testFile);
          if (confidence.isPresent()) {
            boolean inject =
                INJECT_ATTRS.stream()
                    .anyMatch(
                        ann -> trimmed.contains(ann) || previousIsAttribute(file, lineIndex, ann));
            StructuralScan.addFinding(
                seen,
                findings,
                new ImpactFinding(
                    inject ? "INJECTION" : "FIELD_TYPE",
                    confidence.get(),
                    file.path + (inject ? " injects " : " is typed as ") + typeName,
                    typeName,
                    new SourceLocation(file.path, lineNo, lineNo)));
          }
        }
        Matcher access = FIELD_ACCESS.matcher(file.lines.get(i));
        while (access.find()) {
          String name = access.group(1);
          if (!changedFields.contains(name) || NOT_CALLS.contains(name)) {
            continue;
          }
          Optional<Confidence> confidence =
              importConfidence(file, name, name, parsedHead, changedNamesByPath, file.testFile);
          if (confidence.isEmpty()) {
            continue;
          }
          StructuralScan.addFinding(
              seen,
              findings,
              new ImpactFinding(
                  "FIELD_ACCESS",
                  confidence.get(),
                  file.path + " reads " + name,
                  name,
                  new SourceLocation(file.path, lineNo, lineNo)));
        }
      }
    }
    return findings;
  }

  private static boolean previousIsAttribute(ParsedFile file, int index, String name) {
    if (index == 0) {
      return false;
    }
    return file.lines.get(index - 1).trim().contains("#[" + name);
  }

  private static Optional<Confidence> importConfidence(
      ParsedFile file,
      String binding,
      String symbolName,
      Map<String, ParsedFile> parsedHead,
      Map<String, Set<String>> changedNamesByPath,
      boolean allowTestHeuristic) {
    if (changedNamesByPath.getOrDefault(file.path, Set.of()).contains(symbolName)
        && file.decls.stream()
            .anyMatch(decl -> decl.name.equals(binding) || decl.name.equals(symbolName))) {
      return Optional.of(Confidence.DIRECT);
    }
    String spec = file.imports.bindings.get(binding);
    if (spec != null) {
      Optional<String> resolved =
          SourcePaths.resolveExisting(file.path, spec, parsedHead.keySet(), MODULE_SUFFIXES);
      if (resolved.isPresent()
          && changedNamesByPath.getOrDefault(resolved.get(), Set.of()).contains(symbolName)) {
        return Optional.of(Confidence.DIRECT);
      }
      if (resolved.isEmpty()) {
        return Optional.of(Confidence.HEURISTIC);
      }
    }
    for (String module : file.imports.modules) {
      Optional<String> resolved =
          SourcePaths.resolveExisting(file.path, module, parsedHead.keySet(), MODULE_SUFFIXES);
      if (resolved.isPresent()
          && changedNamesByPath.getOrDefault(resolved.get(), Set.of()).contains(symbolName)) {
        return Optional.of(Confidence.DIRECT);
      }
    }
    if (allowTestHeuristic) {
      return Optional.of(Confidence.HEURISTIC);
    }
    return Optional.empty();
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

  private static List<ImpactFinding> publicApiFindings(List<ChangedSymbol> changedSymbols) {
    List<ImpactFinding> findings = new ArrayList<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if (!symbol.publiclyVisible()) {
        continue;
      }
      String label =
          symbol.owner().isEmpty() || "type".equals(symbol.kind())
              ? symbol.name()
              : symbol.owner() + "#" + symbol.name();
      String summary =
          symbol.changeType() == ChangeType.DELETE
              ? "Public " + symbol.kind() + " " + label + " was removed"
              : "Public " + symbol.kind() + " " + label + " occupies changed lines";
      findings.add(
          new ImpactFinding(
              "PUBLIC_API", Confidence.DIRECT, summary, symbol.id(), symbol.location()));
    }
    return findings;
  }

  private static List<TestRecommendation> recommendTests(
      Map<String, ParsedFile> parsedHead,
      List<ChangedSymbol> changedSymbols,
      List<ImpactFinding> findings) {
    Map<String, TestRecommendation> tests = new LinkedHashMap<>();
    Set<String> names = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      names.add(symbol.name());
      if (!symbol.owner().isEmpty()) {
        names.add(symbol.owner());
      }
    }
    for (ParsedFile file : parsedHead.values()) {
      if (!file.testFile) {
        continue;
      }
      for (String changed : names) {
        if (file.path.contains(changed)) {
          tests.putIfAbsent(
              file.path,
              new TestRecommendation(
                  file.path,
                  file.path,
                  Confidence.HEURISTIC,
                  "Test path relates to changed symbol "
                      + changed
                      + ". Suggestion only; the test was not executed."));
        }
      }
    }
    for (ImpactFinding finding : findings) {
      ParsedFile file = parsedHead.get(finding.location().path());
      if (file == null || !file.testFile) {
        continue;
      }
      tests.putIfAbsent(
          file.path,
          new TestRecommendation(
              file.path,
              file.path,
              finding.confidence() == Confidence.DIRECT ? Confidence.DIRECT : Confidence.HEURISTIC,
              "This test "
                  + (finding.confidence() == Confidence.DIRECT ? "directly" : "may")
                  + " call changed code at "
                  + finding.location().path()
                  + ":"
                  + finding.location().startLine()
                  + ". Suggestion only; the test was not executed."));
    }
    return new ArrayList<>(tests.values());
  }

  private static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPhpPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isPhpSource();
  }

  private static boolean isTestPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase();
    String file = lower.substring(lower.lastIndexOf('/') + 1);
    return lower.contains("/test/") || lower.contains("/tests/") || file.endsWith("test.php");
  }

  private record ParsedFile(
      String path,
      List<String> lines,
      List<Decl> decls,
      boolean testFile,
      Imports imports,
      Map<String, List<String>> supers) {}

  record Imports(Map<String, String> bindings, Set<String> modules) {}

  private record Decl(
      String kind,
      String name,
      String owner,
      boolean publiclyVisible,
      boolean test,
      String path,
      int startLine,
      int endLine) {

    String identity() {
      return kind + ":" + owner + "#" + name;
    }

    ChangedSymbol toSymbol(ChangeType changeType) {
      return new ChangedSymbol(
          path + ":" + identity() + "@" + startLine,
          kind,
          name,
          owner,
          new SourceLocation(path, startLine, endLine),
          changeType,
          publiclyVisible);
    }
  }
}
