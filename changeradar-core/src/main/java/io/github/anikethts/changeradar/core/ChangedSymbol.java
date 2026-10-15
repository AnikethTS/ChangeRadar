package io.github.anikethts.changeradar.core;

import java.util.Objects;

public record ChangedSymbol(
    String id,
    String kind,
    String name,
    String owner,
    SourceLocation location,
    ChangeType changeType,
    boolean publiclyVisible) {

  public ChangedSymbol {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(changeType, "changeType");
  }
}
