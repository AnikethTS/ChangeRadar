package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.GitAccessException;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestRunFilterTest {

  @Test
  void gradleKeepsDirectNamesAndDropsHeuristics() {
    List<Limitation> notes = new ArrayList<>();
    String command =
        TestRunFilter.parse("gradle")
            .commandFor(
                "./gradlew test",
                List.of(
                    new TestRecommendation(
                        "DemoTest", "src/test/DemoTest.java", Confidence.DIRECT, "calls n"),
                    new TestRecommendation(
                        "GuessTest", "src/test/GuessTest.java", Confidence.HEURISTIC, "name"),
                    new TestRecommendation(
                        "Bad Test", "src/test/Bad.java", Confidence.DIRECT, "space")),
                notes);

    assertEquals("./gradlew test --tests DemoTest", command);
    assertTrue(notes.get(0).message().contains("1 recommendations were not DIRECT"));
    assertTrue(notes.get(0).message().contains("1 DIRECT names were not a safe filter"));
  }

  @Test
  void mavenAndPytestUseTheirOwnFilters() {
    TestRecommendation direct =
        new TestRecommendation("test_n", "tests/test_pay.py", Confidence.DIRECT, "calls");
    List<Limitation> mavenNotes = new ArrayList<>();
    assertEquals(
        "mvn -Dtest=PayTest",
        TestRunFilter.parse("maven")
            .commandFor(
                "mvn",
                List.of(
                    new TestRecommendation("PayTest", "src/PayTest.java", Confidence.DIRECT, "x")),
                mavenNotes));
    List<Limitation> pytestNotes = new ArrayList<>();
    assertEquals(
        "pytest -q tests/test_pay.py::test_n",
        TestRunFilter.parse("pytest")
            .commandFor("pytest -q {tests}", List.of(direct), pytestNotes));
  }

  @Test
  void skipsTheCommandWhenNothingDirectIsSafe() {
    List<Limitation> notes = new ArrayList<>();
    assertNull(
        TestRunFilter.parse("gradle")
            .commandFor(
                "./gradlew test",
                List.of(
                    new TestRecommendation(
                        "GuessTest", "src/GuessTest.java", Confidence.HEURISTIC, "name")),
                notes));
    assertEquals("SELECTED_TESTS", notes.get(0).code());
    assertTrue(notes.get(0).message().contains("command was not run"));
  }

  @Test
  void rejectsAnUnknownRunner() {
    GitAccessException failure =
        assertThrows(GitAccessException.class, () -> TestRunFilter.parse("bazel"));
    assertTrue(failure.getMessage().contains("bazel"));
  }
}
