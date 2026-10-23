package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChangeRadarLanguageCliTest extends CliGitHarness {

  @Test
  void analyzeKotlinDiffWritesSymbols() throws Exception {
    seedKotlinRepo();
    Path out = tmp.resolve("kt-reports");
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
                out.toString());
    assertEquals(0, code);
    String json = Files.readString(out.resolve("report.json"));
    assertTrue(json.contains("api.kt"));
    assertTrue(json.contains("\"n\""));
  }

  @Test
  void removedKotlinFunctionBlocksWhenKotlinPolicyEnabled() throws Exception {
    seedKotlinRemoval();
    int withoutPolicy =
        command()
            .execute("analyze", "--base", "HEAD~1", "--head", "HEAD", "--repo", tmp.toString());
    assertEquals(0, withoutPolicy);
    int withPolicy =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--policy",
                "breaking-public-kotlin");
    assertEquals(1, withPolicy);
  }

  @Test
  void typeScriptFormatOnlyDoesNotOccupySymbols() throws Exception {
    seedTypeScriptFormatOnly();
    Path out = tmp.resolve("ts-fmt");
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
                out.toString());
    assertEquals(0, code);
    String json = Files.readString(out.resolve("report.json"));
    assertTrue(json.contains("FORMAT_ONLY_CHANGES"));
  }

  @Test
  void analyzeTypeScriptDiffWritesSymbols() throws Exception {
    seedTypeScriptRepo();
    Path out = tmp.resolve("ts-reports");
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
                out.toString());
    assertEquals(0, code);
    String json = Files.readString(out.resolve("report.json"));
    assertTrue(json.contains("src/api.ts"));
    assertTrue(json.contains("\"name\" : \"n\"") || json.contains("\"n\""));
  }

  @Test
  void removedExportBlocksWhenExportPolicyEnabled() throws Exception {
    seedTypeScriptRemoval();
    int withoutPolicy =
        command()
            .execute("analyze", "--base", "HEAD~1", "--head", "HEAD", "--repo", tmp.toString());
    assertEquals(0, withoutPolicy);
    int withPolicy =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--policy",
                "breaking-public-export");
    assertEquals(1, withPolicy);
  }

  @Test
  void analyzePythonDiffWritesSymbols() throws Exception {
    seedPythonRepo();
    Path out = tmp.resolve("py-reports");
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
                out.toString());
    assertEquals(0, code);
    String json = Files.readString(out.resolve("report.json"));
    assertTrue(json.contains("api.py"));
    assertTrue(json.contains("\"n\""));
  }

  @Test
  void removedPythonFunctionBlocksWhenPythonPolicyEnabled() throws Exception {
    seedPythonRemoval();
    int withoutPolicy =
        command()
            .execute("analyze", "--base", "HEAD~1", "--head", "HEAD", "--repo", tmp.toString());
    assertEquals(0, withoutPolicy);
    int withPolicy =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--policy",
                "breaking-public-python");
    assertEquals(1, withPolicy);
  }

  @Test
  void analyzePhpDiffWritesSymbols() throws Exception {
    seedPhpRepo();
    Path out = tmp.resolve("php-reports");
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
                out.toString());
    assertEquals(0, code);
    String json = Files.readString(out.resolve("report.json"));
    assertTrue(json.contains("api.php"));
    assertTrue(json.contains("\"n\""));
  }

  @Test
  void removedPhpFunctionBlocksWhenPhpPolicyEnabled() throws Exception {
    seedPhpRemoval();
    int withoutPolicy =
        command()
            .execute("analyze", "--base", "HEAD~1", "--head", "HEAD", "--repo", tmp.toString());
    assertEquals(0, withoutPolicy);
    int withPolicy =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--policy",
                "breaking-public-php");
    assertEquals(1, withPolicy);
  }
}
