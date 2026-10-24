package io.github.anikethts.changeradar.analyzer.sql;

import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SqlRecommendations {

  private SqlRecommendations() {}

  static List<TestRecommendation> recommendTests(
      Map<String, ParsedFile> parsedHead, List<ImpactFinding> findings) {
    Map<String, TestRecommendation> tests = new LinkedHashMap<>();
    for (ImpactFinding finding : findings) {
      ParsedFile file = parsedHead.get(finding.location().path());
      if (file == null || !file.testFile()) {
        continue;
      }
      tests.putIfAbsent(
          file.path(),
          new TestRecommendation(
              file.path(),
              file.path(),
              finding.confidence() == Confidence.DIRECT ? Confidence.DIRECT : Confidence.HEURISTIC,
              "SQL test file references a changed object. Suggestion only; the script was not"
                  + " executed."));
    }
    return new ArrayList<>(tests.values());
  }
}
