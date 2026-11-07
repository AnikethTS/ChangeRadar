package io.github.anikethts.changeradar.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * PSR-4 and PSR-0 prefixes from {@code composer.json} {@code autoload} and {@code autoload-dev}.
 * This is not Composer: classmap, files, and installed vendor packages are not loaded.
 */
public final class ComposerAutoload {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ComposerAutoload() {}

  public static boolean isComposerPath(String path) {
    String unix = path.replace('\\', '/');
    int slash = unix.lastIndexOf('/');
    String name = slash < 0 ? unix : unix.substring(slash + 1);
    return "composer.json".equals(name);
  }

  public static PrefixMap index(Map<String, String> headFiles) {
    List<Prefix> prefixes = new ArrayList<>();
    for (Map.Entry<String, String> entry : headFiles.entrySet()) {
      if (!isComposerPath(entry.getKey())) {
        continue;
      }
      prefixes.addAll(parse(entry.getKey(), entry.getValue()));
    }
    prefixes.sort(
        Comparator.comparingInt((Prefix prefix) -> prefix.namespace().length()).reversed());
    return new PrefixMap(List.copyOf(prefixes));
  }

  static List<Prefix> parse(String path, String source) {
    if (source == null || source.isBlank()) {
      return List.of();
    }
    try {
      JsonNode root = MAPPER.readTree(source);
      String unix = path.replace('\\', '/');
      int slash = unix.lastIndexOf('/');
      String baseDir = slash < 0 ? "" : unix.substring(0, slash);
      List<Prefix> prefixes = new ArrayList<>();
      readAutoload(root.path("autoload"), baseDir, prefixes);
      readAutoload(root.path("autoload-dev"), baseDir, prefixes);
      return prefixes;
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static void readAutoload(JsonNode node, String baseDir, List<Prefix> prefixes) {
    if (node == null || !node.isObject()) {
      return;
    }
    readMap(node.get("psr-4"), baseDir, false, prefixes);
    readMap(node.get("psr-0"), baseDir, true, prefixes);
  }

  private static void readMap(JsonNode node, String baseDir, boolean psr0, List<Prefix> prefixes) {
    if (node == null || !node.isObject()) {
      return;
    }
    node.properties()
        .forEach(
            field -> {
              List<String> targets = new ArrayList<>();
              if (field.getValue().isArray()) {
                field.getValue().forEach(item -> addTarget(targets, item));
              } else {
                addTarget(targets, field.getValue());
              }
              String namespace = normalizeNamespace(field.getKey());
              if (namespace == null || targets.isEmpty()) {
                return;
              }
              if (!psr0 && !namespace.isEmpty() && !namespace.endsWith("\\")) {
                namespace = namespace + "\\";
              }
              prefixes.add(new Prefix(baseDir, namespace, List.copyOf(targets), psr0));
            });
  }

  private static void addTarget(List<String> targets, JsonNode node) {
    if (node == null || !node.isTextual()) {
      return;
    }
    String target = node.asText().trim().replace('\\', '/');
    if (target.isEmpty()
        || target.startsWith("/")
        || target.contains("://")
        || target.contains("..")) {
      return;
    }
    targets.add(target);
  }

  /**
   * @return namespace with a trailing separator, or {@code null} when the key is not a prefix
   */
  private static String normalizeNamespace(String raw) {
    if (raw == null) {
      return null;
    }
    String namespace = raw.trim().replace('/', '\\');
    if (namespace.startsWith("\\")) {
      namespace = namespace.substring(1);
    }
    if (namespace.contains("{") || namespace.contains("*")) {
      return null;
    }
    return namespace;
  }

  public static final class PrefixMap {
    public static final PrefixMap EMPTY = new PrefixMap(List.of());

    private final List<Prefix> prefixes;

    PrefixMap(List<Prefix> prefixes) {
      this.prefixes = prefixes;
    }

    public boolean hasPrefixes() {
      return !prefixes.isEmpty();
    }

    /**
     * Candidate repo-relative stems for a PHP {@code use} name. Longest prefix first. A path-like
     * specifier is left alone.
     */
    public List<String> expand(String specifier) {
      if (specifier == null || specifier.isBlank() || prefixes.isEmpty()) {
        return List.of();
      }
      String raw = specifier.trim();
      if (raw.contains("/")
          || raw.contains("{")
          || raw.endsWith(".php")
          || raw.startsWith(".")
          || raw.contains("..")) {
        return List.of();
      }
      String fqn = raw.replace('/', '\\');
      if (fqn.startsWith("\\")) {
        fqn = fqn.substring(1);
      }
      if (fqn.isEmpty()) {
        return List.of();
      }
      List<String> stems = new ArrayList<>();
      for (Prefix prefix : prefixes) {
        if (!matches(fqn, prefix.namespace())) {
          continue;
        }
        String relative =
            prefix.psr0()
                ? fqn.replace('\\', '/').replace('_', '/')
                : remainder(fqn, prefix.namespace()).replace('\\', '/');
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains("..")) {
          continue;
        }
        for (String target : prefix.targets()) {
          String dir = join(prefix.baseDir(), target);
          if (dir == null) {
            continue;
          }
          String stem = dir.isEmpty() ? relative : dir + "/" + relative;
          String normalized = SourcePaths.normalize(stem);
          if (!normalized.isEmpty() && !normalized.startsWith("/") && !stems.contains(normalized)) {
            stems.add(normalized);
          }
        }
      }
      return stems;
    }

    private static boolean matches(String fqn, String namespace) {
      if (namespace.isEmpty()) {
        return true;
      }
      return fqn.startsWith(namespace) || (fqn + "\\").equals(namespace);
    }

    private static String remainder(String fqn, String namespace) {
      if (namespace.isEmpty()) {
        return fqn;
      }
      if ((fqn + "\\").equals(namespace)) {
        return "";
      }
      return fqn.substring(namespace.length());
    }

    private static String join(String baseDir, String target) {
      String dir = ".".equals(target) ? "" : target;
      if (dir.endsWith("/")) {
        dir = dir.substring(0, dir.length() - 1);
      }
      String combined;
      if (baseDir.isEmpty()) {
        combined = dir;
      } else if (dir.isEmpty()) {
        combined = baseDir;
      } else {
        combined = baseDir + "/" + dir;
      }
      String normalized = SourcePaths.normalize(combined);
      if (normalized.startsWith("/") || normalized.contains("..")) {
        return null;
      }
      return normalized;
    }
  }

  record Prefix(String baseDir, String namespace, List<String> targets, boolean psr0) {}
}
