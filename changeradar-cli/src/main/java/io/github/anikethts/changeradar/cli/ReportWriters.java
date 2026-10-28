package io.github.anikethts.changeradar.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.anikethts.changeradar.core.Report;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

final class ReportWriters {

  private final ObjectMapper mapper;

  ReportWriters() {
    this.mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  void writeTerminal(Report report, PrintStream out) {
    TextReportWriter.writeTerminal(report, out);
  }

  void writeMarkdown(Report report, Path file) throws IOException {
    TextReportWriter.writeMarkdown(report, file);
  }

  void writeCompact(Report report, Path file) throws IOException {
    TextReportWriter.writeCompact(report, file);
  }

  String compactMarkdown(Report report) {
    return TextReportWriter.compactMarkdown(report);
  }

  void writeSarif(Report report, Path file) throws IOException {
    SarifReportWriter.writeSarif(mapper, report, file);
  }

  void writeGithub(Report report, Path file) throws IOException {
    GithubReportWriter.writeGithub(mapper, report, file);
  }

  void writeJson(Report report, Path file) throws IOException {
    JsonReportWriter.writeJson(mapper, report, file);
  }

  void writeAgent(Report report, Path file) throws IOException {
    AgentReportWriter.write(mapper, report, file);
  }

  ObjectMapper mapper() {
    return mapper;
  }
}
