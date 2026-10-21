package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ChangeRadarPolicyCliTest extends CliGitHarness {

  @Test
  void policyFileAndBaselineSuppressExistingViolation() throws Exception {
    seedPublicRemoval();
    Path policy = tmp.resolve("changeradar.yml");
    Path baseline = tmp.resolve("baseline.txt");
    Files.writeString(
        policy,
        """
        policies:
          - breaking-public-java-api
        """);
    int first =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--policy-file",
                policy.toString(),
                "--write-baseline",
                baseline.toString());
    assertEquals(0, first);
    assertTrue(Files.readString(baseline).contains("breaking-public-java-api"));
    int second =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--policy-file",
                policy.toString(),
                "--baseline",
                baseline.toString());
    assertEquals(0, second);
  }

  @Test
  void suppressFileOmitsFindingIds() throws Exception {
    seedCallerRepo();
    Path out = tmp.resolve("unfiltered");
    int first =
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
    assertEquals(0, first);
    String json = Files.readString(out.resolve("report.json"));
    assertTrue(json.contains("Caller.java"));
    Path suppress = tmp.resolve(".changeradar/suppress.txt");
    Path policy = tmp.resolve("changeradar.yml");
    Files.writeString(
        policy,
        """
        policies: []
        suppressions: .changeradar/suppress.txt
        """);
    int seed =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--write-suppressions",
                suppress.toString());
    assertEquals(0, seed);
    assertTrue(Files.readString(suppress).contains("CALLER|"));
    Path filtered = tmp.resolve("filtered");
    int second =
        command()
            .execute(
                "analyze",
                "--base",
                "HEAD~1",
                "--head",
                "HEAD",
                "--repo",
                tmp.toString(),
                "--policy-file",
                policy.toString(),
                "--format",
                "json",
                "--output",
                filtered.toString());
    assertEquals(0, second);
    String filteredJson = Files.readString(filtered.resolve("report.json"));
    assertTrue(filteredJson.contains("\"findings\" : [ ]"));
    assertTrue(filteredJson.contains("FINDING_SUPPRESSIONS"));
  }

  @Test
  void removedPublicMethodBlocksWhenPolicyEnabled() throws Exception {
    seedPublicRemoval();
    CommandLine cmd = command();
    int withoutPolicy =
        cmd.execute(
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
            tmp.resolve("off").toString());
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
                "--format",
                "json",
                "--output",
                tmp.resolve("on").toString(),
                "--policy",
                "breaking-public-java-api");
    assertEquals(1, withPolicy);
    String json = Files.readString(tmp.resolve("on/report.json"));
    assertTrue(json.contains("\"policyBlocking\" : true"));
    assertTrue(json.contains("breaking-public-java-api"));
  }

  @Test
  void movedPublicTypeDoesNotBlockJavaApiPolicy() throws Exception {
    seedPublicMove();
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
                "--policy",
                "breaking-public-java-api");
    assertEquals(0, code);
  }

  @Test
  void publicReturnTypeChangeBlocksJavaApiPolicy() throws Exception {
    seedPublicReturnTypeChange();
    assertEquals(
        1,
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
                "breaking-public-java-api"));
  }

  @Test
  void publicThrowsChangeBlocksJavaApiPolicy() throws Exception {
    seedPublicThrowsChange();
    assertEquals(
        1,
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
                "breaking-public-java-api"));
  }

  @Test
  void publicFieldTypeChangeBlocksJavaApiPolicy() throws Exception {
    seedPublicFieldTypeChange();
    assertEquals(
        1,
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
                "breaking-public-java-api"));
  }

  @Test
  void publicOverloadRemovalBlocksJavaApiPolicy() throws Exception {
    seedPublicOverloadRemoval();
    assertEquals(
        1,
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
                "breaking-public-java-api"));
  }

  @Test
  void publicSignatureChangeBlocksJavaApiPolicy() throws Exception {
    seedPublicSignatureChange();
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
                "--policy",
                "breaking-public-java-api");
    assertEquals(1, code);
  }

  @Test
  void unreadableChangedJavaBlocksWhenAnalysisUnavailableEnabled() throws Exception {
    seedUnparseableJava();
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
                "--policy",
                "analysis-unavailable");
    assertEquals(1, code);
  }
}
