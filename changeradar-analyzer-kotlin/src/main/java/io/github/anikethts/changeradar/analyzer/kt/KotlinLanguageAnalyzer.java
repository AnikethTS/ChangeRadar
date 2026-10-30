package io.github.anikethts.changeradar.analyzer.kt;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.CallChains;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.FormatOnly;
import io.github.anikethts.changeradar.core.FormatTrivia;
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
 * Kotlin analyzer: maps changed lines to types and functions, then finds name-resolvable callers,
 * overrides, property reads, and annotation uses. This is not kotlinc; unmatched braces fail the
 * file. {@code private}/{@code internal} members are not public API.
 */
public final class KotlinLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "kotlin";

  @Override
  public String languageId() {
    return LANGUAGE_ID;
  }

  @Override
  public boolean supports(ChangedFile file) {
    return file.isKotlinSource();
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
    List<String> formatOnly = new ArrayList<>();
    Map<String, ParsedFile> parsedHead = new LinkedHashMap<>();

    for (Map.Entry<String, String> entry : headFiles.entrySet()) {
      if (!KtSupport.isKotlinPath(entry.getKey())) {
        continue;
      }
      KtParsing.parse(entry.getKey(), entry.getValue(), parsedHead, limitations);
    }

    for (ChangedFile changed : changedFiles) {
      if (!supports(changed)) {
        continue;
      }
      if (changed.changeType() == ChangeType.DELETE) {
        ParsedFile baseFile = KtParsing.parseBase(changed.path(), baseFiles, limitations);
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
      if (changed.changeType() == ChangeType.MODIFY) {
        String basePath = changed.previousPath() != null ? changed.previousPath() : changed.path();
        if (FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.KOTLIN, baseFiles.get(basePath), headFiles.get(changed.path()))) {
          formatOnly.add(changed.path());
          continue;
        }
      }
      List<LineRange> hunks = changed.headLineRanges();
      boolean wholeFile = hunks.isEmpty() || changed.changeType() == ChangeType.ADD;
      Set<String> headKeys = new LinkedHashSet<>();
      for (Decl decl : file.decls()) {
        headKeys.add(decl.identity());
        if (wholeFile || KtSupport.overlaps(hunks, decl.startLine(), decl.endLine())) {
          ChangeType type =
              changed.changeType() == ChangeType.ADD ? ChangeType.ADD : ChangeType.MODIFY;
          changedSymbols.add(decl.toSymbol(type));
        }
      }
      if (changed.changeType() == ChangeType.MODIFY || changed.changeType() == ChangeType.RENAME) {
        ParsedFile baseFile =
            KtParsing.parseBase(
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

    if (!formatOnly.isEmpty()) {
      limitations.add(FormatOnly.limitation(formatOnly));
    }

    changedSymbols.sort(
        Comparator.comparing((ChangedSymbol s) -> s.location().path())
            .thenComparing(s -> s.location().startLine())
            .thenComparing(ChangedSymbol::id));

    CallerScan callers = KtCallers.findCallers(parsedHead, changedSymbols);
    List<ImpactFinding> findings = new ArrayList<>(callers.findings());
    findings.addAll(KtCallers.findStructural(parsedHead, changedSymbols));
    findings.addAll(KtRecommendations.publicApiFindings(changedSymbols));
    findings.sort(
        Comparator.comparing((ImpactFinding f) -> f.location().path())
            .thenComparing(f -> f.location().startLine())
            .thenComparing(ImpactFinding::summary));

    List<TestRecommendation> tests =
        KtRecommendations.recommendTests(parsedHead, changedSymbols, findings);
    tests.sort(
        Comparator.comparing(TestRecommendation::path).thenComparing(TestRecommendation::name));

    boolean anyKotlin = !parsedHead.isEmpty() || changedFiles.stream().anyMatch(this::supports);
    if (anyKotlin) {
      limitations.add(
          new Limitation(
              "KT_IMPORTS",
              "Kotlin callers are matched using import specifiers and same-directory types against"
                  + " HEAD files, not kotlinc. Reflection and generated bindings may be missed or"
                  + " over-reported."));
      limitations.add(
          new Limitation(
              "KT_STRUCTURAL_EDGES",
              "Kotlin overrides, property reads, property types, and annotation uses are matched"
                  + " by name, imports, and same-directory types against HEAD files, not kotlinc."));
      limitations.add(
          new Limitation(
              "KT_CALL_CHAINS",
              "Indirect Kotlin callers follow import-resolved function and method edges up to "
                  + CallChains.MAX_DEPTH
                  + " hops (depth 1 DIRECT, deeper INDIRECT). Heuristic name matches are not"
                  + " chained. Indirect findings are capped at "
                  + CallChains.MAX_INDIRECT
                  + "."));
      if (callers.chainCapped()) {
        limitations.add(
            new Limitation(
                "KT_CALL_CHAIN_CAP",
                "Indirect Kotlin caller findings were capped at " + CallChains.MAX_INDIRECT + "."));
      }
    }
    if (findings.stream()
        .anyMatch(
            finding ->
                "CALLER".equals(finding.type()) && finding.confidence() == Confidence.HEURISTIC)) {
      limitations.add(
          new Limitation(
              "KT_NAME_BASED_DISPATCH",
              "Some Kotlin call sites could not be tied to an import of the changed symbol and"
                  + " were labeled HEURISTIC."));
    }

    return new AnalysisResult(changedSymbols, findings, tests, limitations);
  }
}
