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
  public static final String ANALYSIS_UNAVAILABLE = "analysis-unavailable";

  public static final Set<String> KNOWN = Set.of(BREAKING_PUBLIC_JAVA_API, ANALYSIS_UNAVAILABLE);

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
      violations.addAll(breakingPublicJavaApi(analysis));
    }
    if (rules.contains(ANALYSIS_UNAVAILABLE)) {
      violations.addAll(analysisUnavailable(analysis, changedFiles));
    }
    return new PolicyResult(!violations.isEmpty(), List.copyOf(violations));
  }

  private static List<String> breakingPublicJavaApi(AnalysisResult analysis) {
    List<String> violations = new ArrayList<>();
    for (ChangedSymbol symbol : analysis.changedSymbols()) {
      if (!symbol.publiclyVisible() || symbol.changeType() != ChangeType.DELETE) {
        continue;
      }
      if (isTestPath(symbol.location().path())) {
        continue;
      }
      String label =
          "type".equals(symbol.kind()) || symbol.owner().isEmpty()
              ? symbol.name()
              : symbol.owner() + "#" + symbol.name();
      violations.add(
          BREAKING_PUBLIC_JAVA_API
              + ": public "
              + symbol.kind()
              + " "
              + label
              + " was removed from "
              + symbol.location().path());
    }
    return violations;
  }

  private static List<String> analysisUnavailable(
      AnalysisResult analysis, List<ChangedFile> changedFiles) {
    List<String> violations = new ArrayList<>();
    for (ChangedFile file : changedFiles) {
      if (!file.isJavaSource()) {
        continue;
      }
      boolean parseFailed =
          analysis.limitations().stream()
              .anyMatch(
                  limitation ->
                      "JAVA_PARSE_FAILED".equals(limitation.code())
                          && limitation.message().contains(file.path()));
      if (parseFailed) {
        violations.add(ANALYSIS_UNAVAILABLE + ": could not parse changed Java file " + file.path());
      }
    }
    return violations;
  }

  private static boolean isTestPath(String path) {
    return path.contains("/test/") || path.contains("\\test\\") || path.contains("/src/test");
  }
}
