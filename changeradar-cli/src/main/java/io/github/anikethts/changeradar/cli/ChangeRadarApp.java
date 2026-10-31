package io.github.anikethts.changeradar.cli;

import io.github.anikethts.changeradar.core.ExitCode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

@Command(
    name = "changeradar",
    header = "ChangeRadar — explainable code-change impact analysis for CI/CD.",
    description =
        "Analyze a Git diff, report what it may affect, and recommend verification. Does not deploy,"
            + " skip tests, or replace the rest of the pipeline.",
    mixinStandardHelpOptions = true,
    versionProvider = ChangeRadarApp.VersionProvider.class,
    subcommands = {
      AnalyzeCommand.class,
      RunCommand.class,
      McpCommand.class,
      ReviewCommand.class,
      PoliciesCommand.class,
      CommandLine.HelpCommand.class
    },
    exitCodeOnInvalidInput = 2,
    exitCodeOnExecutionException = 2)
public final class ChangeRadarApp implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }

  public static void main(String[] args) {
    int code =
        new CommandLine(new ChangeRadarApp())
            .setExecutionExceptionHandler(new FailureExceptionHandler())
            .execute(args);
    System.exit(code);
  }

  static final class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {"changeradar " + io.github.anikethts.changeradar.core.Version.current()};
    }
  }

  static final class FailureExceptionHandler implements CommandLine.IExecutionExceptionHandler {
    @Override
    public int handleExecutionException(
        Exception ex, CommandLine commandLine, CommandLine.ParseResult parseResult) {
      commandLine.getErr().println("changeradar: " + ex.getMessage());
      return ExitCode.FAILURE.code();
    }
  }
}
