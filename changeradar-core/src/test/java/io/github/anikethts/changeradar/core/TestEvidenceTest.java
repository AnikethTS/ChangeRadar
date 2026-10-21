package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestEvidenceTest {

  @TempDir Path tmp;

  @Test
  void jacocoHitsChangedLineAndJunitMarksFailure() throws Exception {
    Path jacoco = tmp.resolve("jacoco.xml");
    Files.writeString(
        jacoco,
        """
        <report>
          <package name="demo">
            <sourcefile name="Demo.java">
              <line nr="4" mi="0" ci="2"/>
              <line nr="5" mi="1" ci="0"/>
            </sourcefile>
          </package>
        </report>
        """);
    Path junit = tmp.resolve("TEST-DemoTest.xml");
    Files.writeString(
        junit,
        """
        <testsuite>
          <testcase classname="demo.DemoTest" name="coversN">
            <failure message="boom"/>
          </testcase>
        </testsuite>
        """);
    TestRecommendation suggested =
        new TestRecommendation(
            "DemoTest",
            "src/test/java/demo/DemoTest.java",
            Confidence.HEURISTIC,
            "Suggestion only; the test was not executed.");
    ChangedFile changed =
        new ChangedFile(
            "src/main/java/demo/Demo.java", ChangeType.MODIFY, null, List.of(new LineRange(4, 4)));
    TestEvidence.Result result =
        TestEvidence.apply(List.of(suggested), List.of(changed), List.of(jacoco), List.of(junit));
    TestRecommendation enriched = result.tests().get(0);
    assertTrue(enriched.coversChangedLines());
    assertEquals(ExecutionStatus.FAILED, enriched.execution());
    assertTrue(enriched.reason().contains("Failed"));
    assertTrue(result.limitations().stream().anyMatch(l -> "COVERAGE_INGEST".equals(l.code())));
    assertTrue(result.limitations().stream().anyMatch(l -> "JUNIT_INGEST".equals(l.code())));
  }

  @Test
  void lcovAndUnrelatedFailureIsIgnored() throws Exception {
    Path lcov = tmp.resolve("lcov.info");
    Files.writeString(
        lcov,
        """
        SF:src/api.ts
        DA:2,1
        end_of_record
        """);
    Path junit = tmp.resolve("junit.xml");
    Files.writeString(
        junit,
        """
        <testsuite>
          <testcase classname="other.UnrelatedTest" name="nope">
            <failure/>
          </testcase>
        </testsuite>
        """);
    ChangedFile changed =
        new ChangedFile("src/api.ts", ChangeType.MODIFY, null, List.of(new LineRange(2, 2)));
    TestRecommendation suggested =
        new TestRecommendation("api.test.ts", "src/api.test.ts", Confidence.HEURISTIC, "guess");
    TestEvidence.Result result =
        TestEvidence.apply(List.of(suggested), List.of(changed), List.of(lcov), List.of(junit));
    assertEquals(1, result.tests().size());
    assertTrue(result.tests().get(0).coversChangedLines());
    assertEquals(ExecutionStatus.UNKNOWN, result.tests().get(0).execution());
  }

  @Test
  void missingCoveragePathFails() {
    GitAccessException thrown =
        assertThrows(
            GitAccessException.class,
            () -> TestEvidence.expand(tmp, List.of("no-such-jacoco.xml"), "coverage"));
    assertTrue(thrown.getMessage().contains("Missing --coverage"));
  }
}
