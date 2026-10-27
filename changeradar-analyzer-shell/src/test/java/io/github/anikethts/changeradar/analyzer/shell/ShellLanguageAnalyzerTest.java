package io.github.anikethts.changeradar.analyzer.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.FormatOnly;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShellLanguageAnalyzerTest {

  private final ShellLanguageAnalyzer analyzer = new ShellLanguageAnalyzer();

  @Test
  void changedFunctionIsCalledAndSourced() {
    String base = "build() {\n  echo old\n}\n";
    String head = "build() {\n  echo new\n}\n";
    String caller = "source ./lib.sh\nbuild\n";
    var result =
        analyzer.analyze(
            List.of(
                new ChangedFile("lib.sh", ChangeType.MODIFY, null, List.of(new LineRange(2, 2)))),
            Map.of("lib.sh", head, "run.sh", caller),
            Map.of("lib.sh", base));
    assertTrue(
        result.changedSymbols().stream().anyMatch(symbol -> symbol.kind().equals("function")));
    assertTrue(result.changedSymbols().stream().anyMatch(symbol -> symbol.name().equals("build")));
    assertEquals(2, result.findings().size());
    assertTrue(
        result.findings().stream()
            .anyMatch(finding -> finding.summary().contains("calls function build")));
    assertTrue(
        result.findings().stream()
            .anyMatch(finding -> finding.summary().contains("sources script lib.sh")));
    assertEquals(Confidence.DIRECT, result.findings().get(0).confidence());
    assertTrue(
        result.limitations().stream().anyMatch(item -> item.code().equals("SHELL_REFERENCES")));
  }

  @Test
  void duplicateFunctionNameIsHeuristic() {
    String head = "build() {\n  echo 1\n}\n";
    String other = "build() {\n  echo 2\n}\n";
    String caller = "build\n";
    var result =
        analyzer.analyze(
            List.of(new ChangedFile("a.sh", ChangeType.MODIFY, null, List.of(new LineRange(2, 2)))),
            Map.of("a.sh", head, "b.sh", other, "run.sh", caller),
            Map.of("a.sh", "build() {\n  echo 0\n}\n"));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    finding.summary().contains("calls function build")
                        && finding.confidence() == Confidence.HEURISTIC));
  }

  @Test
  void quotedNameIsNotACall() {
    String head = "build() {\n  echo new\n}\n";
    String caller = "echo \"build\"\n";
    var result =
        analyzer.analyze(
            List.of(
                new ChangedFile("lib.sh", ChangeType.MODIFY, null, List.of(new LineRange(2, 2)))),
            Map.of("lib.sh", head, "run.sh", caller),
            Map.of("lib.sh", "build() {\n  echo old\n}\n"));
    assertTrue(
        result.findings().stream()
            .noneMatch(finding -> finding.summary().contains("calls function")));
  }

  @Test
  void commentOnlyScriptIsFormatOnly() {
    String base = "build() {\n  echo old\n}\n";
    String head = base + "# note\n";
    var result =
        analyzer.analyze(
            List.of(new ChangedFile("lib.sh", ChangeType.MODIFY, null)),
            Map.of("lib.sh", head),
            Map.of("lib.sh", base));
    assertTrue(result.changedSymbols().isEmpty());
    assertTrue(result.limitations().stream().anyMatch(item -> item.code().equals(FormatOnly.CODE)));
  }
}
