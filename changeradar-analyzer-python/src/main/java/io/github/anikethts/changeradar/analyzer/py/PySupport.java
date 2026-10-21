package io.github.anikethts.changeradar.analyzer.py;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;

final class PySupport {

  static final List<String> MODULE_SUFFIXES = List.of(".py", ".pyi");

  private PySupport() {}

  static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  static boolean isPythonPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isPythonSource();
  }

  static boolean isTestPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase();
    String file = lower.substring(lower.lastIndexOf('/') + 1);
    return lower.contains("/test/")
        || lower.contains("/tests/")
        || file.startsWith("test_")
        || file.endsWith("_test.py")
        || file.endsWith("_test.pyi");
  }

  static boolean isPublicName(String name) {
    if (name.startsWith("__") && name.endsWith("__")) {
      return true;
    }
    return !name.startsWith("_");
  }

  static String toSnake(String name) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c) && i > 0) {
        out.append('_');
      }
      out.append(Character.toLowerCase(c));
    }
    return out.toString();
  }
}
