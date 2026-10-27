package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ChangeRadarAppTest extends CliGitHarness {

  @Test
  void policiesCommandListsKnownRules() {
    CommandLine cmd = command();
    int code = cmd.execute("policies");
    assertEquals(0, code);
  }

  @Test
  void missingArgsIsFailure() {
    CommandLine cmd = command();
    int code = cmd.execute("analyze");
    assertEquals(2, code);
  }

  @Test
  void unknownFormatIsFailure() throws Exception {
    seedRepo();
    CommandLine cmd = command();
    int code =
        cmd.execute(
            "analyze",
            "--base",
            "HEAD",
            "--head",
            "HEAD",
            "--repo",
            tmp.toString(),
            "--format",
            "xml");
    assertEquals(2, code);
  }

  @Test
  void analyzeWritesMarkdownAndJson() throws Exception {
    seedRepo();
    Path out = tmp.resolve("reports");
    CommandLine cmd = command();
    int code =
        cmd.execute(
            "analyze",
            "--base",
            "HEAD~1",
            "--head",
            "HEAD",
            "--repo",
            tmp.toString(),
            "--format",
            "markdown,json",
            "--output",
            out.toString());
    assertEquals(0, code);
    String markdown = Files.readString(out.resolve("report.md"));
    String json = Files.readString(out.resolve("report.json"));
    assertTrue(markdown.contains("src/Demo.java"));
    assertTrue(json.contains("\"schemaVersion\""));
    assertTrue(json.contains("changedSymbols"));
    assertTrue(json.contains("src/Demo.java"));
    assertTrue(markdown.contains("Changed symbols"));
  }

  @Test
  void analyzeWritesHtml() throws Exception {
    seedRepo();
    Path out = tmp.resolve("html-reports");
    int code =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--format",
                "html",
                "--output",
                out.toString());
    assertEquals(0, code);
    String page = Files.readString(out.resolve("report.html"));
    assertTrue(page.contains("ChangeRadar review"));
    assertTrue(page.contains("src/Demo.java"));
  }

  @Test
  void reviewWithoutReportIsFailure() throws Exception {
    seedRepo();
    Path out = tmp.resolve("missing-report");
    Files.createDirectories(out);
    int code = command().execute("review", "--repo", tmp.toString(), "--output", out.toString());
    assertEquals(2, code);
  }

  @Test
  void analyzeWritesCompactAndSarif() throws Exception {
    seedRepo();
    Path out = tmp.resolve("reports2");
    int code =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--format",
                "compact,sarif,github",
                "--output",
                out.toString());
    assertEquals(0, code);
    String compact = Files.readString(out.resolve("report.compact.md"));
    String sarif = Files.readString(out.resolve("report.sarif"));
    String github = Files.readString(out.resolve("report.github.json"));
    assertTrue(compact.contains("ChangeRadar"));
    assertTrue(compact.contains("<!-- changeradar-version:"));
    assertTrue(compact.contains("No direct findings.") || compact.contains("Direct findings"));
    assertTrue(sarif.contains("\"version\""));
    assertTrue(sarif.contains("2.1.0"));
    assertTrue(!sarif.contains("\"warning\""));
    assertTrue(github.contains("\"annotations\""));
  }

  @Test
  void unknownPolicyIsFailure() throws Exception {
    seedRepo();
    CommandLine cmd = command();
    int code =
        cmd.execute(
            "analyze",
            "--base",
            "HEAD",
            "--head",
            "HEAD",
            "--repo",
            tmp.toString(),
            "--policy",
            "not-a-policy");
    assertEquals(2, code);
  }

  @Test
  void invalidRefIsFailure() throws Exception {
    seedRepo();
    CommandLine cmd = command();
    int code =
        cmd.execute("analyze", "--base", "not-a-ref", "--head", "HEAD", "--repo", tmp.toString());
    assertEquals(2, code);
  }
}
