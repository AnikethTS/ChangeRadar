package io.github.anikethts.changeradar.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.Report;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

final class JsonReportWriter {

  private JsonReportWriter() {}

  static void writeJson(ObjectMapper mapper, Report report, Path file) throws IOException {
    mapper.writeValue(file.toFile(), body(report));
  }

  static String json(ObjectMapper mapper, Report report) throws IOException {
    return mapper.writeValueAsString(body(report));
  }

  private static JsonReport body(Report report) {
    return new JsonReport(
        report.schemaVersion(),
        report.toolVersion(),
        report.generatedAt(),
        report.request().repository().toString(),
        report.request().base(),
        report.request().head(),
        report.changedFiles(),
        report.changedSymbols(),
        report.findings(),
        report.tests(),
        report.limitations(),
        report.policy().blocking(),
        report.policy().violations());
  }

  record JsonReport(
      String schemaVersion,
      String toolVersion,
      Instant generatedAt,
      String repository,
      String base,
      String head,
      List<ChangedFile> changedFiles,
      List<ChangedSymbol> changedSymbols,
      List<ImpactFinding> findings,
      List<TestRecommendation> recommendedTests,
      List<Limitation> limitations,
      boolean policyBlocking,
      List<String> policyViolations) {}
}
