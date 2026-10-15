package io.github.anikethts.changeradar.core;

import java.util.List;
import java.util.Objects;

public record PolicyResult(boolean blocking, List<String> violations) {

  public PolicyResult {
    Objects.requireNonNull(violations, "violations");
    violations = List.copyOf(violations);
  }

  public static PolicyResult none() {
    return new PolicyResult(false, List.of());
  }
}
