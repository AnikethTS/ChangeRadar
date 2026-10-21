package io.github.anikethts.changeradar.analyzer.ts;

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
import java.util.Optional;
import java.util.Set;

final class TsRecommendations {

  private TsRecommendations() {}

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
              ? "Exported " + symbol.kind() + " " + label + " was removed"
              : "Exported " + symbol.kind() + " " + label + " occupies changed lines";
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
    Set<String> changedTypeNames = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("type".equals(symbol.kind()) || !symbol.owner().isEmpty()) {
        changedTypeNames.add("type".equals(symbol.kind()) ? symbol.name() : symbol.owner());
      } else {
        changedTypeNames.add(symbol.name());
      }
    }
    for (ParsedFile file : parsedHead.values()) {
      if (!file.testFile()) {
        continue;
      }
      String testName = file.path();
      Optional<Decl> type =
          file.decls().stream().filter(decl -> "type".equals(decl.kind())).findFirst();
      if (type.isPresent()) {
        testName = type.get().name();
      }
      for (String changed : changedTypeNames) {
        if (testName.contains(changed) || file.path().contains(changed)) {
          tests.putIfAbsent(
              file.path(),
              new TestRecommendation(
                  testName,
                  file.path(),
                  Confidence.HEURISTIC,
                  "Test path or name relates to changed symbol "
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
              .filter(decl -> "type".equals(decl.kind()))
              .map(decl -> decl.name())
              .findFirst()
              .orElse(file.path());
      tests.putIfAbsent(
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
