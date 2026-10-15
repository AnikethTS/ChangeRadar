package io.github.anikethts.changeradar.core;

import java.util.List;
import java.util.Objects;

/** A path changed between two Git revisions. */
public record ChangedFile(
    String path, ChangeType changeType, String previousPath, List<LineRange> headLineRanges) {

  public ChangedFile {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(changeType, "changeType");
    headLineRanges = headLineRanges == null ? List.of() : List.copyOf(headLineRanges);
  }

  public ChangedFile(String path, ChangeType changeType, String previousPath) {
    this(path, changeType, previousPath, List.of());
  }

  public boolean isJavaSource() {
    return path.endsWith(".java");
  }
}
