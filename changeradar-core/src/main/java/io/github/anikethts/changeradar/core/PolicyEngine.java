package io.github.anikethts.changeradar.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Evaluates named blocking policies against an already-computed analysis. Nothing is enabled unless
 * the caller passes it explicitly.
 */
public final class PolicyEngine {

  public static final String BREAKING_PUBLIC_JAVA_API = "breaking-public-java-api";
  public static final String BREAKING_PUBLIC_EXPORT = "breaking-public-export";
  public static final String BREAKING_PUBLIC_PYTHON = "breaking-public-python";
  public static final String BREAKING_PUBLIC_PHP = "breaking-public-php";
  public static final String ANALYSIS_UNAVAILABLE = "analysis-unavailable";

  public static final Set<String> KNOWN =
      Set.of(
          BREAKING_PUBLIC_JAVA_API,
          BREAKING_PUBLIC_EXPORT,
          BREAKING_PUBLIC_PYTHON,
          BREAKING_PUBLIC_PHP,
          ANALYSIS_UNAVAILABLE);

  private PolicyEngine() {}

  public static Set<String> parseEnabled(Collection<String> raw) {
    Set<String> enabled = new LinkedHashSet<>();
    if (raw == null) {
      return enabled;
    }
    for (String value : raw) {
      if (value == null) {
        continue;
      }
      for (String part : value.split(",")) {
        String id = part.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
          continue;
        }
        if (!KNOWN.contains(id)) {
          throw new GitAccessException(
              "Unknown --policy value: "
                  + part.trim()
                  + ". Known policies: "
                  + String.join(", ", KNOWN.stream().sorted().toList()));
        }
        enabled.add(id);
      }
    }
    return enabled;
  }

  public static PolicyResult evaluate(
      Collection<String> enabled, AnalysisResult analysis, List<ChangedFile> changedFiles) {
    Set<String> rules = enabled == null ? Set.of() : Set.copyOf(enabled);
    if (rules.isEmpty()) {
      return PolicyResult.none();
    }
    List<String> violations = new ArrayList<>();
    if (rules.contains(BREAKING_PUBLIC_JAVA_API)) {
      violations.addAll(breakingPublicApi(analysis, true));
    }
    if (rules.contains(BREAKING_PUBLIC_EXPORT)) {
      violations.addAll(breakingPublicApi(analysis, false));
    }
    if (rules.contains(BREAKING_PUBLIC_PYTHON)) {
      violations.addAll(breakingPublicPython(analysis));
    }
    if (rules.contains(BREAKING_PUBLIC_PHP)) {
      violations.addAll(breakingPublicPhp(analysis));
    }
    if (rules.contains(ANALYSIS_UNAVAILABLE)) {
      violations.addAll(analysisUnavailable(analysis, changedFiles));
    }
    return new PolicyResult(!violations.isEmpty(), List.copyOf(violations));
  }

  private static List<String> breakingPublicApi(AnalysisResult analysis, boolean java) {
    String rule = java ? BREAKING_PUBLIC_JAVA_API : BREAKING_PUBLIC_EXPORT;
    List<String> violations = new ArrayList<>();
    for (ChangedSymbol symbol : analysis.changedSymbols()) {
      if (!symbol.publiclyVisible() || symbol.changeType() != ChangeType.DELETE) {
        continue;
      }
      String path = symbol.location().path();
      if (isTestPath(path)) {
        continue;
      }
      if (java != path.endsWith(".java")) {
        continue;
      }
      if (!java && !new ChangedFile(path, ChangeType.MODIFY, null).isEcmaSource()) {
        continue;
      }
      String label =
          "type".equals(symbol.kind()) || symbol.owner().isEmpty()
              ? symbol.name()
              : symbol.owner() + "#" + symbol.name();
      violations.add(
          rule + ": public " + symbol.kind() + " " + label + " was removed from " + path);
    }
    return violations;
  }

  private static List<String> breakingPublicPython(AnalysisResult analysis) {
    List<String> violations = new ArrayList<>();
    for (ChangedSymbol symbol : analysis.changedSymbols()) {
      if (!symbol.publiclyVisible() || symbol.changeType() != ChangeType.DELETE) {
        continue;
      }
      String path = symbol.location().path();
      if (isTestPath(path) || !new ChangedFile(path, ChangeType.MODIFY, null).isPythonSource()) {
        continue;
      }
      String label =
          "type".equals(symbol.kind()) || symbol.owner().isEmpty()
              ? symbol.name()
              : symbol.owner() + "#" + symbol.name();
      violations.add(
          BREAKING_PUBLIC_PYTHON
              + ": public "
              + symbol.kind()
              + " "
              + label
              + " was removed from "
              + path);
    }
    return violations;
  }

  private static List<String> breakingPublicPhp(AnalysisResult analysis) {
    List<String> violations = new ArrayList<>();
    for (ChangedSymbol symbol : analysis.changedSymbols()) {
      if (!symbol.publiclyVisible() || symbol.changeType() != ChangeType.DELETE) {
        continue;
      }
      String path = symbol.location().path();
      if (isTestPath(path) || !new ChangedFile(path, ChangeType.MODIFY, null).isPhpSource()) {
        continue;
      }
      String label =
          "type".equals(symbol.kind()) || symbol.owner().isEmpty()
              ? symbol.name()
              : symbol.owner() + "#" + symbol.name();
      violations.add(
          BREAKING_PUBLIC_PHP
              + ": public "
              + symbol.kind()
              + " "
              + label
              + " was removed from "
              + path);
    }
    return violations;
  }

  private static List<String> analysisUnavailable(
      AnalysisResult analysis, List<ChangedFile> changedFiles) {
    List<String> violations = new ArrayList<>();
    for (ChangedFile file : changedFiles) {
      if (!file.isJavaSource()
          && !file.isEcmaSource()
          && !file.isPythonSource()
          && !file.isPhpSource()) {
        continue;
      }
      boolean parseFailed =
          analysis.limitations().stream()
              .anyMatch(
                  limitation ->
                      (limitation.code().endsWith("_PARSE_FAILED"))
                          && limitation.message().contains(file.path()));
      if (parseFailed) {
        violations.add(
            ANALYSIS_UNAVAILABLE + ": could not parse changed source file " + file.path());
      }
    }
    return violations;
  }

  private static boolean isTestPath(String path) {
    String n = path.replace('\\', '/').toLowerCase();
    String file = n.substring(n.lastIndexOf('/') + 1);
    return n.contains("/test/")
        || n.contains("/tests/")
        || n.contains("/src/test")
        || file.startsWith("test_")
        || file.endsWith("_test.py")
        || file.endsWith("test.php");
  }
}
