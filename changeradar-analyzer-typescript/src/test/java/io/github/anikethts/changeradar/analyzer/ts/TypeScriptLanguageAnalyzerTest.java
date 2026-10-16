package io.github.anikethts.changeradar.analyzer.ts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypeScriptLanguageAnalyzerTest {

  private final TypeScriptLanguageAnalyzer analyzer = new TypeScriptLanguageAnalyzer();

  @Test
  void mapsChangedFunctionAndFindsCallerAndTest() {
    String api =
        """
        export function n(): number {
          return 1;
        }
        """;
    String caller =
        """
        import { n } from "./api";
        export function run() {
          return n();
        }
        """;
    String test =
        """
        import { n } from "./api";
        test("covers n", () => {
          n();
        });
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/api.ts", ChangeType.MODIFY, null, List.of(new LineRange(1, 3)))),
            Map.of(
                "src/api.ts", api,
                "src/caller.ts", caller,
                "src/api.test.ts", test),
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
                        && finding.location().path().contains("caller.ts")));
    assertTrue(
        result.tests().stream()
            .anyMatch(recommendation -> recommendation.path().contains("api.test.ts")));
  }

  @Test
  void marksRemovedExportAsDelete() {
    String base =
        """
        export function gone() {}
        export function stays() {}
        """;
    String head =
        """
        export function stays() {}
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/api.ts", ChangeType.MODIFY, null, List.of(new LineRange(1, 2)))),
            Map.of("src/api.ts", head),
            Map.of("src/api.ts", base));
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
        export function n(): number {
          return 1;
        }
        """;
    String decoy =
        """
        export function n(): number {
          return 2;
        }
        export function run() {
          return n();
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/api.ts", ChangeType.MODIFY, null, List.of(new LineRange(1, 3)))),
            Map.of("src/api.ts", api, "src/decoy.ts", decoy),
            Map.of());
    assertTrue(
        result.findings().stream()
            .noneMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("decoy.ts")));
  }

  @Test
  void unmatchedBracesAreParseFailures() {
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/broken.ts", ChangeType.MODIFY, null, List.of(new LineRange(1, 1)))),
            Map.of("src/broken.ts", "export function x() {"),
            Map.of());
    assertTrue(result.limitations().stream().anyMatch(l -> "TS_PARSE_FAILED".equals(l.code())));
  }
}
