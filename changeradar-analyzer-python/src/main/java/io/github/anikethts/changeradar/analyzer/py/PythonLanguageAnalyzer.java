package io.github.anikethts.changeradar.analyzer.py;

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
 * Python analyzer: maps changed lines to classes and functions, then finds name-resolvable callers
 * and related tests. This is not CPython; unclosed triples fail the file, and name matches without
 * a receiver are HEURISTIC. Leading-underscore names are treated as private.
 */
public final class PythonLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "python";

  private static final Pattern CLASS =
      Pattern.compile("^class\\s+([A-Za-z_][\\w]*)\\s*(?:\\(([^)]*)\\))?");
  private static final Pattern FIELD = Pattern.compile("^([A-Za-z_][\\w]*)\\s*(:[^=]+)?\\s*=");
  private static final Pattern TYPED_ATTR =
      Pattern.compile("^([A-Za-z_][\\w]*)\\s*:\\s*([A-Za-z_][\\w]*)");
  private static final Pattern DECORATOR = Pattern.compile("^@([A-Za-z_][\\w]*)");
  private static final Pattern FIELD_ACCESS =
      Pattern.compile("([A-Za-z_][\\w]*)\\s*\\.\\s*([A-Za-z_][\\w]*)\\s*(?!\\()");
  private static final Set<String> INJECT_DECORATORS = Set.of("inject", "Inject", "autowired");
  private static final Pattern DEF = Pattern.compile("^(?:async\\s+)?def\\s+([A-Za-z_][\\w]*)");
  private static final Pattern CALL =
      Pattern.compile("(?:([A-Za-z_][\\w]*)\\s*\\.)?([A-Za-z_][\\w]*)\\s*\\(");
  private static final Pattern FROM_IMPORT =
      Pattern.compile("(?m)^\\s*from\\s+([\\w.]+)\\s+import\\s+(.+)$");
  private static final Pattern IMPORT_MODULE =
      Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?");
  private static final List<String> MODULE_SUFFIXES = List.of(".py", ".pyi");
  private static final Set<String> NOT_CALLS =
      Set.of(
          "if", "elif", "for", "while", "def", "class", "return", "with", "except", "assert",
          "lambda", "not", "and", "or", "in", "is");

  @Override
  public String languageId() {
    return LANGUAGE_ID;
  }

  @Override
  public boolean supports(ChangedFile file) {
    return file.isPythonSource();
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
      if (!isPythonPath(entry.getKey())) {
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

    boolean anyPython = !parsedHead.isEmpty() || changedFiles.stream().anyMatch(this::supports);
    if (anyPython) {
      limitations.add(
          new Limitation(
              "PY_IMPORTS",
              "Python callers are matched using import specifiers against HEAD files, not CPython."
                  + " getattr, importlib, and star imports may be missed or over-reported."));
      limitations.add(
          new Limitation(
              "PY_STRUCTURAL_EDGES",
              "Python overrides, attribute reads, and decorators are matched by name and imports"
                  + " against HEAD files, not CPython."));
    }
    if (findings.stream()
        .anyMatch(
            finding ->
                "CALLER".equals(finding.type()) && finding.confidence() == Confidence.HEURISTIC)) {
      limitations.add(
          new Limitation(
              "PY_NAME_BASED_DISPATCH",
              "Some Python call sites could not be tied to an import of the changed symbol and"
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
        new ParsedFile(path, codeLines, decls, isTestPath(path), extractImports(source), supers));
  }

  private static boolean mask(String source, List<String> codeLines) {
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

  private static List<Decl> extractDecls(
      String path, List<String> lines, Map<String, List<String>> supers) {
    List<Decl> decls = new ArrayList<>();
    String currentClass = null;
    int classIndent = -1;
    boolean testFile = isTestPath(path);
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
                  "type", name, name, isPublicName(name), testFile, path, lineNo, lineNo, indent));
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
                  isPublicName(name),
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
                  isPublicName(name),
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
                    isPublicName(field.group(1)),
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

  private static List<String> superNames(String typeName, String bases) {
    List<String> names = new ArrayList<>();
    for (String id : StructuralScan.identifiers(bases)) {
      if (!id.equals(typeName) && !"object".equals(id) && !"metaclass".equals(id)) {
        names.add(id);
      }
    }
    return names;
  }

  private static List<Decl> closeBodies(List<Decl> decls, int lineCount) {
    List<Decl> closed = new ArrayList<>();
    for (int i = 0; i < decls.size(); i++) {
      Decl decl = decls.get(i);
      int end = lineCount;
      for (int j = i + 1; j < decls.size(); j++) {
        if (decls.get(j).indent <= decl.indent) {
          end = decls.get(j).startLine - 1;
          break;
        }
      }
      closed.add(
          new Decl(
              decl.kind,
              decl.name,
              decl.owner,
              decl.publiclyVisible,
              decl.test,
              decl.path,
              decl.startLine,
              Math.max(decl.startLine, end),
              decl.indent));
    }
    return closed;
  }

  private static int leadingIndent(String line) {
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

  private static boolean isPublicName(String name) {
    if (name.startsWith("__") && name.endsWith("__")) {
      return true;
    }
    return !name.startsWith("_");
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
        Matcher call = CALL.matcher(line);
        while (call.find()) {
          String receiver = call.group(1);
          String name = call.group(2);
          if (NOT_CALLS.contains(name)) {
            continue;
          }
          boolean definition =
              file.decls.stream()
                  .anyMatch(decl -> decl.startLine == lineNo && decl.name.equals(name));
          if (definition && receiver == null) {
            continue;
          }
          if (changedTypes.contains(name) && receiver == null) {
            Optional<Confidence> constructed =
                importConfidence(file, name, name, parsedHead, changedNamesByPath, file.testFile);
            if (constructed.isPresent()) {
              String key = file.path + ":" + lineNo + ":new " + name;
              if (seen.add(key)) {
                findings.add(
                    new ImpactFinding(
                        "CALLER",
                        constructed.get(),
                        file.path + " constructs " + name,
                        name,
                        new SourceLocation(file.path, lineNo, lineNo)));
              }
            }
            continue;
          }
          if (!changedFns.contains(name)) {
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
        Matcher decorator = DECORATOR.matcher(trimmed);
        if (decorator.find() && changedTypeDecls.contains(decorator.group(1))) {
          String name = decorator.group(1);
          Optional<Confidence> confidence =
              importConfidence(file, name, name, parsedHead, changedNamesByPath, file.testFile);
          if (confidence.isPresent()) {
            StructuralScan.addFinding(
                seen,
                findings,
                new ImpactFinding(
                    "ANNOTATION_USE",
                    confidence.get(),
                    file.path + " uses @" + name,
                    name,
                    new SourceLocation(file.path, lineNo, lineNo)));
          }
        }
        Matcher typed = TYPED_ATTR.matcher(trimmed);
        if (typed.find() && changedTypeDecls.contains(typed.group(2))) {
          String typeName = typed.group(2);
          final int lineIndex = i;
          Optional<Confidence> confidence =
              importConfidence(
                  file, typeName, typeName, parsedHead, changedNamesByPath, file.testFile);
          if (confidence.isPresent()) {
            boolean inject =
                INJECT_DECORATORS.stream()
                    .anyMatch(ann -> previousIsDecorator(file, lineIndex, ann));
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
          String name = access.group(2);
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

  private static boolean previousIsDecorator(ParsedFile file, int index, String name) {
    if (index == 0) {
      return false;
    }
    return file.lines.get(index - 1).trim().startsWith("@" + name);
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
        if (file.path.contains(changed) || file.path.contains(toSnake(changed))) {
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
      String testName =
          file.decls.stream()
              .filter(decl -> "function".equals(decl.kind) && decl.name.startsWith("test_"))
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

  private static String toSnake(String name) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c) && i > 0) {
        out.append('_');
      }
      out.append(Character.toLowerCase(c));
    }
    return out.toString();
  }

  private static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPythonPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isPythonSource();
  }

  private static boolean isTestPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase();
    String file = lower.substring(lower.lastIndexOf('/') + 1);
    return lower.contains("/test/")
        || lower.contains("/tests/")
        || file.startsWith("test_")
        || file.endsWith("_test.py")
        || file.endsWith("_test.pyi");
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
      int endLine,
      int indent) {

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
