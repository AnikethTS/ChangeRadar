package io.github.anikethts.changeradar.core;

/** Outcome from a provided JUnit XML report. UNKNOWN means no report or no matching case. */
public enum ExecutionStatus {
  UNKNOWN,
  PASSED,
  FAILED,
  SKIPPED,
  ERROR
}
