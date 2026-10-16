package io.github.anikethts.changeradar.analyzer.ts;

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
 * TypeScript/JavaScript analyzer: maps changed lines to classes, functions, and exported types,
 * then finds name-resolvable callers and related tests. This is not tsc; unmatched braces fail the
 * file, and name matches without a typed receiver are HEURISTIC.
 */
public final class TypeScriptLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "typescript";

  private static final Pattern CLASS =
      Pattern.compile(
          "^(?:export\\s+(?:default\\s+)?)?(?:abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)");
  private static final Pattern FUNCTION =
      Pattern.compile(
          "^(?:export\\s+(?:default\\s+)?)?(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)");
  private static final Pattern CONST_FN =
      Pattern.compile(
          "^(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\(|function\\b)");
  private static final Pattern TYPE_OR_INTERFACE =
      Pattern.compile("^(?:export\\s+)?(?:type|interface)\\s+([A-Za-z_$][\\w$]*)");
  private static final Pattern METHOD =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|async|readonly|override|abstract|get|set)\\s+)*"
              + "(?:async\\s+)?([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(");
  private static final Pattern CALL =
      Pattern.compile("(?:([A-Za-z_$][\\w$]*)\\s*\\.)?([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(");
  private static final Pattern NEW_TYPE =
      Pattern.compile("\\bnew\\s+([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(");
  private static final Pattern IMPORT_NAMED =
      Pattern.compile(
          "(?:import|export)\\s+(?:type\\s+)?\\{([^}]+)\\}\\s+from\\s+['\"]([^'\"]+)['\"]");
  private static final Pattern IMPORT_DEFAULT =
      Pattern.compile("import\\s+(?:type\\s+)?([A-Za-z_$][\\w$]*)\\s+from\\s+['\"]([^'\"]+)['\"]");
  private static final Pattern IMPORT_STAR =
      Pattern.compile("import\\s+\\*\\s+as\\s+([A-Za-z_$][\\w$]*)\\s+from\\s+['\"]([^'\"]+)['\"]");
  private static final Pattern IMPORT_REQUIRE =
      Pattern.compile(
          "(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*require\\s*\\(\\s*['\"]([^'\"]+)['\"]");
  private static final List<String> MODULE_SUFFIXES =
      List.of(".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs");
  private static final Set<String> NOT_METHODS =
      Set.of(
          "if",
          "for",
          "while",
          "switch",
          "catch",
          "function",
          "class",
          "return",
          "typeof",
          "await",
          "import",
          "export",
          "void",
          "super",
          "constructor");

  @Override
  public String languageId() {
    return LANGUAGE_ID;
  }

  @Override
  public boolean supports(ChangedFile file) {
    return file.isEcmaSource();
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
      if (!isEcmaPath(entry.getKey())) {
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
    findings.addAll(publicApiFindings(changedSymbols));
    findings.sort(
        Comparator.comparing((ImpactFinding f) -> f.location().path())
            .thenComparing(f -> f.location().startLine())
            .thenComparing(ImpactFinding::summary));

    List<TestRecommendation> tests = recommendTests(parsedHead, changedSymbols, findings);
    tests.sort(
        Comparator.comparing(TestRecommendation::path).thenComparing(TestRecommendation::name));

    boolean anyEcma = !parsedHead.isEmpty() || changedFiles.stream().anyMatch(this::supports);
    if (anyEcma) {
      limitations.add(
          new Limitation(
              "TS_IMPORTS",
              "TypeScript/JavaScript callers are matched using import/require specifiers against"
                  + " HEAD files, not tsc. Dynamic import(), eval, and path aliases may be missed"
                  + " or over-reported."));
    }
    if (findings.stream()
        .anyMatch(
            finding ->
                "CALLER".equals(finding.type()) && finding.confidence() == Confidence.HEURISTIC)) {
      limitations.add(
          new Limitation(
              "TS_NAME_BASED_DISPATCH",
              "Some TypeScript/JavaScript call sites could not be tied to an import of the changed"
                  + " symbol and were labeled HEURISTIC."));
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
              "TS_PARSE_FAILED",
              "Could not parse " + path + ": unmatched braces (depth " + depth + ")."));
      return;
    }
    List<Decl> decls = extractDecls(path, codeLines);
    into.put(
        path, new ParsedFile(path, codeLines, decls, isTestPath(path), extractImports(source)));
  }

  private static int stripAndMeasure(String source, List<String> codeLines) {
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

  private static List<Decl> extractDecls(String path, List<String> lines) {
    List<Decl> decls = new ArrayList<>();
    int brace = 0;
    String currentClass = null;
    int classLevel = -1;
    boolean classExported = false;
    boolean testFile = isTestPath(path);
    for (int i = 0; i < lines.size(); i++) {
      int lineNo = i + 1;
      String trimmed = lines.get(i).trim();
      if (currentClass == null) {
        Matcher cls = CLASS.matcher(trimmed);
        if (cls.find()) {
          boolean exported = trimmed.startsWith("export");
          decls.add(
              new Decl(
                  "type",
                  cls.group(1),
                  cls.group(1),
                  exported,
                  testFile || isTestName(cls.group(1)),
                  path,
                  lineNo,
                  lineNo));
          currentClass = cls.group(1);
          classExported = exported;
          classLevel = brace + 1;
        } else {
          addTopLevel(decls, path, trimmed, lineNo, testFile);
        }
      } else if (brace == classLevel) {
        Matcher method = METHOD.matcher(trimmed);
        if (method.find() && !NOT_METHODS.contains(method.group(1))) {
          decls.add(
              new Decl(
                  "constructor".equals(method.group(1)) ? "constructor" : "method",
                  method.group(1),
                  currentClass,
                  classExported,
                  testFile || isTestName(currentClass),
                  path,
                  lineNo,
                  lineNo));
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

  private static void addTopLevel(
      List<Decl> decls, String path, String trimmed, int lineNo, boolean testFile) {
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
    Matcher type = TYPE_OR_INTERFACE.matcher(trimmed);
    if (type.find()) {
      decls.add(
          new Decl(
              "type",
              type.group(1),
              type.group(1),
              trimmed.startsWith("export"),
              testFile || isTestName(type.group(1)),
              path,
              lineNo,
              lineNo));
    }
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
          if (NOT_METHODS.contains(name) || !changedFns.contains(name)) {
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
              ? "Exported " + symbol.kind() + " " + label + " was removed"
              : "Exported " + symbol.kind() + " " + label + " occupies changed lines";
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
    Set<String> changedTypeNames = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("type".equals(symbol.kind()) || !symbol.owner().isEmpty()) {
        changedTypeNames.add("type".equals(symbol.kind()) ? symbol.name() : symbol.owner());
      } else {
        changedTypeNames.add(symbol.name());
      }
    }
    for (ParsedFile file : parsedHead.values()) {
      if (!file.testFile) {
        continue;
      }
      String testName = file.path;
      Optional<Decl> type =
          file.decls.stream().filter(decl -> "type".equals(decl.kind)).findFirst();
      if (type.isPresent()) {
        testName = type.get().name;
      }
      for (String changed : changedTypeNames) {
        if (testName.contains(changed) || file.path.contains(changed)) {
          tests.putIfAbsent(
              file.path,
              new TestRecommendation(
                  testName,
                  file.path,
                  Confidence.HEURISTIC,
                  "Test path or name relates to changed symbol "
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
      String testName =
          file.decls.stream()
              .filter(decl -> "type".equals(decl.kind))
              .map(decl -> decl.name)
              .findFirst()
              .orElse(file.path);
      tests.putIfAbsent(
          file.path,
          new TestRecommendation(
              testName,
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

  private static boolean isEcmaPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isEcmaSource();
  }

  private static boolean isTestPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase();
    return lower.contains(".test.")
        || lower.contains(".spec.")
        || lower.contains("/__tests__/")
        || lower.contains("/test/")
        || lower.contains("/tests/");
  }

  private static boolean isTestName(String name) {
    return name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("Spec");
  }

  private record ParsedFile(
      String path, List<String> lines, List<Decl> decls, boolean testFile, Imports imports) {}

  record Imports(Map<String, String> bindings, Set<String> modules) {}

  private record Decl(
      String kind,
      String name,
      String owner,
      boolean exported,
      boolean test,
      String path,
      int startLine,
      int endLine) {

    String identity() {
      return kind + ":" + owner + "#" + name;
    }

    ChangedSymbol toSymbol(ChangeType changeType) {
      String id = path + ":" + identity() + "@" + startLine;
      return new ChangedSymbol(
          id,
          kind,
          name,
          owner,
          new SourceLocation(path, startLine, endLine),
          changeType,
          exported);
    }
  }
}
