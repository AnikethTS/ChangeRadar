package io.github.anikethts.changeradar.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anikethts.changeradar.core.Report;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Local tool implementations for {@link McpServer}. No network and no source upload. */
final class McpTools implements McpServer.ToolHost {

  private final ReportWriters writers = new ReportWriters();

  @Override
  public String call(String name, JsonNode arguments) throws Exception {
    return switch (name) {
      case "analyze" -> analyze(arguments);
      case "brief" -> brief(arguments);
      default -> throw new IllegalArgumentException("Unknown tool: " + name);
    };
  }

  private String analyze(JsonNode arguments) throws Exception {
    String base = text(arguments, "base");
    String head = text(arguments, "head");
    if (base == null || head == null) {
      throw new IllegalArgumentException("base and head are required");
    }
    Path repo = path(arguments, "repo");
    Path output = path(arguments, "output");
    Path policyFile = path(arguments, "policyFile");
    AnalyzeCommand command =
        AnalyzeCommand.create(base, head, repo, List.of("agent"), output, policyFile);
    command.call();
    Path repository = (repo == null ? Path.of(".") : repo).toAbsolutePath().normalize();
    Path dest = output == null ? repository.resolve("build").resolve("changeradar") : output;
    Path brief = dest.resolve("report.agent.json");
    if (!Files.isRegularFile(brief)) {
      throw new IllegalStateException("Agent brief was not written: " + brief);
    }
    return Files.readString(brief);
  }

  private String brief(JsonNode arguments) throws Exception {
    String report = text(arguments, "report");
    if (report == null) {
      throw new IllegalArgumentException("report is required");
    }
    Path file = Path.of(report);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("report.json was not found: " + file);
    }
    Report parsed = JsonReportWriter.read(writers.mapper(), file);
    return AgentReportWriter.render(writers.mapper(), parsed);
  }

  private static String text(JsonNode arguments, String field) {
    JsonNode node = arguments.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    String value = node.asText().trim();
    return value.isEmpty() ? null : value;
  }

  private static Path path(JsonNode arguments, String field) {
    String value = text(arguments, field);
    return value == null ? null : Path.of(value);
  }
}
