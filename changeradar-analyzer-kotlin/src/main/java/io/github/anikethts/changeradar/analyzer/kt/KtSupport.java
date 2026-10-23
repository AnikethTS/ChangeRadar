package io.github.anikethts.changeradar.analyzer.kt;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;

final class KtSupport {

  static final List<String> MODULE_SUFFIXES = List.of(".kt", ".kts");

  private KtSupport() {}

  static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  static boolean isKotlinPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isKotlinSource();
  }

  static boolean isTestPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase();
    String file = lower.substring(lower.lastIndexOf('/') + 1);
    return lower.contains("/test/")
        || lower.contains("/tests/")
        || lower.contains("/src/test")
        || file.startsWith("test_")
        || file.endsWith("test.kt")
        || file.endsWith("test.kts");
  }

  static boolean isPublic(String trimmed) {
    return !trimmed.contains("private") && !trimmed.contains("internal");
  }
}
