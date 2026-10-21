package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChangeRadarEvidenceCliTest extends CliGitHarness {

  @Test
  void coverageAndJunitAnnotateReportWithoutRunningTests() throws Exception {
    seedCallerRepo();
    Path jacoco = tmp.resolve("jacoco.xml");
    Files.writeString(
        jacoco,
        """
        <report>
          <package name="demo">
            <sourcefile name="Demo.java">
              <line nr="6" mi="0" ci="1"/>
            </sourcefile>
          </package>
        </report>
        """);
    Path junit = tmp.resolve("TEST-DemoTest.xml");
    Files.writeString(
        junit,
        """
        <testsuite>
          <testcase classname="demo.DemoTest" name="coversN">
            <failure message="boom"/>
          </testcase>
        </testsuite>
        """);
    Path out = tmp.resolve("reports");
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
                "json",
                "--output",
                out.toString(),
                "--coverage",
                jacoco.toString(),
                "--junit",
                junit.toString());
    assertEquals(0, code);
    String json = Files.readString(out.resolve("report.json"));
    assertTrue(json.contains("\"execution\" : \"FAILED\""));
    assertTrue(json.contains("COVERAGE_INGEST"));
    assertTrue(json.contains("JUNIT_INGEST"));
    assertTrue(json.contains("coversChangedLines"));
  }

  @Test
  void failedRelatedTestsPolicyBlocksWhenJunitFailed() throws Exception {
    seedCallerRepo();
    Path junit = tmp.resolve("TEST-DemoTest.xml");
    Files.writeString(
        junit,
        """
        <testsuite>
          <testcase classname="demo.DemoTest" name="coversN">
            <failure message="boom"/>
          </testcase>
        </testsuite>
        """);
    int off =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--junit",
                junit.toString());
    assertEquals(0, off);
    int on =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--junit",
                junit.toString(),
                "--policy",
                "failed-related-tests");
    assertEquals(1, on);
  }

  @Test
  void missingCoveragePathIsExitTwo() throws Exception {
    seedCallerRepo();
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
                "--coverage",
                tmp.resolve("missing.xml").toString());
    assertEquals(2, code);
  }
}
