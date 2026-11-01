package io.github.anikethts.changeradar.analyzer.py;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PyRecommendations {

  private PyRecommendations() {}

  static List<ImpactFinding> publicApiFindings(List<ChangedSymbol> changedSymbols) {
    List<ImpactFinding> findings = new ArrayList<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if (!symbol.publiclyVisible()) {
        continue;
      }
      String label =
          symbol.owner().isEmpty() || "type".equals(symbol.kind())
              ? symbol.name()
              : symbol.owner() + "#" + symbol.name();
      String summary =
          symbol.changeType() == ChangeType.DELETE
              ? "Public " + symbol.kind() + " " + label + " was removed"
              : "Public " + symbol.kind() + " " + label + " occupies changed lines";
      findings.add(
          new ImpactFinding(
              "PUBLIC_API", Confidence.DIRECT, summary, symbol.id(), symbol.location()));
    }
    return findings;
  }

  static List<TestRecommendation> recommendTests(
      Map<String, ParsedFile> parsedHead,
      List<ChangedSymbol> changedSymbols,
      List<ImpactFinding> findings) {
    Map<String, TestRecommendation> tests = new LinkedHashMap<>();
    Set<String> names = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      names.add(symbol.name());
      if (!symbol.owner().isEmpty()) {
        names.add(symbol.owner());
      }
    }
    for (ParsedFile file : parsedHead.values()) {
      if (!file.testFile()) {
        continue;
      }
      for (String changed : names) {
        if (file.path().contains(changed) || file.path().contains(PySupport.toSnake(changed))) {
          TestRecommendation.prefer(
              tests,
              file.path(),
              new TestRecommendation(
                  file.path(),
                  file.path(),
                  Confidence.HEURISTIC,
                  "Test path relates to changed symbol "
                      + changed
                      + ". Suggestion only; the test was not executed."));
        }
      }
    }
    for (ImpactFinding finding : findings) {
      ParsedFile file = parsedHead.get(finding.location().path());
      if (file == null || !file.testFile()) {
        continue;
      }
      String testName =
          file.decls().stream()
              .filter(decl -> "function".equals(decl.kind()) && decl.name().startsWith("test_"))
              .map(decl -> decl.name())
              .findFirst()
              .orElse(file.path());
      TestRecommendation.prefer(
          tests,
          file.path(),
          new TestRecommendation(
              testName,
              file.path(),
              finding.confidence() == Confidence.DIRECT ? Confidence.DIRECT : Confidence.HEURISTIC,
              "This test "
                  + (finding.confidence() == Confidence.DIRECT ? "directly" : "may")
                  + " call changed code at "
                  + finding.location().path()
                  + ":"
                  + finding.location().startLine()
                  + ". Suggestion only; the test was not executed."));
    }
    return new ArrayList<>(tests.values());
  }
}
