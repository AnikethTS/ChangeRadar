package io.github.anikethts.changeradar.core;

import java.util.List;
import java.util.Locale;
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

  public boolean isPhpSource() {
    return path.toLowerCase().endsWith(".php");
  }

  public boolean isSqlSource() {
    return path.toLowerCase().endsWith(".sql");
  }

  /** A GitHub Actions workflow or a local {@code action.yml}. */
  public boolean isWorkflowSource() {
    String unix = path.replace('\\', '/');
    String lower = unix.toLowerCase(Locale.ROOT);
    boolean yaml = lower.endsWith(".yml") || lower.endsWith(".yaml");
    boolean workflowDir =
        lower.startsWith(".github/workflows/") || lower.contains("/.github/workflows/");
    if (workflowDir && yaml) {
      return true;
    }
    String file = lower.substring(lower.lastIndexOf('/') + 1);
    return file.equals("action.yml") || file.equals("action.yaml");
  }

  public boolean isShellSource() {
    return path.toLowerCase(Locale.ROOT).endsWith(".sh");
  }

  public boolean isPythonSource() {
    String lower = path.toLowerCase();
    return lower.endsWith(".py") || lower.endsWith(".pyi");
  }

  public boolean isKotlinSource() {
    String lower = path.toLowerCase();
    return lower.endsWith(".kt") || lower.endsWith(".kts");
  }

  public boolean isEcmaSource() {
    String lower = path.toLowerCase();
    return lower.endsWith(".ts")
        || lower.endsWith(".tsx")
        || lower.endsWith(".mts")
        || lower.endsWith(".cts")
        || lower.endsWith(".js")
        || lower.endsWith(".jsx")
        || lower.endsWith(".mjs")
        || lower.endsWith(".cjs");
  }
}
