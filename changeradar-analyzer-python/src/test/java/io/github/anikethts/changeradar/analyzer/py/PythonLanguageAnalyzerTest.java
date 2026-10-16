package io.github.anikethts.changeradar.analyzer.py;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
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
