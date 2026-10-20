package io.github.anikethts.changeradar.core;

import java.util.Objects;

public record ImpactFinding(
    String id,
    String type,
    Confidence confidence,
    String summary,
    String symbolId,
    SourceLocation location) {

  public ImpactFinding {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(confidence, "confidence");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(symbolId, "symbolId");
    Objects.requireNonNull(location, "location");
  }

  public ImpactFinding(
      String type,
      Confidence confidence,
      String summary,
      String symbolId,
      SourceLocation location) {
    this(FindingIds.of(type, symbolId, location), type, confidence, summary, symbolId, location);
  }
}
