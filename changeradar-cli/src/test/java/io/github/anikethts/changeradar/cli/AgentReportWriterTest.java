package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anikethts.changeradar.core.AnalysisRequest;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.PolicyResult;
import io.github.anikethts.changeradar.core.Report;
import io.github.anikethts.changeradar.core.SourceLocation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentReportWriterTest {

  @Test
  void keepsDirectFindingsAndEveryLimitation() throws Exception {
    List<ImpactFinding> findings = new ArrayList<>();
    for (int i = 0; i < AgentReportWriter.MAX_FINDINGS; i++) {
      findings.add(finding("a" + i, Confidence.UNKNOWN));
    }
    findings.add(finding("z-direct", Confidence.DIRECT));
    List<ChangedSymbol> symbols = new ArrayList<>();
    for (int i = 0; i < AgentReportWriter.MAX_SYMBOLS; i++) {
      symbols.add(symbol("p" + i, false));
    }
    symbols.add(symbol("public-one", true));
    Report report =
        new Report(
            Report.SCHEMA_VERSION,
            "0.2.6",
            Instant.parse("2026-10-28T00:00:00Z"),
            new AnalysisRequest(Path.of("/repo"), "base", "head"),
            List.of(),
            symbols,
            findings,
            List.of(),
            List.of(
                new Limitation("STATIC_ANALYSIS_LIMITS", "Absence is not proof."),
                new Limitation("SCOPED_SOURCES", "Parsers ran on a subset.")),
            new PolicyResult(true, List.of("public-api-removal")));

    JsonNode brief =
        new ReportWriters()
            .mapper()
            .readTree(AgentReportWriter.render(new ReportWriters().mapper(), report));

    assertEquals("1.0.0", brief.get("agentSchemaVersion").asText());
    assertEquals(1, brief.get("exitCode").asInt());
    assertTrue(brief.get("policyBlocking").asBoolean());
    assertEquals(41, brief.get("counts").get("findings").asInt());
    assertEquals(1, brief.get("truncated").get("findings").asInt());
    assertEquals(1, brief.get("truncated").get("symbols").asInt());
    assertEquals(AgentReportWriter.MAX_FINDINGS, brief.get("findings").size());
    assertEquals("z-direct", brief.get("findings").get(0).get("id").asText());
    assertEquals("DIRECT", brief.get("findings").get(0).get("confidence").asText());
    assertEquals(2, brief.get("limitations").size());
    assertEquals("STATIC_ANALYSIS_LIMITS", brief.get("limitations").get(0).get("code").asText());
    assertEquals("SCOPED_SOURCES", brief.get("limitations").get(1).get("code").asText());
    assertEquals(AgentReportWriter.MAX_SYMBOLS, brief.get("changedSymbols").size());
    assertEquals("public-one", brief.get("changedSymbols").get(0).get("name").asText());
    assertTrue(
        brief.get("guidance").toString().contains("Do not skip tests or deploy from this brief."));
  }

  private static ImpactFinding finding(String id, Confidence confidence) {
    return new ImpactFinding(
        id,
        "CALLER",
        confidence,
        "summary " + id,
        "symbol",
        new SourceLocation("src/Demo.java", 4, 4));
  }

  private static ChangedSymbol symbol(String id, boolean publiclyVisible) {
    return new ChangedSymbol(
        id,
        "method",
        id,
        "demo.Demo",
        new SourceLocation("src/Demo.java", 4, 4),
        ChangeType.MODIFY,
        publiclyVisible);
  }
}
