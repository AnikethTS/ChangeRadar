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
    assertTrue(json.contains("JAVA_SYMBOLS_NOT_IMPLEMENTED"));
    assertTrue(json.contains("src/Demo.java"));
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
      Files.writeString(src, "class Demo {}\n");
      git.add().addFilepattern(".").call();
      git.commit()
          .setAuthor("ChangeRadar Test", "test@changeradar.example")
          .setCommitter("ChangeRadar Test", "test@changeradar.example")
          .setSign(false)
          .setMessage("base")
          .call();
      Files.writeString(src, "class Demo { int n; }\n");
      git.add().addFilepattern(".").call();
      git.commit()
          .setAuthor("ChangeRadar Test", "test@changeradar.example")
          .setCommitter("ChangeRadar Test", "test@changeradar.example")
          .setSign(false)
          .setMessage("head")
          .call();
    }
  }
}
