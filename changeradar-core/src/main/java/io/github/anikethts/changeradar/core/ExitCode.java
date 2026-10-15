package io.github.anikethts.changeradar.core;

/**
 * Documented CLI/CI exit-code contract.
 *
 * <p>0 is a completed analysis with no enabled blocking policy violated. 1 is a configured blocking
 * policy violation. 2 is invalid input, a missing Git ref, or an analyzer/tool failure. A heuristic
 * finding never maps to 1 unless a policy explicitly says so.
 */
public enum ExitCode {
  SUCCESS(0),
  POLICY_VIOLATION(1),
  FAILURE(2);

  private final int code;

  ExitCode(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }
}
