package io.github.anikethts.changeradar.analyzer.php;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.Confidence;
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
                        && finding.confidence() == Confidence.DIRECT
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
  void sameNameWithoutRequireIsNotACaller() {
    String api =
        """
        <?php
        function n() {
          return 1;
        }
        """;
    String decoy =
        """
        <?php
        function n() {
          return 2;
        }
        function run() {
          return n();
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.php", ChangeType.MODIFY, null, List.of(new LineRange(2, 4)))),
            Map.of("api.php", api, "decoy.php", decoy),
            Map.of());
    assertTrue(
        result.findings().stream()
            .noneMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("decoy.php")));
  }

  @Test
  void overrideAndFieldAndAttributeAreReported() {
    String api =
        """
        <?php
        class Api {
          function n() {}
        }
        class Flag {}
        class Demo {
          public $timeout;
        }
        """;
    String impl =
        """
        <?php
        require 'api.php';
        class Impl extends Api {
          function n() {}
        }
        class Worker {
          #[Flag]
          public Demo $demo;
          function read($d) {
            return $d->timeout;
          }
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "api.php",
                    ChangeType.MODIFY,
                    null,
                    List.of(new LineRange(3, 3), new LineRange(5, 5), new LineRange(7, 7)))),
            Map.of("api.php", api, "impl.php", impl),
            Map.of());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "OVERRIDE".equals(finding.type())
                        && finding.location().path().contains("impl.php")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "FIELD_ACCESS".equals(finding.type())
                        && finding.location().path().contains("impl.php")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "ANNOTATION_USE".equals(finding.type())
                        && finding.location().path().contains("impl.php")));
    assertTrue(
        result.limitations().stream().anyMatch(l -> "PHP_STRUCTURAL_EDGES".equals(l.code())));
  }

  @Test
  void requireResolvedCallerOfCallerIsIndirect() {
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
    String main =
        """
        <?php
        require 'caller.php';
        function main() {
          return run();
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.php", ChangeType.MODIFY, null, List.of(new LineRange(2, 4)))),
            Map.of("api.php", api, "caller.php", caller, "main.php", main),
            Map.of());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.confidence() == Confidence.DIRECT
                        && finding.location().path().contains("caller.php")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.confidence() == Confidence.INDIRECT
                        && finding.location().path().contains("main.php")
                        && finding.summary().contains("caller.php#run")));
    assertTrue(result.limitations().stream().anyMatch(l -> "PHP_CALL_CHAINS".equals(l.code())));
  }

  @Test
  void heuristicCallersAreNotChained() {
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
        use mystery\\n;
        function run() {
          return n();
        }
        """;
    String main =
        """
        <?php
        require 'caller.php';
        function main() {
          return run();
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.php", ChangeType.MODIFY, null, List.of(new LineRange(2, 4)))),
            Map.of("api.php", api, "caller.php", caller, "main.php", main),
            Map.of());
    assertEquals(
        Confidence.HEURISTIC,
        result.findings().stream()
            .filter(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("caller.php"))
            .findFirst()
            .orElseThrow()
            .confidence());
    assertTrue(
        result.findings().stream()
            .noneMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("main.php")));
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
