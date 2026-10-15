package io.github.anikethts.changeradar.core;

import java.util.Objects;

public record SourceLocation(String path, int startLine, int endLine) {

  public SourceLocation {
    Objects.requireNonNull(path, "path");
  }
}
