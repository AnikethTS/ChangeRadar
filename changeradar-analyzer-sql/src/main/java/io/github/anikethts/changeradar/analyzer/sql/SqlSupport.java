package io.github.anikethts.changeradar.analyzer.sql;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;
import java.util.Locale;

final class SqlSupport {

  private SqlSupport() {}

  static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  static boolean isSqlPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isSqlSource();
  }

  static boolean isTestPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    String file = lower.substring(lower.lastIndexOf('/') + 1);
    return lower.contains("/test/")
        || lower.contains("/tests/")
        || file.startsWith("test_")
        || file.endsWith("_test.sql")
        || file.contains(".test.sql");
  }

  static int lineOf(String source, int index) {
    int line = 1;
    int end = Math.min(index, source.length());
    for (int i = 0; i < end; i++) {
      if (source.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }
}
