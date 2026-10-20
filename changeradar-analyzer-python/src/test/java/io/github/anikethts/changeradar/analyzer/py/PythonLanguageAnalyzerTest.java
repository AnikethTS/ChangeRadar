package io.github.anikethts.changeradar.analyzer.py;

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

class PythonLanguageAnalyzerTest {

  private final PythonLanguageAnalyzer analyzer = new PythonLanguageAnalyzer();

  @Test
  void mapsChangedFunctionAndFindsCallerAndTest() {
    String api =
        """
        def n():
            return 1
        """;
    String caller =
        """
        from api import n

        def run():
            return n()
        """;
    String test =
        """
        from api import n

        def test_n():
            n()
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.py", ChangeType.MODIFY, null, List.of(new LineRange(1, 2)))),
            Map.of("api.py", api, "caller.py", caller, "test_api.py", test),
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
                        && finding.location().path().contains("caller.py")));
    assertTrue(
        result.tests().stream()
            .anyMatch(recommendation -> recommendation.path().contains("test_api.py")));
  }

  @Test
  void marksRemovedPublicFunctionAsDelete() {
    String base =
        """
        def gone():
            pass

        def stays():
            pass
        """;
    String head =
        """
        def stays():
            pass
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.py", ChangeType.MODIFY, null, List.of(new LineRange(1, 3)))),
            Map.of("api.py", head),
            Map.of("api.py", base));
    assertTrue(
        result.changedSymbols().stream()
            .anyMatch(
                symbol ->
                    "gone".equals(symbol.name())
                        && symbol.changeType() == ChangeType.DELETE
                        && symbol.publiclyVisible()));
  }

  @Test
  void sameNameWithoutImportIsNotACaller() {
    String api =
        """
        def n():
            return 1
        """;
    String decoy =
        """
        def n():
            return 2

        def run():
            return n()
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.py", ChangeType.MODIFY, null, List.of(new LineRange(1, 2)))),
            Map.of("api.py", api, "decoy.py", decoy),
            Map.of());
    assertTrue(
        result.findings().stream()
            .noneMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("decoy.py")));
  }

  @Test
  void overrideAndFieldAndDecoratorAreReported() {
    String api =
        """
        class Api:
            def n(self):
                return 1

        class Flag:
            pass

        class Demo:
            timeout = 1
        """;
    String impl =
        """
        from api import Api, Flag, Demo

        class Impl(Api):
            def n(self):
                return 2

        class Worker:
            @Flag
            demo: Demo

            def read(self, d):
                return d.timeout
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "api.py",
                    ChangeType.MODIFY,
                    null,
                    List.of(new LineRange(2, 3), new LineRange(5, 6), new LineRange(9, 9)))),
            Map.of("api.py", api, "impl.py", impl),
            Map.of());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "OVERRIDE".equals(finding.type())
                        && finding.location().path().contains("impl.py")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "FIELD_ACCESS".equals(finding.type())
                        && finding.location().path().contains("impl.py")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "ANNOTATION_USE".equals(finding.type())
                        && finding.location().path().contains("impl.py")));
    assertTrue(result.limitations().stream().anyMatch(l -> "PY_STRUCTURAL_EDGES".equals(l.code())));
  }

  @Test
  void importResolvedCallerOfCallerIsIndirect() {
    String api =
        """
        def n():
            return 1
        """;
    String caller =
        """
        from api import n

        def run():
            return n()
        """;
    String main =
        """
        from caller import run

        def main():
            return run()
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.py", ChangeType.MODIFY, null, List.of(new LineRange(1, 2)))),
            Map.of("api.py", api, "caller.py", caller, "main.py", main),
            Map.of());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.confidence() == Confidence.DIRECT
                        && finding.location().path().contains("caller.py")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.confidence() == Confidence.INDIRECT
                        && finding.location().path().contains("main.py")
                        && finding.summary().contains("caller.py#run")));
    assertTrue(result.limitations().stream().anyMatch(l -> "PY_CALL_CHAINS".equals(l.code())));
  }

  @Test
  void heuristicCallersAreNotChained() {
    String api =
        """
        def n():
            return 1
        """;
    String caller =
        """
        from mystery import n

        def run():
            return n()
        """;
    String main =
        """
        from caller import run

        def main():
            return run(None)
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.py", ChangeType.MODIFY, null, List.of(new LineRange(1, 2)))),
            Map.of("api.py", api, "caller.py", caller, "main.py", main),
            Map.of());
    assertEquals(
        Confidence.HEURISTIC,
        result.findings().stream()
            .filter(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("caller.py"))
            .findFirst()
            .orElseThrow()
            .confidence());
    assertTrue(
        result.findings().stream()
            .noneMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("main.py")));
  }

  @Test
  void unclosedStringIsParseFailure() {
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "broken.py", ChangeType.MODIFY, null, List.of(new LineRange(1, 1)))),
            Map.of("broken.py", "def x():\n    s = '''oops\n"),
            Map.of());
    assertTrue(result.limitations().stream().anyMatch(l -> "PY_PARSE_FAILED".equals(l.code())));
  }
}
