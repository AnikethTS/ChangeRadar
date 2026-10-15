package io.github.anikethts.changeradar.core;

import java.util.Objects;

/** A path changed between two Git revisions. */
public record ChangedFile(String path, ChangeType changeType, String previousPath) {

  public ChangedFile {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(changeType, "changeType");
  }

  public boolean isJavaSource() {
    return path.endsWith(".java");
  }
}
