package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ChangeRadarAppTest {

  @TempDir Path tmp;

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
            "sarif");
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

  @Test
  void invalidRefIsFailure() throws Exception {
    seedRepo();
    CommandLine cmd = command();
    int code =
        cmd.execute("analyze", "--base", "not-a-ref", "--head", "HEAD", "--repo", tmp.toString());
    assertEquals(2, code);
  }

  private static CommandLine command() {
    CommandLine cmd = new CommandLine(new ChangeRadarApp());
    cmd.setExecutionExceptionHandler(new ChangeRadarApp.FailureExceptionHandler());
    StringWriter err = new StringWriter();
    cmd.setErr(new PrintWriter(err, true));
    cmd.setOut(new PrintWriter(new StringWriter(), true));
    return cmd;
  }

  private void seedRepo() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Demo.java");
      Files.createDirectories(src.getParent());
      Files.writeString(src, "class Demo { void n() {} }\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "class Demo { void n() { int x = 1; } }\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  private void seedPublicRemoval() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Api.java");
      Files.createDirectories(src.getParent());
      Files.writeString(
          src,
          """
          public class Api {
            public void gone() {}
            public void stays() {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(
          src,
          """
          public class Api {
            public void stays() {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  private void seedTypeScriptRepo() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/api.ts");
      Files.createDirectories(src.getParent());
      Files.writeString(src, "export function n(): number { return 1; }\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "export function n(): number { return 2; }\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  private void seedTypeScriptRemoval() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/api.ts");
      Files.createDirectories(src.getParent());
      Files.writeString(src, "export function gone() {}\nexport function stays() {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "export function stays() {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  private void seedUnparseableJava() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Broken.java");
      Files.createDirectories(src.getParent());
      Files.writeString(src, "public class Broken {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "public class Broken { this is not java\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  private static void commit(Git git, String message) throws Exception {
    git.commit()
        .setAuthor("ChangeRadar Test", "test@changeradar.example")
        .setCommitter("ChangeRadar Test", "test@changeradar.example")
        .setSign(false)
        .setMessage(message)
        .call();
  }
}
