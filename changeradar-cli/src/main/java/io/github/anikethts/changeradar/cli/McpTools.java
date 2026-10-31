package io.github.anikethts.changeradar.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anikethts.changeradar.core.Report;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    AnalyzeCommand command = configure(arguments);
    command.call();
    Path repo = command.repo;
    Path output = command.output;
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

  /**
   * Same evidence inputs CI already passes to {@code analyze}. Does not run the build or tests, and
   * does not write a baseline or suppressions file.
   */
  static AnalyzeCommand configure(JsonNode arguments) {
    String base = text(arguments, "base");
    String head = text(arguments, "head");
    if (base == null || head == null) {
      throw new IllegalArgumentException("base and head are required");
    }
    Path repo = path(arguments, "repo");
    return AnalyzeCommand.create(
        base,
        head,
        repo == null ? Path.of(".") : repo,
        List.of("agent"),
        path(arguments, "output"),
        texts(arguments, "policies"),
        path(arguments, "policyFile"),
        path(arguments, "baseline"),
        null,
        path(arguments, "suppressFile"),
        null,
        texts(arguments, "classpath"),
        texts(arguments, "coverage"),
        texts(arguments, "junit"),
        bool(arguments, "fullIndex"));
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

  private static List<String> texts(JsonNode arguments, String field) {
    JsonNode node = arguments.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    List<String> values = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(
          item -> {
            String value = item.asText().trim();
            if (!value.isEmpty()) {
              values.add(value);
            }
          });
    } else if (node.isTextual()) {
      String value = node.asText().trim();
      if (!value.isEmpty()) {
        values.add(value);
      }
    }
    return values.isEmpty() ? null : List.copyOf(values);
  }

  private static boolean bool(JsonNode arguments, String field) {
    JsonNode node = arguments.get(field);
    return node != null && node.asBoolean(false);
  }
}
