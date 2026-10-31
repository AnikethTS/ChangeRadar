package io.github.anikethts.changeradar.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anikethts.changeradar.core.Version;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Local MCP stdio server. Claude Code, Cursor, and the official SDK send one JSON-RPC object per
 * line. A client that sends {@code Content-Length} headers is answered in that framing instead.
 * Protocol bytes go only to {@code out}.
 */
final class McpServer {

  static final String DEFAULT_PROTOCOL = "2024-11-05";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String INSTRUCTIONS =
      "ChangeRadar reads a local Git diff and returns a capped brief. Call analyze with base and"
          + " head. Open the review rows before the rest of the diff. Pass junit, coverage, and"
          + " classpath when CI already produced them; this server does not run the build or"
          + " tests. HEURISTIC and UNKNOWN are not proof of impact. Do not skip tests or deploy"
          + " from the brief. This server does not upload source.";

  private McpServer() {}

  static void serve(InputStream in, OutputStream out, ToolHost host) throws IOException {
    Framing framing = new Framing();
    while (true) {
      byte[] body = framing.read(in);
      if (body == null) {
        return;
      }
      JsonNode request = JSON.readTree(body);
      if (!request.hasNonNull("id")) {
        continue;
      }
      ObjectNode response = JSON.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", request.get("id"));
      String method = request.path("method").asText("");
      try {
        response.set("result", result(method, request.path("params"), host));
      } catch (ToolFailure failure) {
        response.set("result", toolError(failure.getMessage()));
      } catch (IllegalArgumentException failure) {
        response.set("error", rpcError(-32601, failure.getMessage()));
      } catch (RuntimeException failure) {
        response.set("error", rpcError(-32603, failure.getMessage()));
      }
      framing.write(out, response);
    }
  }

  private static JsonNode result(String method, JsonNode params, ToolHost host) throws IOException {
    return switch (method) {
      case "initialize" -> initialize(params);
      case "ping" -> JSON.createObjectNode();
      case "tools/list" -> tools();
      case "tools/call" -> call(params, host);
      default -> throw new IllegalArgumentException("Unknown method: " + method);
    };
  }

  private static ObjectNode initialize(JsonNode params) {
    ObjectNode result = JSON.createObjectNode();
    result.put("protocolVersion", protocolVersion(params));
    ObjectNode capabilities = result.putObject("capabilities");
    capabilities.putObject("tools");
    ObjectNode info = result.putObject("serverInfo");
    info.put("name", "changeradar");
    info.put("version", Version.current());
    result.put("instructions", INSTRUCTIONS);
    return result;
  }

  private static String protocolVersion(JsonNode params) {
    String requested = params.path("protocolVersion").asText("");
    if (requested.matches("\\d{4}-\\d{2}-\\d{2}")) {
      return requested;
    }
    return DEFAULT_PROTOCOL;
  }

  private static ObjectNode tools() {
    ObjectNode result = JSON.createObjectNode();
    ArrayNode tools = result.putArray("tools");
    tools.add(
        tool(
            "analyze",
            "Analyze change impact",
            "Run local impact analysis for a Git base and head. Returns the agent brief,"
                + " including a review order. Optional JUnit, coverage, classpath, and policy"
                + " inputs match CI and do not run the build or tests. HEURISTIC and UNKNOWN"
                + " are not proof.",
            analyzeSchema()));
    tools.add(
        tool(
            "brief",
            "Read an existing report",
            "Project an existing report.json into an agent brief. Does not run Git.",
            briefSchema()));
    return result;
  }

  private static ObjectNode tool(String name, String title, String description, ObjectNode schema) {
    ObjectNode tool = JSON.createObjectNode();
    tool.put("name", name);
    tool.put("title", title);
    tool.put("description", description);
    tool.set("inputSchema", schema);
    return tool;
  }

  private static ObjectNode analyzeSchema() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    properties
        .putObject("base")
        .put("type", "string")
        .put("description", "Base Git ref, such as the pull request base SHA.");
    properties
        .putObject("head")
        .put("type", "string")
        .put("description", "Head Git ref, such as HEAD or the pull request head SHA.");
    properties
        .putObject("repo")
        .put("type", "string")
        .put("description", "Repository path. Default: the process working directory.");
    properties
        .putObject("policyFile")
        .put("type", "string")
        .put("description", "Optional policy YAML path.");
    properties
        .putObject("policies")
        .put("type", "array")
        .put("description", "Blocking policy names, same as --policy. Off unless set.")
        .putObject("items")
        .put("type", "string");
    properties
        .putObject("baseline")
        .put("type", "string")
        .put("description", "Existing policy baseline file. Does not write a new baseline.");
    properties
        .putObject("suppressFile")
        .put("type", "string")
        .put("description", "Existing finding-id suppressions. Does not write a new file.");
    properties
        .putObject("classpath")
        .put("type", "array")
        .put(
            "description",
            "Already-built jars or jar directories for Java type resolution. Does not run the"
                + " build.")
        .putObject("items")
        .put("type", "string");
    properties
        .putObject("coverage")
        .put("type", "array")
        .put("description", "JaCoCo XML or LCOV files the build already wrote. Does not run tests.")
        .putObject("items")
        .put("type", "string");
    properties
        .putObject("junit")
        .put("type", "array")
        .put(
            "description",
            "JUnit XML the test run already wrote. Annotates pass or fail. Does not run tests.")
        .putObject("items")
        .put("type", "string");
    properties
        .putObject("fullIndex")
        .put("type", "boolean")
        .put("description", "Parse every HEAD source file. Default: false.");
    properties
        .putObject("output")
        .put("type", "string")
        .put("description", "Report directory. Default: build/changeradar under the repo.");
    schema.putArray("required").add("base").add("head");
    return schema;
  }

  private static ObjectNode briefSchema() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    schema
        .putObject("properties")
        .putObject("report")
        .put("type", "string")
        .put("description", "Path to report.json from a previous analyze run.");
    schema.putArray("required").add("report");
    return schema;
  }

  private static ObjectNode call(JsonNode params, ToolHost host) throws IOException {
    String name = params.path("name").asText("");
    JsonNode arguments = params.path("arguments");
    if (arguments.isMissingNode() || arguments.isNull()) {
      arguments = JSON.createObjectNode();
    }
    try {
      String text = host.call(name, arguments);
      ObjectNode result = JSON.createObjectNode();
      ArrayNode content = result.putArray("content");
      content.addObject().put("type", "text").put("text", text);
      return result;
    } catch (Exception failure) {
      throw new ToolFailure(
          failure.getMessage() == null ? failure.toString() : failure.getMessage());
    }
  }

  private static ObjectNode toolError(String message) {
    ObjectNode result = JSON.createObjectNode();
    result.putArray("content").addObject().put("type", "text").put("text", message);
    result.put("isError", true);
    return result;
  }

  private static ObjectNode rpcError(int code, String message) {
    ObjectNode error = JSON.createObjectNode();
    error.put("code", code);
    error.put("message", message == null ? "MCP request failed" : message);
    return error;
  }

  interface ToolHost {
    String call(String name, JsonNode arguments) throws Exception;
  }

  private static final class ToolFailure extends RuntimeException {
    ToolFailure(String message) {
      super(message);
    }
  }

  /** Reads one JSON-RPC body and writes the matching framing. */
  private static final class Framing {
    private Boolean newline;

    byte[] read(InputStream in) throws IOException {
      String line = readLine(in);
      while (line != null && line.isBlank()) {
        line = readLine(in);
      }
      if (line == null) {
        return null;
      }
      if (newline == null) {
        newline = line.startsWith("{");
      }
      if (newline) {
        return line.getBytes(StandardCharsets.UTF_8);
      }
      int contentLength = contentLength(line);
      while (true) {
        String header = readLine(in);
        if (header == null) {
          return null;
        }
        if (header.isEmpty()) {
          break;
        }
        int parsed = contentLength(header);
        if (parsed >= 0) {
          contentLength = parsed;
        }
      }
      if (contentLength < 0) {
        throw new IOException("Missing Content-Length");
      }
      byte[] body = in.readNBytes(contentLength);
      if (body.length < contentLength) {
        return null;
      }
      return body;
    }

    void write(OutputStream out, JsonNode body) throws IOException {
      byte[] bytes = JSON.writeValueAsBytes(body);
      if (Boolean.TRUE.equals(newline)) {
        out.write(bytes);
        out.write('\n');
      } else {
        String header = "Content-Length: " + bytes.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
      }
      out.flush();
    }

    private static int contentLength(String header) {
      int colon = header.indexOf(':');
      if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
        return Integer.parseInt(header.substring(colon + 1).trim());
      }
      return -1;
    }

    private static String readLine(InputStream in) throws IOException {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      while (true) {
        int value = in.read();
        if (value < 0) {
          return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
        }
        if (value == '\n') {
          return buffer.toString(StandardCharsets.UTF_8);
        }
        if (value != '\r') {
          buffer.write(value);
        }
      }
    }
  }
}
