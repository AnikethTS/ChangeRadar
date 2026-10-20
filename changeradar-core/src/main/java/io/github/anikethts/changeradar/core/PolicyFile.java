package io.github.anikethts.changeradar.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Minimal YAML subset for committed policy files:
 *
 * <pre>
 * policies:
 *   - breaking-public-java-api
 * baseline: .changeradar/baseline.txt
 * </pre>
 */
public final class PolicyFile {

  public record Document(Set<String> policies, String baseline) {
    public Document {
      Objects.requireNonNull(policies, "policies");
      policies = Set.copyOf(policies);
    }
  }

  private PolicyFile() {}

  public static Document load(Path file) {
    Objects.requireNonNull(file, "file");
    String text;
    try {
      text = Files.readString(file);
    } catch (IOException e) {
      throw new GitAccessException("Cannot read --policy-file " + file + ": " + e.getMessage(), e);
    }
    List<String> names = new ArrayList<>();
    String baseline = null;
    boolean inPolicies = false;
    for (String raw : text.split("\n")) {
      String line = raw.replace("\t", "  ");
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      if (trimmed.startsWith("policies:")) {
        inPolicies = true;
        String rest = trimmed.substring("policies:".length()).trim();
        if (rest.startsWith("[") && rest.endsWith("]")) {
          for (String part : rest.substring(1, rest.length() - 1).split(",")) {
            addName(names, part);
          }
          inPolicies = false;
        }
        continue;
      }
      if (trimmed.startsWith("baseline:")) {
        inPolicies = false;
        baseline = unquote(trimmed.substring("baseline:".length()).trim());
        if (baseline.isEmpty()) {
          baseline = null;
        }
        continue;
      }
      if (inPolicies && trimmed.startsWith("-")) {
        addName(names, trimmed.substring(1));
        continue;
      }
      inPolicies = false;
    }
    return new Document(new LinkedHashSet<>(PolicyEngine.parseEnabled(names)), baseline);
  }

  public static Set<String> readBaseline(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return Set.of();
    }
    try {
      Set<String> keys = new LinkedHashSet<>();
      for (String line : Files.readAllLines(file)) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
          keys.add(trimmed);
        }
      }
      return keys;
    } catch (IOException e) {
      throw new GitAccessException(
          "Cannot read policy baseline " + file + ": " + e.getMessage(), e);
    }
  }

  public static void writeBaseline(Path file, List<String> violations) {
    Objects.requireNonNull(file, "file");
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      StringBuilder out = new StringBuilder();
      out.append("# ChangeRadar policy baseline. One violation key per line.\n");
      for (String violation : violations) {
        out.append(violation).append('\n');
      }
      Files.writeString(file, out.toString());
    } catch (IOException e) {
      throw new GitAccessException(
          "Cannot write policy baseline " + file + ": " + e.getMessage(), e);
    }
  }

  private static void addName(List<String> names, String raw) {
    String name = unquote(raw.trim()).toLowerCase(Locale.ROOT);
    if (!name.isEmpty()) {
      names.add(name);
    }
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
