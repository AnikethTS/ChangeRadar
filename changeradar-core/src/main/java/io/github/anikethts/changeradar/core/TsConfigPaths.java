package io.github.anikethts.changeradar.core;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nearest {@code tsconfig.json} / {@code jsconfig.json} {@code compilerOptions.paths} and {@code
 * baseUrl}. This is not tsc: {@code extends}, project references, and multi-star patterns are
 * ignored.
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
    Map<String, Config> configs = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : headFiles.entrySet()) {
      if (!isConfigPath(entry.getKey())) {
        continue;
      }
      parse(entry.getKey(), entry.getValue())
          .ifPresent(config -> configs.put(entry.getKey(), config));
    }
    return new PathMap(configs);
  }

  static Optional<Config> parse(String path, String source) {
    if (source == null || source.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode root = MAPPER.readTree(source);
      JsonNode opts = root.path("compilerOptions");
      String baseUrl = opts.path("baseUrl").asText(".");
      JsonNode paths = opts.get("paths");
      List<Mapping> mappings = new ArrayList<>();
      if (paths != null && paths.isObject()) {
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
      mappings.sort(Comparator.comparingInt((Mapping m) -> m.pattern.length()).reversed());
      return Optional.of(new Config(path, baseUrl, List.copyOf(mappings)));
    } catch (Exception ignored) {
      return Optional.empty();
    }
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

  record Config(String path, String baseUrl, List<Mapping> mappings) {
    Config {
      mappings = List.copyOf(mappings);
    }

    List<String> expand(String specifier) {
      if (specifier == null || specifier.isBlank() || specifier.startsWith(".")) {
        return List.of();
      }
      String spec = specifier.replace('\\', '/');
      String root = join(configDir(), baseUrl == null || baseUrl.isBlank() ? "." : baseUrl);
      for (Mapping mapping : mappings) {
        Optional<String> rest = mapping.match(spec);
        if (rest.isEmpty()) {
          continue;
        }
        List<String> stems = new ArrayList<>();
        for (String target : mapping.targets()) {
          stems.add(join(root, fill(target.replace('\\', '/'), rest.get())));
        }
        return stems;
      }
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

    private String configDir() {
      String unix = path.replace('\\', '/');
      int slash = unix.lastIndexOf('/');
      return slash < 0 ? "" : unix.substring(0, slash);
    }

    private static String fill(String target, String rest) {
      int star = target.indexOf('*');
      if (star < 0) {
        return rest.isEmpty() ? target : target + "/" + rest;
      }
      return target.substring(0, star) + rest + target.substring(star + 1);
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
