package io.github.anikethts.changeradar.cli;

import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.GitAccessException;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns DIRECT test recommendations into a runner filter. Heuristic and unknown recommendations are
 * not executed. Names that are not a safe token are skipped so they cannot change the shell
 * command.
 */
final class TestRunFilter {

  static final int MAX_TESTS = 15;

  private static final Pattern CLASS_TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_$.]*");
  private static final Pattern PY_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern RELATIVE_PATH = Pattern.compile("[A-Za-z0-9_./-]+");

  private final String runner;

  private TestRunFilter(String runner) {
    this.runner = runner;
  }

  static TestRunFilter parse(String raw) {
    String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    if (!id.equals("gradle") && !id.equals("maven") && !id.equals("pytest")) {
      throw new GitAccessException(
          "Unknown --runner value: " + raw + ". Known runners: gradle, maven, pytest.");
    }
    return new TestRunFilter(id);
  }

  /**
   * @return the command to run, or {@code null} when no DIRECT test had a safe filter
   */
  String commandFor(String template, List<TestRecommendation> tests, List<Limitation> notes) {
    List<String> tokens = new ArrayList<>();
    int notDirect = 0;
    int unsafe = 0;
    List<TestRecommendation> ordered =
        tests.stream()
            .sorted(
                Comparator.comparing(TestRecommendation::path)
                    .thenComparing(TestRecommendation::name))
            .toList();
    for (TestRecommendation test : ordered) {
      if (test.confidence() != Confidence.DIRECT) {
        notDirect++;
        continue;
      }
      String token = token(test);
      if (token == null) {
        unsafe++;
        continue;
      }
      if (!tokens.contains(token)) {
        tokens.add(token);
      }
    }
    int omitted = 0;
    if (tokens.size() > MAX_TESTS) {
      omitted = tokens.size() - MAX_TESTS;
      tokens = new ArrayList<>(tokens.subList(0, MAX_TESTS));
    }
    if (tokens.isEmpty()) {
      notes.add(
          new Limitation(
              "SELECTED_TESTS",
              "No DIRECT test had a safe "
                  + runner
                  + " filter, so the command was not run. "
                  + notDirect
                  + " recommendations were not DIRECT and were left out. Absence of a run is not"
                  + " proof the rest of the suite is unnecessary."));
      return null;
    }
    String rendered = render(tokens);
    String command =
        template.contains("{tests}")
            ? template.replace("{tests}", rendered)
            : template + " " + rendered;
    notes.add(
        new Limitation(
            "SELECTED_TESTS",
            "Selected "
                + tokens.size()
                + " DIRECT tests for "
                + runner
                + ". "
                + notDirect
                + " recommendations were not DIRECT and were not executed."
                + (unsafe > 0 ? " " + unsafe + " DIRECT names were not a safe filter." : "")
                + (omitted > 0
                    ? " "
                        + omitted
                        + " additional DIRECT tests were past the cap of "
                        + MAX_TESTS
                        + "."
                    : "")
                + " A selected test is not proof the change is safe."));
    return command;
  }

  private String token(TestRecommendation test) {
    if ("pytest".equals(runner)) {
      return pytestNode(test);
    }
    String name = test.name();
    if (!CLASS_TOKEN.matcher(name).matches()) {
      return null;
    }
    return name;
  }

  private static String pytestNode(TestRecommendation test) {
    String path = test.path().replace('\\', '/');
    if (path.startsWith("/") || path.contains("..") || !RELATIVE_PATH.matcher(path).matches()) {
      return null;
    }
    if (test.name().equals(test.path()) || test.name().equals(path)) {
      return path;
    }
    if (!PY_NAME.matcher(test.name()).matches()) {
      return null;
    }
    return path + "::" + test.name();
  }

  private String render(List<String> tokens) {
    if ("maven".equals(runner)) {
      return "-Dtest=" + String.join(",", tokens);
    }
    if ("pytest".equals(runner)) {
      return String.join(" ", tokens);
    }
    StringBuilder args = new StringBuilder();
    for (String token : tokens) {
      if (!args.isEmpty()) {
        args.append(' ');
      }
      args.append("--tests ").append(token);
    }
    return args.toString();
  }
}
