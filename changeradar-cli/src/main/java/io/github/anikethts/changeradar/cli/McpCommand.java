package io.github.anikethts.changeradar.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(
    name = "mcp",
    description =
        "Serve analyze and brief on stdin/stdout for Claude Code, Cursor, and other local MCP"
            + " clients. One JSON object per line. analyze can take JUnit, coverage, and"
            + " classpath from a build that already ran. Does not open a port, run tests, or"
            + " upload source.")
public final class McpCommand implements Callable<Integer> {

  @Override
  public Integer call() {
    try {
      McpServer.serve(System.in, System.out, new McpTools());
      return 0;
    } catch (Exception failure) {
      System.err.println("changeradar: " + failure.getMessage());
      return 2;
    }
  }
}
