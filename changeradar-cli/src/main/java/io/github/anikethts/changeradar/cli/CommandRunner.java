package io.github.anikethts.changeradar.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a user-supplied command in a repository working tree. The command is not chosen by
 * ChangeRadar. Output stays on disk. Nothing is uploaded.
 */
final class CommandRunner {

  static final int MAX_TIMEOUT_SECONDS = 7200;

  private CommandRunner() {}

  static Result run(Path repository, String command, int timeoutSeconds, Path logDirectory) {
    Path stdout = logDirectory.resolve("run.stdout.txt");
    Path stderr = logDirectory.resolve("run.stderr.txt");
    List<String> shell = shell(command);
    ProcessBuilder builder = new ProcessBuilder(shell);
    builder.directory(repository.toFile());
    builder.redirectOutput(stdout.toFile());
    builder.redirectError(stderr.toFile());
    Process process;
    try {
      process = builder.start();
    } catch (IOException failure) {
      String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
      return new Result(0, false, false, message, stdout, stderr);
    }
    try {
      boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        return new Result(0, true, true, null, stdout, stderr);
      }
      return new Result(process.exitValue(), false, true, null, stdout, stderr);
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      return new Result(0, true, true, "interrupted", stdout, stderr);
    }
  }

  private static List<String> shell(String command) {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("win")) {
      return List.of("cmd.exe", "/c", command);
    }
    return List.of("sh", "-c", command);
  }

  record Result(
      int exitCode, boolean timedOut, boolean started, String error, Path stdout, Path stderr) {}
}
