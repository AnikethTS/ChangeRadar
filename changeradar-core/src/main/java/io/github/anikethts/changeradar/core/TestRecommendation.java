package io.github.anikethts.changeradar.core;

import java.util.Map;
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

  /** Keeps the stronger confidence when two recommendations name the same test. */
  public static void prefer(
      Map<String, TestRecommendation> tests, String key, TestRecommendation candidate) {
    TestRecommendation current = tests.get(key);
    if (current == null || candidate.confidence().ordinal() < current.confidence().ordinal()) {
      tests.put(key, candidate);
    }
  }

  public TestRecommendation withEvidence(
      boolean coversChangedLines, ExecutionStatus execution, String reason) {
    return new TestRecommendation(name, path, confidence, reason, coversChangedLines, execution);
  }
}
