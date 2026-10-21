package io.github.anikethts.changeradar.core;

import java.util.Objects;

public record TestRecommendation(
    String name,
    String path,
    Confidence confidence,
    String reason,
    boolean coversChangedLines,
    ExecutionStatus execution) {

  public TestRecommendation {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(confidence, "confidence");
    Objects.requireNonNull(reason, "reason");
    execution = execution == null ? ExecutionStatus.UNKNOWN : execution;
  }

  public TestRecommendation(String name, String path, Confidence confidence, String reason) {
    this(name, path, confidence, reason, false, ExecutionStatus.UNKNOWN);
  }

  public TestRecommendation withEvidence(
      boolean coversChangedLines, ExecutionStatus execution, String reason) {
    return new TestRecommendation(name, path, confidence, reason, coversChangedLines, execution);
  }
}
