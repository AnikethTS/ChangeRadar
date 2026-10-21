package io.github.anikethts.changeradar.analyzer.ts;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;

final class TsSupport {

  static final List<String> MODULE_SUFFIXES =
      List.of(".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs");

  private TsSupport() {}

  static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  static boolean isEcmaPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isEcmaSource();
  }

  static boolean isTestPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase();
    return lower.contains(".test.")
        || lower.contains(".spec.")
        || lower.contains("/__tests__/")
        || lower.contains("/test/")
        || lower.contains("/tests/");
  }

  static boolean isTestName(String name) {
    return name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("Spec");
  }
}
