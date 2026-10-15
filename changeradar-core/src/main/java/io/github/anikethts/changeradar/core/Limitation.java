package io.github.anikethts.changeradar.core;

import java.util.Objects;

/** An honest gap in analysis, not a finding. */
public record Limitation(String code, String message) {

  public Limitation {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
  }
}
