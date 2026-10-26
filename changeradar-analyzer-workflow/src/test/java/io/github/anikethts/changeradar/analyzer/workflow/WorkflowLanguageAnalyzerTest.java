package io.github.anikethts.changeradar.analyzer.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.FormatOnly;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowLanguageAnalyzerTest {

  private final WorkflowLanguageAnalyzer analyzer = new WorkflowLanguageAnalyzer();

  @Test
  void changedJobIsNeededByLaterJob() {
    String base =
        """
        name: CI
        on: push
        jobs:
          build:
            runs-on: ubuntu-latest
          deploy:
            needs: build
            runs-on: ubuntu-latest
        """;
    String head = base.replace("ubuntu-latest", "ubuntu-24.04");
    var result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    ".github/workflows/ci.yml",
                    ChangeType.MODIFY,
                    null,
                    List.of(new LineRange(5, 5)))),
            Map.of(".github/workflows/ci.yml", head),
            Map.of(".github/workflows/ci.yml", base));
    assertEquals(1, result.changedSymbols().size());
    assertEquals("job", result.changedSymbols().get(0).kind());
    assertEquals("build", result.changedSymbols().get(0).name());
    assertEquals(1, result.findings().size());
    ImpactFinding finding = result.findings().get(0);
    assertEquals(Confidence.DIRECT, finding.confidence());
    assertTrue(finding.summary().contains("deploy"));
    assertTrue(finding.summary().contains("needs job build"));
  }

  @Test
  void reusableWorkflowIsUsedByLocalPath() {
    String called =
        """
        on:
          workflow_call:
        jobs:
          package:
            runs-on: ubuntu-latest
        """;
    String caller =
        """
        on: push
        jobs:
          call:
            uses: ./.github/workflows/called.yml
        """;
    var result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    ".github/workflows/called.yml",
                    ChangeType.MODIFY,
                    null,
                    List.of(new LineRange(5, 5)))),
            Map.of(".github/workflows/called.yml", called, ".github/workflows/caller.yml", caller),
            Map.of(
                ".github/workflows/called.yml", called.replace("ubuntu-latest", "ubuntu-22.04")));
    assertTrue(
        result.changedSymbols().stream().anyMatch(symbol -> symbol.kind().equals("workflow")));
    assertTrue(result.changedSymbols().stream().anyMatch(symbol -> symbol.kind().equals("job")));
    assertEquals(1, result.findings().size());
    assertTrue(result.findings().get(0).summary().contains("uses workflow called.yml"));
    assertTrue(
        result.limitations().stream().anyMatch(item -> item.code().equals("WORKFLOW_REFERENCES")));
  }

  @Test
  void localActionCallerIgnoresMarketplaceUses() {
    String action =
        """
        name: Hello
        description: greet
        runs:
          using: composite
          steps:
            - run: echo hi
        """;
    String workflow =
        """
        on: push
        jobs:
          build:
            steps:
              - uses: actions/checkout@v4
              - uses: ./.github/actions/hello
        """;
    var result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    ".github/actions/hello/action.yml", ChangeType.MODIFY, null, List.of())),
            Map.of(
                ".github/actions/hello/action.yml", action, ".github/workflows/ci.yml", workflow),
            Map.of(".github/actions/hello/action.yml", action.replace("greet", "wave")));
    assertEquals("action", result.changedSymbols().get(0).kind());
    assertEquals(1, result.findings().size());
    assertTrue(result.findings().get(0).summary().contains("uses action hello"));
  }

  @Test
  void commentOnlyWorkflowIsFormatOnly() {
    String base =
        """
        on: push
        jobs:
          build:
            runs-on: ubuntu-latest
        """;
    String head = base + "# note\n";
    var result =
        analyzer.analyze(
            List.of(new ChangedFile(".github/workflows/ci.yml", ChangeType.MODIFY, null)),
            Map.of(".github/workflows/ci.yml", head),
            Map.of(".github/workflows/ci.yml", base));
    assertTrue(result.changedSymbols().isEmpty());
    assertTrue(result.limitations().stream().anyMatch(item -> item.code().equals(FormatOnly.CODE)));
  }
}
