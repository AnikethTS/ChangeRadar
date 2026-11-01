package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;

class RunCommandTest extends CliGitHarness {

  @Test
  void runsTheCommandThenReadsJunit() throws Exception {
    seedRepo();
    Path out = tmp.resolve("reports");
    int code =
        command()
            .execute(
                "run",
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
                "--timeout",
                "30",
                "--junit",
                "junit.xml",
                "--command",
                successCommand());

    assertEquals(0, code);
    assertTrue(Files.readString(tmp.resolve("ran-marker.txt")).startsWith("ran"));
    String json = Files.readString(out.resolve("report.json"));
    assertTrue(json.contains("EXECUTION"));
    assertTrue(json.contains("process exit 0"));
    assertTrue(json.contains("JUNIT_INGEST"));
    assertTrue(json.contains("not proof from the diff"));
    assertTrue(Files.isRegularFile(out.resolve("run.stdout.txt")));
    String analyze = Files.readString(out.resolve("report.json"));
    assertFalse(analyze.contains("Stopped the command"));
  }

  @Test
  void commandFailureIsExitOneAndAnalyzeDoesNotRunIt() throws Exception {
    seedRepo();
    Path out = tmp.resolve("failed");
    int code =
        command()
            .execute(
                "run",
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
                "--command",
                "exit 3");
    assertEquals(1, code);
    assertTrue(Files.readString(out.resolve("report.json")).contains("process exit 3"));

    Path analyzed = tmp.resolve("analyzed");
    int analyze =
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
                analyzed.toString());
    assertEquals(0, analyze);
    assertFalse(Files.readString(analyzed.resolve("report.json")).contains("\"EXECUTION\""));
  }

  @Test
  void timeoutIsAToolFailure() throws Exception {
    seedRepo();
    Path out = tmp.resolve("timed-out");
    int code =
        command()
            .execute(
                "run",
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
                "--timeout",
                "1",
                "--command",
                sleepCommand());
    assertEquals(2, code);
    assertTrue(
        Files.readString(out.resolve("report.json")).contains("Stopped the command after 1s"));
  }

  @Test
  void runnerAppendsOnlyTheDirectTest() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path demo = tmp.resolve("src/main/java/demo/Demo.java");
      Path test = tmp.resolve("src/main/java/demo/DemoTest.java");
      Files.createDirectories(demo.getParent());
      Files.writeString(
          demo,
          """
          package demo;
          public class Demo {
            public void n() {}
          }
          """);
      Files.writeString(
          test,
          """
          package demo;
          public class DemoTest {
            void checks() {
              new Demo().n();
            }
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(
          demo,
          """
          package demo;
          public class Demo {
            public void n() {
              int x = 1;
            }
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
    Path out = tmp.resolve("selected");
    int code =
        command()
            .execute(
                "run",
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
                "--timeout",
                "30",
                "--runner",
                "gradle",
                "--command",
                "echo ran> ran-marker.txt");
    assertEquals(0, code);
    String json = Files.readString(out.resolve("report.json"));
    assertTrue(json.contains("--tests DemoTest"));
    assertTrue(json.contains("SELECTED_TESTS"));
    assertTrue(Files.exists(tmp.resolve("ran-marker.txt")));
  }

  @Test
  void runnerDoesNotStartWhenNoDirectTestIsSelected() throws Exception {
    seedRepo();
    Path out = tmp.resolve("none");
    int code =
        command()
            .execute(
                "run",
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
                "--timeout",
                "30",
                "--runner",
                "gradle",
                "--command",
                "echo ran> ran-marker.txt");
    assertEquals(0, code);
    assertTrue(Files.readString(out.resolve("report.json")).contains("command was not run"));
    assertFalse(Files.exists(tmp.resolve("ran-marker.txt")));
  }

  private static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  /** A command both {@code sh -c} and {@code cmd /c} can run. */
  private static String successCommand() {
    if (windows()) {
      return "(echo ran)> ran-marker.txt & (echo ^<testsuite^>^<testcase"
          + " name='ok'/^>^</testsuite^>)> junit.xml";
    }
    return "printf ran > ran-marker.txt && printf '<testsuite><testcase"
        + " name=\"ok\"/></testsuite>' > junit.xml";
  }

  private static String sleepCommand() {
    return windows() ? "ping -n 31 127.0.0.1" : "sleep 30";
  }
}
