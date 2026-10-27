package io.github.anikethts.changeradar.analyzer.shell;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;

final class ShellSupport {

  private ShellSupport() {}

  static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  static boolean isShellPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isShellSource();
  }

  static int lineCount(String source) {
    if (source.isEmpty()) {
      return 1;
    }
    int lines = 1;
    for (int i = 0; i < source.length(); i++) {
      if (source.charAt(i) == '\n') {
        lines++;
      }
    }
    if (source.charAt(source.length() - 1) == '\n') {
      lines--;
    }
    return Math.max(lines, 1);
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
