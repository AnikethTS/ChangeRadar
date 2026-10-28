package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.anikethts.changeradar.core.AnalysisRequest;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.PolicyResult;
import io.github.anikethts.changeradar.core.Report;
import io.github.anikethts.changeradar.core.SourceLocation;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlReportWriterTest {

  @TempDir Path tmp;

  @Test
  void embedsEscapedReport() throws Exception {
    ImpactFinding finding =
        new ImpactFinding(
            "CALLER",
            Confidence.DIRECT,
            "<script>alert(1)</script>",
            "sym",
            new SourceLocation("src/Demo.java", 4, 4));
    Report report =
        new Report(
            Report.SCHEMA_VERSION,
            "0.0.0",
            Instant.parse("2026-10-27T00:00:00Z"),
            new AnalysisRequest(Path.of("/repo"), "base", "head"),
            List.of(),
            List.of(),
            List.of(finding),
            List.of(
                new TestRecommendation(
                    "DemoTest", "src/DemoTest.java", Confidence.HEURISTIC, "name match")),
            List.of(new Limitation("SCOPED_SOURCES", "Parsers ran on a subset.")),
            PolicyResult.none());
    Path file = tmp.resolve("report.html");
    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    HtmlReportWriter.write(mapper, report, file);
    String page = Files.readString(file);
    assertFalse(page.contains("<script>alert(1)</script>"));
    assertTrue(page.contains("\\u003cscript>alert(1)\\u003c/script>"));
    assertTrue(page.contains(finding.id()));
    assertTrue(page.contains("DIRECT"));
    assertTrue(page.contains("SCOPED_SOURCES"));
    assertTrue(page.contains("DemoTest"));
  }
}
