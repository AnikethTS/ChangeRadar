package io.github.anikethts.changeradar.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Enriches suggested tests with suite coverage (changed-line hits) and JUnit XML pass/fail. Does
 * not run the project.
 */
public final class TestEvidence {

  private TestEvidence() {}

  public static Result apply(
      List<TestRecommendation> tests,
      List<ChangedFile> changed,
      List<Path> coverageFiles,
      List<Path> junitFiles) {
    List<Limitation> limitations = new ArrayList<>();
    CoverageIndex coverage = CoverageIndex.load(coverageFiles, limitations);
    JunitResults junit = JunitResults.load(junitFiles, limitations);
    if (!coverageFiles.isEmpty()) {
      CoverageIndex.Stats stats = coverage.stats(changed);
      limitations.add(
          new Limitation(
              "COVERAGE_INGEST",
              "Coverage reports ("
                  + coverage.filesLoaded()
                  + ") are suite-wide, not per-test. Covered "
                  + stats.coveredLines()
                  + " of "
                  + stats.changedLines()
                  + " changed lines. ChangeRadar did not run tests."));
    }
    if (!junitFiles.isEmpty()) {
      limitations.add(
          new Limitation(
              "JUNIT_INGEST",
              "JUnit XML ("
                  + junit.filesLoaded()
                  + ") annotated recommended tests with pass/fail. ChangeRadar did not run tests."));
    }

    List<TestRecommendation> enriched = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (TestRecommendation test : tests) {
      boolean covers = coverage.intersectsChanged(test.path(), changed);
      ExecutionStatus status = junit.statusFor(test);
      String reason = test.reason();
      if (covers) {
        reason = "Suite coverage hit changed lines related to this test. " + reason;
      }
      if (status == ExecutionStatus.FAILED || status == ExecutionStatus.ERROR) {
        reason = "Failed in the provided JUnit report. " + reason;
      } else if (status == ExecutionStatus.PASSED) {
        reason = "Passed in the provided JUnit report. " + reason;
      } else if (status == ExecutionStatus.SKIPPED) {
        reason = "Skipped in the provided JUnit report. " + reason;
      }
      TestRecommendation next = test.withEvidence(covers, status, reason);
      enriched.add(next);
      seen.add(key(next));
    }
    for (JunitResults.Case failed : junit.failed()) {
      if (!failed.relatesTo(changed)) {
        continue;
      }
      TestRecommendation extra =
          new TestRecommendation(
              failed.displayName(),
              failed.displayPath(),
              Confidence.DIRECT,
              "Failed in the provided JUnit report and relates to a changed path.",
              coverage.intersectsChanged(failed.displayPath(), changed),
              failed.status());
      if (seen.add(key(extra))) {
        enriched.add(extra);
      }
    }
    enriched.sort(
        Comparator.comparing(
                (TestRecommendation test) ->
                    test.execution() == ExecutionStatus.FAILED
                            || test.execution() == ExecutionStatus.ERROR
                        ? 0
                        : 1)
            .thenComparing(TestRecommendation::path)
            .thenComparing(TestRecommendation::name));
    return new Result(enriched, limitations);
  }

  public static List<Path> expand(Path repository, List<String> raw, String kind) {
    List<Path> files = new ArrayList<>();
    if (raw == null) {
      return files;
    }
    for (String value : raw) {
      if (value == null || value.isBlank()) {
        continue;
      }
      Path path = Path.of(value);
      Path resolved = path.isAbsolute() ? path : repository.resolve(path).normalize();
      if (!Files.exists(resolved)) {
        throw new GitAccessException("Missing --" + kind + " path: " + resolved);
      }
      if (Files.isRegularFile(resolved)) {
        files.add(resolved);
        continue;
      }
      if (!Files.isDirectory(resolved)) {
        throw new GitAccessException("Not a file or directory: " + resolved);
      }
      try (Stream<Path> walk = Files.walk(resolved, 6)) {
        walk.filter(Files::isRegularFile)
            .filter(candidate -> matchesKind(candidate, kind))
            .forEach(files::add);
      } catch (Exception e) {
        throw new GitAccessException("Could not list --" + kind + " directory: " + resolved, e);
      }
    }
    return files;
  }

  private static boolean matchesKind(Path file, String kind) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    if ("coverage".equals(kind)) {
      return name.contains("jacoco")
          || name.equals("lcov.info")
          || name.endsWith(".lcov")
          || name.equals("coverage.xml");
    }
    return (name.startsWith("test-") && name.endsWith(".xml"))
        || (name.contains("junit") && name.endsWith(".xml"));
  }

  private static String key(TestRecommendation test) {
    return test.path() + "#" + test.name();
  }

  public record Result(List<TestRecommendation> tests, List<Limitation> limitations) {}
}
