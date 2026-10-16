package io.github.anikethts.changeradar.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record AnalysisResult(
    List<ChangedSymbol> changedSymbols,
    List<ImpactFinding> findings,
    List<TestRecommendation> tests,
    List<Limitation> limitations) {

  public AnalysisResult {
    Objects.requireNonNull(changedSymbols, "changedSymbols");
    Objects.requireNonNull(findings, "findings");
    Objects.requireNonNull(tests, "tests");
    Objects.requireNonNull(limitations, "limitations");
    changedSymbols = List.copyOf(changedSymbols);
    findings = List.copyOf(findings);
    tests = List.copyOf(tests);
    limitations = List.copyOf(limitations);
  }

  public static AnalysisResult empty() {
    return new AnalysisResult(List.of(), List.of(), List.of(), List.of());
  }

  public static AnalysisResult merge(List<AnalysisResult> parts) {
    List<ChangedSymbol> symbols = new ArrayList<>();
    List<ImpactFinding> findings = new ArrayList<>();
    List<TestRecommendation> tests = new ArrayList<>();
    List<Limitation> limitations = new ArrayList<>();
    for (AnalysisResult part : parts) {
      symbols.addAll(part.changedSymbols());
      findings.addAll(part.findings());
      tests.addAll(part.tests());
      limitations.addAll(part.limitations());
    }
    symbols.sort(
        Comparator.comparing((ChangedSymbol s) -> s.location().path())
            .thenComparing(s -> s.location().startLine())
            .thenComparing(ChangedSymbol::id));
    findings.sort(
        Comparator.comparing((ImpactFinding f) -> f.location().path())
            .thenComparing(f -> f.location().startLine())
            .thenComparing(ImpactFinding::summary));
    tests.sort(
        Comparator.comparing(TestRecommendation::path).thenComparing(TestRecommendation::name));
    return new AnalysisResult(symbols, findings, tests, limitations);
  }
}
