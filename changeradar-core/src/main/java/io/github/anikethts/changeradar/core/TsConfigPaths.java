package io.github.anikethts.changeradar.core;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Nearest {@code tsconfig.json} / {@code jsconfig.json} {@code compilerOptions.paths} and {@code
 * baseUrl}, including relative {@code extends}. This is not tsc: package names, project references,
 * and multi-star patterns are ignored. A child {@code baseUrl} does not rebase inherited path
 * targets.
 */
public final class TsConfigPaths {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
          .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
          .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
          .build();

  private TsConfigPaths() {}

  public static boolean isConfigPath(String path) {
    String unix = path.replace('\\', '/');
    int slash = unix.lastIndexOf('/');
    String name = slash < 0 ? unix : unix.substring(slash + 1);
    return "tsconfig.json".equals(name)
        || "jsconfig.json".equals(name)
        || (name.startsWith("tsconfig.") && name.endsWith(".json"));
  }

  public static PathMap index(Map<String, String> headFiles) {
    Map<String, Raw> raws = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : headFiles.entrySet()) {
      if (!isConfigPath(entry.getKey())) {
        continue;
      }
      parse(entry.getKey(), entry.getValue()).ifPresent(raw -> raws.put(entry.getKey(), raw));
    }
    Map<String, Config> configs = new LinkedHashMap<>();
    for (Raw raw : raws.values()) {
      configs.put(raw.path(), resolve(raw, raws, new LinkedHashSet<>()));
    }
    return new PathMap(configs);
  }

  static Optional<Raw> parse(String path, String source) {
    if (source == null || source.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode root = MAPPER.readTree(source);
      JsonNode opts = root.path("compilerOptions");
      boolean declaredBase = opts.hasNonNull("baseUrl") && opts.get("baseUrl").isTextual();
      String baseUrl = declaredBase ? opts.get("baseUrl").asText() : ".";
      JsonNode paths = opts.get("paths");
      boolean declaredPaths = paths != null && paths.isObject();
      List<Mapping> mappings = new ArrayList<>();
      if (declaredPaths) {
        paths
            .properties()
            .forEach(
                field -> {
                  List<String> targets = new ArrayList<>();
                  if (field.getValue().isArray()) {
                    field.getValue().forEach(node -> targets.add(node.asText()));
                  } else if (field.getValue().isTextual()) {
                    targets.add(field.getValue().asText());
                  }
                  String pattern = field.getKey();
                  if (pattern.isBlank() || targets.isEmpty()) {
                    return;
                  }
                  if (pattern.indexOf('*') != pattern.lastIndexOf('*')) {
                    return;
                  }
                  mappings.add(new Mapping(pattern, List.copyOf(targets)));
                });
      }
      return Optional.of(
          new Raw(
              path, declaredBase, baseUrl, declaredPaths, List.copyOf(mappings), extendsOf(root)));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private static List<String> extendsOf(JsonNode root) {
    JsonNode value = root.get("extends");
    if (value == null || value.isNull()) {
      return List.of();
    }
    List<String> specs = new ArrayList<>();
    if (value.isTextual()) {
      specs.add(value.asText());
    } else if (value.isArray()) {
      value.forEach(
          node -> {
            if (node.isTextual()) {
              specs.add(node.asText());
            }
          });
    }
    return List.copyOf(specs);
  }

  private static Config resolve(Raw raw, Map<String, Raw> raws, Set<String> stack) {
    if (!stack.add(raw.path())) {
      return own(raw, null);
    }
    Config parent = null;
    for (String spec : raw.extendsSpecs()) {
      String path = resolveExtends(raw.path(), spec, raws.keySet());
      Raw base = path == null ? null : raws.get(path);
      if (base == null) {
        continue;
      }
      Config resolved = resolve(base, raws, stack);
      parent = parent == null ? resolved : overlay(parent, resolved);
    }
    stack.remove(raw.path());
    return own(raw, parent);
  }

  /** Later config wins. Inherited path targets stay where the file that declared them put them. */
  private static Config overlay(Config base, Config top) {
    String resolvedBase = top.declaredBase() ? top.resolvedBase() : base.resolvedBase();
    List<Mapping> mappings = top.declaredPaths() ? top.mappings() : base.mappings();
    return new Config(
        top.path(),
        resolvedBase,
        mappings,
        top.declaredBase() || base.declaredBase(),
        top.declaredPaths());
  }

  private static Config own(Raw raw, Config parent) {
    String resolvedBase =
        raw.declaredBase()
            ? Config.join(configDir(raw.path()), raw.baseUrl())
            : parent != null && parent.declaredBase()
                ? parent.resolvedBase()
                : Config.join(configDir(raw.path()), ".");
    List<Mapping> mappings;
    if (raw.declaredPaths()) {
      String anchor = raw.declaredBase() || parent == null ? resolvedBase : parent.resolvedBase();
      mappings = anchor(raw.mappings(), anchor);
    } else if (parent != null) {
      mappings = parent.mappings();
    } else {
      mappings = List.of();
    }
    return new Config(
        raw.path(),
        resolvedBase,
        mappings,
        raw.declaredBase() || (parent != null && parent.declaredBase()),
        raw.declaredPaths());
  }

  private static String resolveExtends(String from, String spec, Set<String> known) {
    if (spec == null || spec.isBlank()) {
      return null;
    }
    String unix = spec.trim().replace('\\', '/');
    if (unix.contains("://") || unix.startsWith("@") || !unix.startsWith(".")) {
      return null;
    }
    String dir = configDir(from);
    String joined = SourcePaths.normalize(dir.isEmpty() ? unix : dir + "/" + unix);
    if (known.contains(joined)) {
      return joined;
    }
    if (!joined.endsWith(".json") && known.contains(joined + ".json")) {
      return joined + ".json";
    }
    return null;
  }

  private static List<Mapping> anchor(List<Mapping> mappings, String base) {
    List<Mapping> anchored = new ArrayList<>();
    for (Mapping mapping : mappings) {
      List<String> targets = new ArrayList<>();
      for (String target : mapping.targets()) {
        targets.add(Config.join(base, target.replace('\\', '/')));
      }
      anchored.add(new Mapping(mapping.pattern(), List.copyOf(targets)));
    }
    anchored.sort(
        Comparator.comparingInt((Mapping mapping) -> mapping.pattern().length()).reversed());
    return List.copyOf(anchored);
  }

  private static String configDir(String path) {
    String unix = path.replace('\\', '/');
    int slash = unix.lastIndexOf('/');
    return slash < 0 ? "" : unix.substring(0, slash);
  }

  public record PathMap(Map<String, Config> configs) {
    public PathMap {
      configs = Map.copyOf(configs);
    }

    public static final PathMap EMPTY = new PathMap(Map.of());

    public boolean hasMappings() {
      return configs.values().stream().anyMatch(config -> !config.mappings().isEmpty());
    }

    public List<String> expand(String fromFile, String specifier) {
      Config config = nearest(fromFile);
      if (config == null) {
        return List.of();
      }
      return config.expand(specifier);
    }

    Config nearest(String fromFile) {
      String unix = fromFile.replace('\\', '/');
      int slash = unix.lastIndexOf('/');
      String dir = slash < 0 ? "" : unix.substring(0, slash);
      while (true) {
        Config ts = configs.get(dir.isEmpty() ? "tsconfig.json" : dir + "/tsconfig.json");
        if (ts != null) {
          return ts;
        }
        Config js = configs.get(dir.isEmpty() ? "jsconfig.json" : dir + "/jsconfig.json");
        if (js != null) {
          return js;
        }
        if (dir.isEmpty()) {
          return null;
        }
        int next = dir.lastIndexOf('/');
        dir = next < 0 ? "" : dir.substring(0, next);
      }
    }
  }

  record Config(
      String path,
      String resolvedBase,
      List<Mapping> mappings,
      boolean declaredBase,
      boolean declaredPaths) {
    Config {
      mappings = List.copyOf(mappings);
    }

    List<String> expand(String specifier) {
      if (specifier == null || specifier.isBlank() || specifier.startsWith(".")) {
        return List.of();
      }
      String spec = specifier.replace('\\', '/');
      for (Mapping mapping : mappings) {
        Optional<String> rest = mapping.match(spec);
        if (rest.isEmpty()) {
          continue;
        }
        List<String> stems = new ArrayList<>();
        for (String target : mapping.targets()) {
          stems.add(fill(target.replace('\\', '/'), rest.get()));
        }
        return stems;
      }
      String root = resolvedBase == null || resolvedBase.isBlank() ? "." : resolvedBase;
      return List.of(join(root, spec));
    }

    /** Join relative path segments without turning a repo-root config into `/stem`. */
    private static String join(String left, String right) {
      StringBuilder out = new StringBuilder();
      for (String part : List.of(left, right)) {
        if (part == null) {
          continue;
        }
        String piece = part.replace('\\', '/');
        while (piece.startsWith("./")) {
          piece = piece.substring(2);
        }
        if (piece.isEmpty() || ".".equals(piece)) {
          continue;
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) != '/') {
          out.append('/');
        }
        if (out.length() > 0) {
          while (piece.startsWith("/")) {
            piece = piece.substring(1);
          }
        }
        out.append(piece);
      }
      return SourcePaths.normalize(out.toString());
    }

    private static String fill(String target, String rest) {
      int star = target.indexOf('*');
      if (star < 0) {
        return rest.isEmpty() ? target : target + "/" + rest;
      }
      return target.substring(0, star) + rest + target.substring(star + 1);
    }
  }

  record Raw(
      String path,
      boolean declaredBase,
      String baseUrl,
      boolean declaredPaths,
      List<Mapping> mappings,
      List<String> extendsSpecs) {
    Raw {
      mappings = List.copyOf(mappings);
      extendsSpecs = List.copyOf(extendsSpecs);
    }
  }

  record Mapping(String pattern, List<String> targets) {
    Optional<String> match(String specifier) {
      int star = pattern.indexOf('*');
      if (star < 0) {
        return specifier.equals(pattern) ? Optional.of("") : Optional.empty();
      }
      String prefix = pattern.substring(0, star);
      String suffix = pattern.substring(star + 1);
      if (!specifier.startsWith(prefix) || !specifier.endsWith(suffix)) {
        return Optional.empty();
      }
      int end = specifier.length() - suffix.length();
      if (end < prefix.length()) {
        return Optional.empty();
      }
      return Optional.of(specifier.substring(prefix.length(), end));
    }
  }
}
