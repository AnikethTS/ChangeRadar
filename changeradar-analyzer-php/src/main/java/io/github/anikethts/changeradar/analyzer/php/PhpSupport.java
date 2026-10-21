package io.github.anikethts.changeradar.analyzer.php;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;

final class PhpSupport {

  static final List<String> MODULE_SUFFIXES = List.of(".php");

  private PhpSupport() {}

  static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  static boolean isPhpPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isPhpSource();
  }

  static boolean isTestPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase();
    String file = lower.substring(lower.lastIndexOf('/') + 1);
    return lower.contains("/test/") || lower.contains("/tests/") || file.endsWith("test.php");
  }
}
