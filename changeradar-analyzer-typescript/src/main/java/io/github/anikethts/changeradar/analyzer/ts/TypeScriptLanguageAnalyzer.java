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
import io.github.anikethts.changeradar.core.TestRecommendation;
import io.github.anikethts.changeradar.core.TsConfigPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TypeScript/JavaScript analyzer: maps changed lines to classes, functions, and exported types,
 * then finds name-resolvable callers and related tests. This is not tsc; unmatched braces fail the
 * file, and name matches without a typed receiver are HEURISTIC.
 */
public final class TypeScriptLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "typescript";

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
    TsConfigPaths.PathMap pathMap = TsConfigPaths.index(headFiles);

    for (Map.Entry<String, String> entry : headFiles.entrySet()) {
      if (!TsSupport.isEcmaPath(entry.getKey())) {
        continue;
      }
      TsParsing.parse(entry.getKey(), entry.getValue(), parsedHead, limitations);
    }

    for (ChangedFile changed : changedFiles) {
      if (!supports(changed)) {
        continue;
      }
      if (changed.changeType() == ChangeType.DELETE) {
        ParsedFile baseFile = TsParsing.parseBase(changed.path(), baseFiles, limitations);
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
        if (wholeFile || TsSupport.overlaps(hunks, decl.startLine(), decl.endLine())) {
          ChangeType type =
              changed.changeType() == ChangeType.ADD ? ChangeType.ADD : ChangeType.MODIFY;
          changedSymbols.add(decl.toSymbol(type));
        }
      }
      if (changed.changeType() == ChangeType.MODIFY || changed.changeType() == ChangeType.RENAME) {
        ParsedFile baseFile =
            TsParsing.parseBase(
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

    List<ImpactFinding> findings = TsCallers.findCallers(parsedHead, changedSymbols, pathMap);
    findings.addAll(TsCallers.findStructural(parsedHead, changedSymbols, pathMap));
    findings.addAll(TsRecommendations.publicApiFindings(changedSymbols));
    findings.sort(
        Comparator.comparing((ImpactFinding f) -> f.location().path())
            .thenComparing(f -> f.location().startLine())
            .thenComparing(ImpactFinding::summary));

    List<TestRecommendation> tests =
        TsRecommendations.recommendTests(parsedHead, changedSymbols, findings);
    tests.sort(
        Comparator.comparing(TestRecommendation::path).thenComparing(TestRecommendation::name));

    boolean anyEcma = !parsedHead.isEmpty() || changedFiles.stream().anyMatch(this::supports);
    if (anyEcma) {
      limitations.add(
          new Limitation(
              "TS_IMPORTS",
              "TypeScript/JavaScript callers are matched using import/require specifiers against"
                  + " HEAD files, following export * from, named export { … } from re-exports, and"
                  + " nearest tsconfig.json/jsconfig.json compilerOptions.paths. This is not tsc."
                  + " Dynamic import(), eval, extends, and unmapped aliases may be missed or"
                  + " over-reported."));
      limitations.add(
          new Limitation(
              "TS_STRUCTURAL_EDGES",
              "TypeScript/JavaScript overrides, field reads, decorators, and typed fields are"
                  + " matched by name and imports against HEAD files, not tsc."));
      if (pathMap.hasMappings()) {
        limitations.add(
            new Limitation(
                "TS_TSCONFIG_PATHS",
                "Path aliases came from nearest tsconfig.json/jsconfig.json compilerOptions.paths"
                    + " and baseUrl. extends, project references, and mappings with more than one"
                    + " * are ignored."));
      }
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

  static Imports extractImports(String source) {
    return TsParsing.extractImports(source);
  }
}
