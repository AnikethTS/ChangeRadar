package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.anikethts.changeradar.core.AnalysisRequest;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.PolicyResult;
import io.github.anikethts.changeradar.core.Report;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpServerTest {

  @TempDir Path tmp;

  @Test
  void listsToolsAndProjectsAnExistingReport() throws Exception {
    Path report = tmp.resolve("report.json");
    ReportWriters writers = new ReportWriters();
    writers.writeJson(
        new Report(
            Report.SCHEMA_VERSION,
            "0.2.6",
            Instant.parse("2026-10-28T00:00:00Z"),
            new AnalysisRequest(tmp, "aaa", "bbb"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new Limitation("STATIC_ANALYSIS_LIMITS", "Reflection is unresolved.")),
            PolicyResult.none()),
        report);

    ObjectMapper json = new ObjectMapper();
    byte[] request =
        frames(
            json,
            message(json, 1, "initialize"),
            message(json, null, "notifications/initialized"),
            message(json, 2, "tools/list"),
            callBrief(json, 3, report));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    McpServer.serve(new ByteArrayInputStream(request), out, new McpTools());

    List<JsonNode> responses = frames(json, out.toByteArray());
    assertEquals(3, responses.size());
    assertEquals("2024-11-05", responses.get(0).get("result").get("protocolVersion").asText());
    JsonNode tools = responses.get(1).get("result").get("tools");
    assertEquals("analyze", tools.get(0).get("name").asText());
    assertEquals("brief", tools.get(1).get("name").asText());
    String text = responses.get(2).get("result").get("content").get(0).get("text").asText();
    assertTrue(text.contains("\"agentSchemaVersion\" : \"1.0.0\""));
    assertTrue(text.contains("STATIC_ANALYSIS_LIMITS"));
    assertTrue(text.contains("Do not skip tests or deploy from this brief."));
    assertTrue(!responses.get(2).get("result").has("isError"));
  }

  @Test
  void speaksNewlineJsonAndEchoesTheClientProtocol() throws Exception {
    ObjectMapper json = new ObjectMapper();
    ObjectNode initialize = message(json, 1, "initialize");
    initialize.putObject("params").put("protocolVersion", "2025-06-18");
    byte[] request =
        (json.writeValueAsString(initialize)
                + "\n"
                + json.writeValueAsString(message(json, 2, "tools/list"))
                + "\n")
            .getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    McpServer.serve(new ByteArrayInputStream(request), out, new McpTools());

    String payload = out.toString(StandardCharsets.UTF_8);
    assertTrue(!payload.contains("Content-Length"));
    String[] lines = payload.split("\n");
    assertEquals(2, lines.length);
    JsonNode handshake = json.readTree(lines[0]);
    assertEquals("2025-06-18", handshake.get("result").get("protocolVersion").asText());
    assertTrue(handshake.get("result").get("instructions").asText().contains("Do not skip tests"));
    assertEquals(
        "analyze", json.readTree(lines[1]).get("result").get("tools").get(0).get("name").asText());
  }

  private static ObjectNode message(ObjectMapper json, Integer id, String method) {
    ObjectNode node = json.createObjectNode();
    node.put("jsonrpc", "2.0");
    if (id != null) {
      node.put("id", id);
    }
    node.put("method", method);
    return node;
  }

  private static ObjectNode callBrief(ObjectMapper json, int id, Path report) {
    ObjectNode node = message(json, id, "tools/call");
    ObjectNode params = node.putObject("params");
    params.put("name", "brief");
    params.putObject("arguments").put("report", report.toString());
    return node;
  }

  private static byte[] frames(ObjectMapper json, JsonNode... messages) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    for (JsonNode message : messages) {
      byte[] body = json.writeValueAsBytes(message);
      buffer.write(
          ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      buffer.write(body);
    }
    return buffer.toByteArray();
  }

  private static List<JsonNode> frames(ObjectMapper json, byte[] payload) throws Exception {
    List<JsonNode> messages = new ArrayList<>();
    int offset = 0;
    while (offset < payload.length) {
      int headerEnd = indexOf(payload, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), offset);
      String header = new String(payload, offset, headerEnd - offset, StandardCharsets.US_ASCII);
      int length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
      int body = headerEnd + 4;
      messages.add(json.readTree(payload, body, length));
      offset = body + length;
    }
    return messages;
  }

  private static int indexOf(byte[] data, byte[] needle, int from) {
    for (int i = from; i <= data.length - needle.length; i++) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (data[i + j] != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        return i;
      }
    }
    return -1;
  }
}
