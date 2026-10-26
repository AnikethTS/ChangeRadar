package io.github.anikethts.changeradar.analyzer.workflow;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;

final class WorkflowSupport {

  private WorkflowSupport() {}

  static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  static boolean isWorkflowPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isWorkflowSource();
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
}
