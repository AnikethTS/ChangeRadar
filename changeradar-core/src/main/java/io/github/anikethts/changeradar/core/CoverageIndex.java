package io.github.anikethts.changeradar.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Suite-wide coverage from JaCoCo XML or LCOV. Hits mean some test run executed the line, not which
 * test.
 */
public final class CoverageIndex {

  private final Map<String, Set<Integer>> hits = new LinkedHashMap<>();
  private int filesLoaded;

  public static CoverageIndex load(List<Path> files, List<Limitation> limitations) {
    CoverageIndex index = new CoverageIndex();
    for (Path file : files) {
      try {
        String sample = Files.readString(file);
        if (looksLikeJacoco(sample)) {
          index.addJacoco(file);
        } else if (looksLikeLcov(sample)) {
          index.addLcov(sample);
        } else {
          limitations.add(
              new Limitation(
                  "COVERAGE_PARSE_FAILED", "Not JaCoCo XML or LCOV: " + file + ". Ignored."));
          continue;
        }
        index.filesLoaded++;
      } catch (Exception e) {
        limitations.add(
            new Limitation(
                "COVERAGE_PARSE_FAILED",
                "Could not read coverage " + file + ": " + e.getMessage()));
      }
    }
    return index;
  }

  public boolean isEmpty() {
    return hits.isEmpty();
  }

  public int filesLoaded() {
    return filesLoaded;
  }

  public boolean lineHit(String path, int line) {
    String unix = path.replace('\\', '/');
    for (Map.Entry<String, Set<Integer>> entry : hits.entrySet()) {
      if (pathMatches(unix, entry.getKey()) && entry.getValue().contains(line)) {
        return true;
      }
    }
    return false;
  }

  public boolean fileHasHits(String path) {
    String unix = path.replace('\\', '/');
    for (Map.Entry<String, Set<Integer>> entry : hits.entrySet()) {
      if (pathMatches(unix, entry.getKey()) && !entry.getValue().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  public Stats stats(List<ChangedFile> changed) {
    int total = 0;
    int covered = 0;
    for (ChangedFile file : changed) {
      List<LineRange> ranges = file.headLineRanges();
      if (ranges.isEmpty()) {
        if (fileHasHits(file.path())) {
          total++;
          covered++;
        } else if (file.changeType() != ChangeType.DELETE) {
          total++;
        }
        continue;
      }
      for (LineRange range : ranges) {
        for (int line = range.startLine(); line <= range.endLine(); line++) {
          total++;
          if (lineHit(file.path(), line)) {
            covered++;
          }
        }
      }
    }
    return new Stats(total, covered);
  }

  public boolean intersectsChanged(String testPath, List<ChangedFile> changed) {
    Set<String> stems = productionStems(testPath);
    for (ChangedFile file : changed) {
      if (file.changeType() == ChangeType.DELETE) {
        continue;
      }
      String unix = file.path().replace('\\', '/');
      String fileName = unix.substring(unix.lastIndexOf('/') + 1);
      int dot = fileName.lastIndexOf('.');
      String stem = dot < 0 ? fileName : fileName.substring(0, dot);
      if (!stems.contains(stem.toLowerCase(Locale.ROOT))) {
        continue;
      }
      List<LineRange> ranges = file.headLineRanges();
      if (ranges.isEmpty()) {
        if (fileHasHits(file.path())) {
          return true;
        }
        continue;
      }
      for (LineRange range : ranges) {
        for (int line = range.startLine(); line <= range.endLine(); line++) {
          if (lineHit(file.path(), line)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  static Set<String> productionStems(String testPath) {
    String unix = testPath.replace('\\', '/');
    String file = unix.substring(unix.lastIndexOf('/') + 1);
    int dot = file.lastIndexOf('.');
    String stem = dot < 0 ? file : file.substring(0, dot);
    String lower = stem.toLowerCase(Locale.ROOT);
    Set<String> stems = new LinkedHashSet<>();
    stems.add(lower);
    if (lower.endsWith("test")) {
      stems.add(lower.substring(0, lower.length() - 4));
    }
    if (lower.endsWith("tests")) {
      stems.add(lower.substring(0, lower.length() - 5));
    }
    if (lower.endsWith("spec")) {
      stems.add(lower.substring(0, lower.length() - 4));
    }
    if (lower.startsWith("test_")) {
      stems.add(lower.substring(5));
    }
    if (lower.endsWith("_test")) {
      stems.add(lower.substring(0, lower.length() - 5));
    }
    if (lower.contains(".test")) {
      stems.add(lower.replace(".test", ""));
    }
    if (lower.contains(".spec")) {
      stems.add(lower.replace(".spec", ""));
    }
    return stems;
  }

  private void addJacoco(Path file) throws Exception {
    Document document = XmlSecure.factory().newDocumentBuilder().parse(file.toFile());
    NodeList packages = document.getElementsByTagName("package");
    for (int p = 0; p < packages.getLength(); p++) {
      Element pkg = (Element) packages.item(p);
      String pkgName = pkg.getAttribute("name").replace('.', '/');
      NodeList sources = pkg.getElementsByTagName("sourcefile");
      for (int s = 0; s < sources.getLength(); s++) {
        Element source = (Element) sources.item(s);
        String name = source.getAttribute("name");
        String key = pkgName.isEmpty() ? name : pkgName + "/" + name;
        Set<Integer> lines =
            hits.computeIfAbsent(key.replace('\\', '/'), ignored -> new LinkedHashSet<>());
        NodeList lineNodes = source.getElementsByTagName("line");
        for (int i = 0; i < lineNodes.getLength(); i++) {
          Element line = (Element) lineNodes.item(i);
          int ci = parseInt(line.getAttribute("ci"));
          int nr = parseInt(line.getAttribute("nr"));
          if (ci > 0 && nr > 0) {
            lines.add(nr);
          }
        }
      }
    }
  }

  private void addLcov(String source) {
    String current = "";
    for (String raw : source.split("\n")) {
      String line = raw.trim();
      if (line.startsWith("SF:")) {
        current = line.substring(3).replace('\\', '/');
      } else if (line.startsWith("DA:") && !current.isEmpty()) {
        String[] parts = line.substring(3).split(",");
        if (parts.length >= 2) {
          int nr = parseInt(parts[0]);
          int hitsCount = parseInt(parts[1]);
          if (nr > 0 && hitsCount > 0) {
            hits.computeIfAbsent(current, ignored -> new LinkedHashSet<>()).add(nr);
          }
        }
      }
    }
  }

  private static boolean looksLikeJacoco(String sample) {
    String head = sample.length() > 400 ? sample.substring(0, 400) : sample;
    return head.contains("<report") || head.contains("jacoco") || head.contains("<package");
  }

  private static boolean looksLikeLcov(String sample) {
    return sample.contains("\nSF:") || sample.startsWith("SF:") || sample.startsWith("TN:");
  }

  static boolean pathMatches(String filePath, String coverageKey) {
    String file = filePath.replace('\\', '/');
    String key = coverageKey.replace('\\', '/');
    if (file.equals(key) || file.endsWith("/" + key) || key.endsWith("/" + file)) {
      return true;
    }
    int slash = key.lastIndexOf('/');
    String name = slash < 0 ? key : key.substring(slash + 1);
    return file.endsWith("/" + name) && (slash < 0 || file.contains("/" + key));
  }

  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public record Stats(int changedLines, int coveredLines) {}
}
