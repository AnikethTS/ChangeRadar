package io.github.anikethts.changeradar.core;

import java.util.Objects;

public record ChangedSymbol(
    String id,
    String kind,
    String name,
    String owner,
    SourceLocation location,
    ChangeType changeType,
    boolean publiclyVisible,
    String signature) {

  public ChangedSymbol {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(changeType, "changeType");
    signature = signature == null ? "" : signature;
  }

  public ChangedSymbol(
      String id,
      String kind,
      String name,
      String owner,
      SourceLocation location,
      ChangeType changeType,
      boolean publiclyVisible) {
    this(id, kind, name, owner, location, changeType, publiclyVisible, "");
  }

  /** Owner plus overload key, e.g. {@code demo.Demo#n(int)}. */
  public String methodKey() {
    if (signature.isEmpty()) {
      return owner.isEmpty() ? name : owner + "#" + name;
    }
    return owner + "#" + signature;
  }
}
