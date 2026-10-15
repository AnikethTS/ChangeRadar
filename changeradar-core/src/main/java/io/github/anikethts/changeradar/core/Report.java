package io.github.anikethts.changeradar.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Report(
    String schemaVersion,
    String toolVersion,
    Instant generatedAt,
    AnalysisRequest request,
    List<ChangedFile> changedFiles,
    List<ChangedSymbol> changedSymbols,
    List<ImpactFinding> findings,
    List<TestRecommendation> tests,
    List<Limitation> limitations,
    PolicyResult policy) {

  public static final String SCHEMA_VERSION = "1.0.0";

  public Report {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(toolVersion, "toolVersion");
    Objects.requireNonNull(generatedAt, "generatedAt");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(changedFiles, "changedFiles");
    Objects.requireNonNull(changedSymbols, "changedSymbols");
    Objects.requireNonNull(findings, "findings");
    Objects.requireNonNull(tests, "tests");
    Objects.requireNonNull(limitations, "limitations");
    Objects.requireNonNull(policy, "policy");
    changedFiles = List.copyOf(changedFiles);
    changedSymbols = List.copyOf(changedSymbols);
    findings = List.copyOf(findings);
    tests = List.copyOf(tests);
    limitations = List.copyOf(limitations);
  }
}
