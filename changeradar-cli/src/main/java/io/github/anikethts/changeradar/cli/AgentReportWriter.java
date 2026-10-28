package io.github.anikethts.changeradar.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ExitCode;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.Report;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A capped projection of {@link Report} for a local agent. Not a second analysis. */
final class AgentReportWriter {

  static final String SCHEMA_VERSION = "1.0.0";
  static final int MAX_FINDINGS = 40;
  static final int MAX_FILES = 40;
  static final int MAX_SYMBOLS = 20;
  static final int MAX_TESTS = 15;

  private static final List<String> GUIDANCE =
      List.of(
          "HEURISTIC and UNKNOWN are not proof of impact.",
          "Absence of a finding is not proof of no impact.",
          "Do not skip tests or deploy from this brief.",
          "The full report is report.json.");

  private AgentReportWriter() {}

  static void write(ObjectMapper mapper, Report report, Path file) throws IOException {
    mapper.writeValue(file.toFile(), brief(report));
  }

  static String render(ObjectMapper mapper, Report report) throws IOException {
    return mapper.writeValueAsString(brief(report));
  }

  private static AgentBrief brief(Report report) {
    List<ChangedFile> files =
        report.changedFiles().stream().sorted(Comparator.comparing(ChangedFile::path)).toList();
    List<ChangedSymbol> symbols =
        report.changedSymbols().stream()
            .sorted(
                Comparator.comparingInt((ChangedSymbol symbol) -> symbol.publiclyVisible() ? 0 : 1)
                    .thenComparing(ChangedSymbol::id))
            .toList();
    List<ImpactFinding> findings =
        report.findings().stream()
            .sorted(
                Comparator.comparingInt((ImpactFinding finding) -> rank(finding.confidence()))
                    .thenComparing(ImpactFinding::id))
            .toList();
    List<TestRecommendation> tests =
        report.tests().stream()
            .sorted(
                Comparator.comparingInt((TestRecommendation test) -> rank(test.confidence()))
                    .thenComparing(TestRecommendation::path)
                    .thenComparing(TestRecommendation::name))
            .toList();

    int exit =
        report.policy().blocking() ? ExitCode.POLICY_VIOLATION.code() : ExitCode.SUCCESS.code();
    return new AgentBrief(
        SCHEMA_VERSION,
        report.toolVersion(),
        report.request().repository().toString(),
        report.request().base(),
        report.request().head(),
        exit,
        report.policy().blocking(),
        report.policy().violations(),
        new Counts(
            report.changedFiles().size(),
            report.changedSymbols().size(),
            report.findings().size(),
            report.tests().size(),
            report.limitations().size()),
        new Truncated(
            dropped(files.size(), MAX_FILES),
            dropped(symbols.size(), MAX_SYMBOLS),
            dropped(findings.size(), MAX_FINDINGS),
            dropped(tests.size(), MAX_TESTS)),
        cap(files, MAX_FILES).stream().map(AgentReportWriter::fileRow).toList(),
        cap(symbols, MAX_SYMBOLS).stream().map(AgentReportWriter::symbolRow).toList(),
        cap(findings, MAX_FINDINGS).stream().map(AgentReportWriter::findingRow).toList(),
        cap(tests, MAX_TESTS).stream().map(AgentReportWriter::testRow).toList(),
        List.copyOf(report.limitations()),
        GUIDANCE);
  }

  private static int rank(Confidence confidence) {
    return switch (confidence) {
      case DIRECT -> 0;
      case INDIRECT -> 1;
      case HEURISTIC -> 2;
      case UNKNOWN -> 3;
    };
  }

  private static int dropped(int size, int max) {
    return Math.max(0, size - max);
  }

  private static <T> List<T> cap(List<T> values, int max) {
    if (values.size() <= max) {
      return values;
    }
    return new ArrayList<>(values.subList(0, max));
  }

  private static FileRow fileRow(ChangedFile file) {
    return new FileRow(file.path(), file.changeType().name(), file.previousPath());
  }

  private static SymbolRow symbolRow(ChangedSymbol symbol) {
    String signature = symbol.signature().isBlank() ? null : symbol.signature();
    return new SymbolRow(
        symbol.name(),
        symbol.kind(),
        symbol.changeType().name(),
        symbol.publiclyVisible(),
        symbol.location().path(),
        symbol.location().startLine(),
        signature);
  }

  private static FindingRow findingRow(ImpactFinding finding) {
    return new FindingRow(
        finding.id(),
        finding.confidence().name(),
        finding.type(),
        finding.summary(),
        finding.location().path(),
        finding.location().startLine());
  }

  private static TestRow testRow(TestRecommendation test) {
    return new TestRow(
        test.name(), test.path(), test.confidence().name(), test.reason(), test.execution().name());
  }

  record AgentBrief(
      String agentSchemaVersion,
      String toolVersion,
      String repository,
      String base,
      String head,
      int exitCode,
      boolean policyBlocking,
      List<String> policyViolations,
      Counts counts,
      Truncated truncated,
      List<FileRow> changedFiles,
      List<SymbolRow> changedSymbols,
      List<FindingRow> findings,
      List<TestRow> tests,
      List<Limitation> limitations,
      List<String> guidance) {}

  record Counts(int files, int symbols, int findings, int tests, int limitations) {}

  record Truncated(int files, int symbols, int findings, int tests) {}

  record FileRow(String path, String changeType, String previousPath) {}

  record SymbolRow(
      String name,
      String kind,
      String changeType,
      boolean publiclyVisible,
      String path,
      int line,
      String signature) {}

  record FindingRow(
      String id, String confidence, String type, String summary, String path, int line) {}

  record TestRow(String name, String path, String confidence, String reason, String execution) {}
}
