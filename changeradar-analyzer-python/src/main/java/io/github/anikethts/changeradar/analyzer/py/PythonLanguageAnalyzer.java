package io.github.anikethts.changeradar.analyzer.py;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.CallChains;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.LanguageAnalyzer;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.LineRange;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Python analyzer: maps changed lines to classes and functions, then finds name-resolvable callers
 * and related tests. This is not CPython; unclosed triples fail the file, and name matches without
 * a receiver are HEURISTIC. Leading-underscore names are treated as private.
 */
public final class PythonLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "python";

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
      if (!PySupport.isPythonPath(entry.getKey())) {
        continue;
      }
      PyParsing.parse(entry.getKey(), entry.getValue(), parsedHead, limitations);
    }

    for (ChangedFile changed : changedFiles) {
      if (!supports(changed)) {
        continue;
      }
      if (changed.changeType() == ChangeType.DELETE) {
        ParsedFile baseFile = PyParsing.parseBase(changed.path(), baseFiles, limitations);
        if (baseFile != null) {
          for (Decl decl : baseFile.decls()) {
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
      for (Decl decl : file.decls()) {
        headKeys.add(decl.identity());
        if (wholeFile || PySupport.overlaps(hunks, decl.startLine(), decl.endLine())) {
          ChangeType type =
              changed.changeType() == ChangeType.ADD ? ChangeType.ADD : ChangeType.MODIFY;
          changedSymbols.add(decl.toSymbol(type));
        }
      }
      if (changed.changeType() == ChangeType.MODIFY || changed.changeType() == ChangeType.RENAME) {
        ParsedFile baseFile =
            PyParsing.parseBase(
                changed.previousPath() != null ? changed.previousPath() : changed.path(),
                baseFiles,
                limitations);
        if (baseFile != null) {
          for (Decl decl : baseFile.decls()) {
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

    CallerScan callers = PyCallers.findCallers(parsedHead, changedSymbols);
    List<ImpactFinding> findings = new ArrayList<>(callers.findings());
    findings.addAll(PyCallers.findStructural(parsedHead, changedSymbols));
    findings.addAll(PyRecommendations.publicApiFindings(changedSymbols));
    findings.sort(
        Comparator.comparing((ImpactFinding f) -> f.location().path())
            .thenComparing(f -> f.location().startLine())
            .thenComparing(ImpactFinding::summary));

    List<TestRecommendation> tests =
        PyRecommendations.recommendTests(parsedHead, changedSymbols, findings);
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
      limitations.add(
          new Limitation(
              "PY_CALL_CHAINS",
              "Indirect Python callers follow import-resolved function and method edges up to "
                  + CallChains.MAX_DEPTH
                  + " hops (depth 1 DIRECT, deeper INDIRECT). Heuristic name matches are not"
                  + " chained. Indirect findings are capped at "
                  + CallChains.MAX_INDIRECT
                  + "."));
      if (callers.chainCapped()) {
        limitations.add(
            new Limitation(
                "PY_CALL_CHAIN_CAP",
                "Indirect Python caller findings were capped at " + CallChains.MAX_INDIRECT + "."));
      }
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

  static Imports extractImports(String source) {
    return PyParsing.extractImports(source);
  }
}
