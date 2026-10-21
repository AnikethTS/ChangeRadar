package io.github.anikethts.changeradar.analyzer.java;

import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.publicApiSummary;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.simpleName;

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

final class JavaRecommendations {

  private JavaRecommendations() {}

  static List<ImpactFinding> publicApiFindings(List<ChangedSymbol> changedSymbols) {
    List<ImpactFinding> findings = new ArrayList<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if (!symbol.publiclyVisible()) {
        continue;
      }
      if (!"type".equals(symbol.kind())
          && !"method".equals(symbol.kind())
          && !"field".equals(symbol.kind())) {
        continue;
      }
      findings.add(
          new ImpactFinding(
              "PUBLIC_API",
              Confidence.DIRECT,
              publicApiSummary(symbol),
              symbol.id(),
              symbol.location()));
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
      if ("type".equals(symbol.kind())) {
        changedTypeNames.add(symbol.name());
      } else {
        String simple = simpleName(symbol.owner());
        if (!simple.isEmpty()) {
          changedTypeNames.add(simple);
        }
      }
    }

    for (ParsedFile file : parsedHead.values()) {
      if (!file.testFile() && file.decls().stream().noneMatch(Decl::test)) {
        continue;
      }
      for (Decl decl : file.decls()) {
        if (!"type".equals(decl.kind())) {
          continue;
        }
        for (String typeName : changedTypeNames) {
          if (decl.name().equals(typeName + "Test") || decl.name().equals(typeName + "Tests")) {
            tests.putIfAbsent(
                file.path() + "#" + decl.name(),
                new TestRecommendation(
                    decl.name(),
                    file.path(),
                    Confidence.HEURISTIC,
                    "Type name "
                        + decl.name()
                        + " matches naming convention for changed type "
                        + typeName
                        + ". Suggestion only; the test was not executed."));
          }
        }
      }
    }

    for (ImpactFinding finding : findings) {
      ParsedFile file = parsedHead.get(finding.location().path());
      if (file == null || (!file.testFile() && file.decls().stream().noneMatch(Decl::test))) {
        continue;
      }
      String testName =
          file.decls().stream()
              .filter(decl -> "type".equals(decl.kind()))
              .map(Decl::name)
              .findFirst()
              .orElse(file.path());
      tests.putIfAbsent(
          file.path() + "#" + testName,
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
