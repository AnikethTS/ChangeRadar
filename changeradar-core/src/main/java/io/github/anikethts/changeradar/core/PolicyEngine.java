package io.github.anikethts.changeradar.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

  public static final Map<String, String> DESCRIPTIONS = descriptions();

  private PolicyEngine() {}

  private static Map<String, String> descriptions() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put(
        BREAKING_PUBLIC_JAVA_API,
        "Public Java type/method/field was removed, narrowed, or its binary signature changed"
            + " (renames are not removals).");
    map.put(
        BREAKING_PUBLIC_EXPORT,
        "Exported TS/JS symbol was removed or its signature changed (renames are not removals).");
    map.put(
        BREAKING_PUBLIC_PYTHON,
        "Public Python class/function was removed or its signature changed (renames are not"
            + " removals).");
    map.put(
        BREAKING_PUBLIC_PHP,
        "Public PHP class/function was removed or its signature changed (renames are not"
            + " removals).");
    map.put(ANALYSIS_UNAVAILABLE, "A changed source file could not be parsed.");
    return Map.copyOf(map);
  }

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
    return evaluate(enabled, analysis, changedFiles, Set.of(), List.of());
  }

  public static PolicyResult evaluate(
      Collection<String> enabled,
      AnalysisResult analysis,
      List<ChangedFile> changedFiles,
      Set<String> baseline,
      List<ChangedSymbol> signatureChanges) {
    Set<String> rules = enabled == null ? Set.of() : Set.copyOf(enabled);
    if (rules.isEmpty()) {
      return PolicyResult.none();
    }
    Set<String> ignore = baseline == null ? Set.of() : Set.copyOf(baseline);
    List<String> violations = new ArrayList<>();
    if (rules.contains(BREAKING_PUBLIC_JAVA_API)) {
      violations.addAll(breakingPublicApi(analysis, true));
      violations.addAll(signatureViolations(signatureChanges, true, false, false, false));
    }
    if (rules.contains(BREAKING_PUBLIC_EXPORT)) {
      violations.addAll(breakingPublicApi(analysis, false));
      violations.addAll(signatureViolations(signatureChanges, false, true, false, false));
    }
    if (rules.contains(BREAKING_PUBLIC_PYTHON)) {
      violations.addAll(breakingPublicPython(analysis));
      violations.addAll(signatureViolations(signatureChanges, false, false, true, false));
    }
    if (rules.contains(BREAKING_PUBLIC_PHP)) {
      violations.addAll(breakingPublicPhp(analysis));
      violations.addAll(signatureViolations(signatureChanges, false, false, false, true));
    }
    if (rules.contains(ANALYSIS_UNAVAILABLE)) {
      violations.addAll(analysisUnavailable(analysis, changedFiles));
    }
    List<String> fresh = new ArrayList<>();
    for (String violation : violations) {
      if (!ignore.contains(violation)) {
        fresh.add(violation);
      }
    }
    return new PolicyResult(!fresh.isEmpty(), List.copyOf(fresh));
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

  private static List<String> signatureViolations(
      List<ChangedSymbol> signatureChanges,
      boolean java,
      boolean export,
      boolean python,
      boolean php) {
    if (signatureChanges == null || signatureChanges.isEmpty()) {
      return List.of();
    }
    List<String> violations = new ArrayList<>();
    for (ChangedSymbol symbol : signatureChanges) {
      if (!symbol.publiclyVisible() || isTestPath(symbol.location().path())) {
        continue;
      }
      String path = symbol.location().path();
      boolean match =
          java && path.endsWith(".java")
              || export && new ChangedFile(path, ChangeType.MODIFY, null).isEcmaSource()
              || python && new ChangedFile(path, ChangeType.MODIFY, null).isPythonSource()
              || php && new ChangedFile(path, ChangeType.MODIFY, null).isPhpSource();
      if (!match) {
        continue;
      }
      String rule =
          java
              ? BREAKING_PUBLIC_JAVA_API
              : export
                  ? BREAKING_PUBLIC_EXPORT
                  : python ? BREAKING_PUBLIC_PYTHON : BREAKING_PUBLIC_PHP;
      String label =
          "type".equals(symbol.kind()) || symbol.owner().isEmpty()
              ? symbol.name()
              : symbol.owner() + "#" + symbol.name();
      violations.add(
          rule + ": public " + symbol.kind() + " " + label + " signature changed in " + path);
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
