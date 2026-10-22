package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisRequest;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.PolicyResult;
import io.github.anikethts.changeradar.core.Report;
import io.github.anikethts.changeradar.core.SourceLocation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubReportWriterTest {

  @Test
  void skipsHeuristicAndTestFileCallers() {
    ImpactFinding production =
        new ImpactFinding(
            "CALLER",
            Confidence.INDIRECT,
            "production caller",
            "sym",
            new SourceLocation("src/main/java/demo/Caller.java", 10, 12));
    ImpactFinding testCaller =
        new ImpactFinding(
            "CALLER",
            Confidence.INDIRECT,
            "test caller",
            "sym",
            new SourceLocation(
                "changeradar-core/src/test/java/demo/PolicyEngineTest.java", 218, 218));
    ImpactFinding heuristic =
        new ImpactFinding(
            "CALLER",
            Confidence.HEURISTIC,
            "name match",
            "sym",
            new SourceLocation("src/main/java/demo/Other.java", 4, 4));
    Report report =
        new Report(
            Report.SCHEMA_VERSION,
            "0.0.0",
            Instant.parse("2026-10-21T00:00:00Z"),
            new AnalysisRequest(Path.of("/repo"), "base", "head"),
            List.of(),
            List.of(),
            List.of(production, testCaller, heuristic),
            List.of(),
            List.of(),
            PolicyResult.none());
    GithubReportWriter.GithubCheck check = GithubReportWriter.githubCheck(report);
    assertEquals(1, check.annotations().size());
    assertTrue(check.annotations().get(0).path().contains("Caller.java"));
  }
}
