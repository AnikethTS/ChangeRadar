package io.github.anikethts.changeradar.core;

import java.util.Objects;

/** Stable finding keys for comments, SARIF, suppressions, and baselines. */
public final class FindingIds {

  private FindingIds() {}

  public static String of(String type, String symbolId, SourceLocation location) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(symbolId, "symbolId");
    Objects.requireNonNull(location, "location");
    return type
        + "|"
        + symbolId
        + "|"
        + location.path().replace('\\', '/')
        + ":"
        + location.startLine();
  }
}
