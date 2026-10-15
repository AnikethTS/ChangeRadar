package io.github.anikethts.changeradar.core;

import java.util.Objects;

public record ImpactFinding(
    String type, Confidence confidence, String summary, String symbolId, SourceLocation location) {

  public ImpactFinding {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(confidence, "confidence");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(symbolId, "symbolId");
    Objects.requireNonNull(location, "location");
  }
}
