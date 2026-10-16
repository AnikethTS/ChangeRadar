package io.github.anikethts.changeradar.analyzer.php;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhpLanguageAnalyzerTest {

  private final PhpLanguageAnalyzer analyzer = new PhpLanguageAnalyzer();

  @Test
  void mapsChangedFunctionAndFindsCallerAndTest() {
    String api =
        """
        <?php
        function n() {
          return 1;
        }
        """;
    String caller =
        """
        <?php
        require 'api.php';
        function run() {
          return n();
        }
        """;
    String test =
        """
        <?php
        function test_n() {
          n();
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.php", ChangeType.MODIFY, null, List.of(new LineRange(2, 4)))),
            Map.of("api.php", api, "caller.php", caller, "ApiTest.php", test),
            Map.of());
    assertTrue(
        result.changedSymbols().stream()
            .anyMatch(symbol -> "n".equals(symbol.name()) && "function".equals(symbol.kind())));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    finding.type().equals("CALLER")
                        && finding.location().path().contains("caller.php")));
    assertTrue(
        result.tests().stream()
            .anyMatch(recommendation -> recommendation.path().contains("ApiTest.php")));
  }

  @Test
  void marksRemovedPublicFunctionAsDelete() {
    String base =
        """
        <?php
        function gone() {}
        function stays() {}
        """;
    String head =
        """
        <?php
        function stays() {}
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.php", ChangeType.MODIFY, null, List.of(new LineRange(2, 3)))),
            Map.of("api.php", head),
            Map.of("api.php", base));
    assertTrue(
        result.changedSymbols().stream()
            .anyMatch(
                symbol ->
                    "gone".equals(symbol.name())
                        && symbol.changeType() == ChangeType.DELETE
                        && symbol.publiclyVisible()));
  }

  @Test
  void unmatchedBracesAreParseFailures() {
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "broken.php", ChangeType.MODIFY, null, List.of(new LineRange(1, 2)))),
            Map.of("broken.php", "<?php\nfunction x() {\n"),
            Map.of());
    assertTrue(result.limitations().stream().anyMatch(l -> "PHP_PARSE_FAILED".equals(l.code())));
  }
}
