package io.github.anikethts.changeradar.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anikethts.changeradar.core.Report;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class HtmlReportWriter {

  static final String TOKEN = "__REPORT_JSON__";

  private HtmlReportWriter() {}

  static void write(ObjectMapper mapper, Report report, Path file) throws IOException {
    Files.writeString(file, render(JsonReportWriter.json(mapper, report)));
  }

  static String render(String json) {
    String safe = json.replace("<", "\\u003c");
    String template = template();
    if (!template.contains(TOKEN)) {
      throw new IllegalStateException("Review template is missing the report token");
    }
    return template.replace(TOKEN, safe);
  }

  private static String template() {
    try (InputStream in = HtmlReportWriter.class.getResourceAsStream("/review/report.html")) {
      if (in == null) {
        throw new IllegalStateException("Missing /review/report.html");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("Missing /review/report.html", ex);
    }
  }
}
