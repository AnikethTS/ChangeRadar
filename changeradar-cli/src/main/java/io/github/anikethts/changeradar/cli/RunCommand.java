package io.github.anikethts.changeradar.cli;

import io.github.anikethts.changeradar.core.GitAccessException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "run",
    description =
        "Analyze two Git revisions, then run a command you supply in the repository working tree."
            + " Does not choose Gradle, Maven, or pytest, does not checkout a ref, and does not"
            + " upload source. changeradar analyze never runs a command.")
public final class RunCommand implements Callable<Integer> {

  @Option(
      names = "--base",
      required = true,
      description = "Base Git ref or SHA (the revision the change is compared against).")
  String base;

  @Option(
      names = "--head",
      required = true,
      description = "Head Git ref or SHA (the revision that contains the change).")
  String head;

  @Option(
      names = "--repo",
      defaultValue = ".",
      description =
          "Path to the Git repository. The command runs here. Default: current" + " directory.")
  Path repo;

  @Option(
      names = "--command",
      required = true,
      description =
          "Command to run after analysis, passed to the system shell. Example:"
              + " ./gradlew test. ChangeRadar does not pick a build tool.")
  String command;

  @Option(
      names = "--timeout",
      defaultValue = "600",
      description = "Seconds to allow the command. Default: 600. Maximum: 7200.")
  int timeout;

  @Option(
      names = "--format",
      defaultValue = "terminal",
      split = ",",
      description =
          "Comma-separated formats: terminal, markdown, json, compact, sarif, github, html,"
              + " agent. Default: terminal.")
  List<String> formats;

  @Option(names = "--output", description = "Directory for report files and command output.")
  Path output;

  @Option(
      names = "--policy",
      split = ",",
      description = "Named blocking policies, passed through to analyze.")
  List<String> policies;

  @Option(names = "--policy-file", description = "Policy YAML, passed through to analyze.")
  Path policyFile;

  @Option(names = "--baseline", description = "Policy baseline, passed through to analyze.")
  Path baseline;

  @Option(names = "--write-baseline", description = "Write a policy baseline during analyze.")
  Path writeBaseline;

  @Option(
      names = "--suppress-file",
      description = "Finding suppressions, passed through to analyze.")
  Path suppressFile;

  @Option(
      names = "--write-suppressions",
      description = "Write finding suppressions during analyze.")
  Path writeSuppressions;

  @Option(names = "--classpath", description = "Already-built jars, passed through to analyze.")
  List<String> classpath;

  @Option(
      names = "--coverage",
      description = "JaCoCo or LCOV input, read after the command. Does not run tests itself.")
  List<String> coverage;

  @Option(
      names = "--junit",
      description =
          "JUnit XML the command is expected to write. Read after the command. Does not run"
              + " tests itself.")
  List<String> junit;

  @Option(names = "--full-index", description = "Parse every HEAD source file.")
  boolean fullIndex;

  @Override
  public Integer call() throws Exception {
    if (command.isBlank()) {
      throw new GitAccessException("run requires a non-blank --command");
    }
    AnalyzeCommand analyze =
        AnalyzeCommand.create(
            base,
            head,
            repo,
            formats,
            output,
            policies,
            policyFile,
            baseline,
            writeBaseline,
            suppressFile,
            writeSuppressions,
            classpath,
            coverage,
            junit,
            fullIndex);
    analyze.runAfterAnalysis(command, timeout);
    return analyze.call();
  }
}
