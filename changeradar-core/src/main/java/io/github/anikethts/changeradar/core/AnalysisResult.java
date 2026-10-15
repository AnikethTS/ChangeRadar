package io.github.anikethts.changeradar.core;

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
}
