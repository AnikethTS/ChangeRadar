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
 * Minimal MCP stdio server. Speaks JSON-RPC with {@code Content-Length} framing. Writes protocol
 * bytes only on {@code out}.
 */
final class McpServer {

  private static final ObjectMapper JSON = new ObjectMapper();

  private McpServer() {}

  static void serve(InputStream in, OutputStream out, ToolHost host) throws IOException {
    while (true) {
      byte[] body = readMessage(in);
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
      } catch (RuntimeException failure) {
        response.set("error", rpcError(-32603, failure.getMessage()));
      }
      writeMessage(out, response);
    }
  }

  private static JsonNode result(String method, JsonNode params, ToolHost host) throws IOException {
    return switch (method) {
      case "initialize" -> initialize();
      case "ping" -> JSON.createObjectNode();
      case "tools/list" -> tools();
      case "tools/call" -> call(params, host);
      default -> throw new IllegalArgumentException("Unknown method: " + method);
    };
  }

  private static ObjectNode initialize() {
    ObjectNode result = JSON.createObjectNode();
    result.put("protocolVersion", "2024-11-05");
    ObjectNode capabilities = result.putObject("capabilities");
    capabilities.putObject("tools");
    ObjectNode info = result.putObject("serverInfo");
    info.put("name", "changeradar");
    info.put("version", Version.current());
    return result;
  }

  private static ObjectNode tools() {
    ObjectNode result = JSON.createObjectNode();
    ArrayNode tools = result.putArray("tools");
    tools.add(
        tool("analyze", "Run local impact analysis and return the agent brief.", analyzeSchema()));
    tools.add(
        tool(
            "brief",
            "Project an existing report.json into an agent brief. Does not run Git.",
            briefSchema()));
    return result;
  }

  private static ObjectNode tool(String name, String description, ObjectNode schema) {
    ObjectNode tool = JSON.createObjectNode();
    tool.put("name", name);
    tool.put("description", description);
    tool.set("inputSchema", schema);
    return tool;
  }

  private static ObjectNode analyzeSchema() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("base").put("type", "string").put("description", "Base Git ref.");
    properties.putObject("head").put("type", "string").put("description", "Head Git ref.");
    properties
        .putObject("repo")
        .put("type", "string")
        .put("description", "Repository path. Default: .");
    properties
        .putObject("policyFile")
        .put("type", "string")
        .put("description", "Optional policy YAML path.");
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
        .put("description", "Path to report.json.");
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

  private static byte[] readMessage(InputStream in) throws IOException {
    int contentLength = -1;
    while (true) {
      String line = readLine(in);
      if (line == null) {
        return null;
      }
      if (line.isEmpty()) {
        break;
      }
      int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
        contentLength = Integer.parseInt(line.substring(colon + 1).trim());
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

  private static void writeMessage(OutputStream out, JsonNode body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    String header = "Content-Length: " + bytes.length + "\r\n\r\n";
    out.write(header.getBytes(StandardCharsets.US_ASCII));
    out.write(bytes);
    out.flush();
  }

  interface ToolHost {
    String call(String name, JsonNode arguments) throws Exception;
  }

  private static final class ToolFailure extends RuntimeException {
    ToolFailure(String message) {
      super(message);
    }
  }
}
