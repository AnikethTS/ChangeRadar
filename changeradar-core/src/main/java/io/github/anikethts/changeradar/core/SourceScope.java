package io.github.anikethts.changeradar.core;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Limits HEAD sources fed to parsers: changed files, same-directory Java neighbors, and files whose
 * import/require/{@code use} specifiers resolve to a changed path.
 */
public final class SourceScope {

  private static final Pattern QUOTED =
      Pattern.compile("(?:from|import|require|include|use)\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
  private static final Pattern FROM_MODULE =
      Pattern.compile("(?m)^\\s*from\\s+([\\w.]+)\\s+import");
  private static final Pattern IMPORT_MODULE = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)");
  private static final Pattern USE =
      Pattern.compile("(?m)^\\s*use\\s+(?:function\\s+|const\\s+)?([^;]+);");
  private static final List<String> SUFFIXES =
      List.of(
          ".java", ".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs", ".py", ".pyi",
          ".php");

  private SourceScope() {}

  public static Map<String, String> subset(
      List<ChangedFile> changedFiles, Map<String, String> headSources) {
    Set<String> seeds = new LinkedHashSet<>();
    for (ChangedFile file : changedFiles) {
      if (headSources.containsKey(file.path()) || isSourcePath(file.path())) {
        seeds.add(file.path());
      }
      if (file.previousPath() != null) {
        seeds.add(file.previousPath());
      }
    }
    Set<String> keep = new LinkedHashSet<>(seeds);
    Set<String> seedDirs = new LinkedHashSet<>();
    for (String seed : seeds) {
      seedDirs.add(parentDir(seed));
    }
    TsConfigPaths.PathMap pathMap = TsConfigPaths.index(headSources);
    for (Map.Entry<String, String> entry : headSources.entrySet()) {
      String path = entry.getKey();
      if (keep.contains(path)) {
        continue;
      }
      if (TsConfigPaths.isConfigPath(path)) {
        keep.add(path);
        continue;
      }
      if (path.endsWith(".java") && seedDirs.contains(parentDir(path))) {
        keep.add(path);
        continue;
      }
      if (importsSeed(path, entry.getValue(), seeds, headSources.keySet(), pathMap)) {
        keep.add(path);
      }
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (String path : keep) {
      String source = headSources.get(path);
      if (source != null) {
        out.put(path, source);
      }
    }
    return out;
  }

  private static boolean importsSeed(
      String fromFile,
      String source,
      Set<String> seeds,
      Set<String> known,
      TsConfigPaths.PathMap pathMap) {
    Matcher quoted = QUOTED.matcher(source);
    while (quoted.find()) {
      if (resolvesToSeed(fromFile, quoted.group(1), seeds, known, pathMap)) {
        return true;
      }
    }
    Matcher from = FROM_MODULE.matcher(source);
    while (from.find()) {
      if (resolvesToSeed(fromFile, from.group(1), seeds, known, pathMap)) {
        return true;
      }
    }
    Matcher imp = IMPORT_MODULE.matcher(source);
    while (imp.find()) {
      if (resolvesToSeed(fromFile, imp.group(1), seeds, known, pathMap)) {
        return true;
      }
    }
    Matcher use = USE.matcher(source);
    while (use.find()) {
      String spec = use.group(1).trim();
      int as = spec.toLowerCase().lastIndexOf(" as ");
      if (as >= 0) {
        spec = spec.substring(0, as).trim();
      }
      if (resolvesToSeed(fromFile, spec, seeds, known, pathMap)) {
        return true;
      }
    }
    return false;
  }

  private static boolean resolvesToSeed(
      String fromFile,
      String spec,
      Set<String> seeds,
      Set<String> known,
      TsConfigPaths.PathMap pathMap) {
    Optional<String> resolved =
        SourcePaths.resolveExisting(fromFile, spec, known, SUFFIXES, pathMap);
    return resolved.filter(seeds::contains).isPresent();
  }

  private static String parentDir(String path) {
    String unix = path.replace('\\', '/');
    int slash = unix.lastIndexOf('/');
    return slash < 0 ? "" : unix.substring(0, slash);
  }

  private static boolean isSourcePath(String path) {
    ChangedFile file = new ChangedFile(path, ChangeType.MODIFY, null);
    return file.isJavaSource()
        || file.isEcmaSource()
        || file.isPythonSource()
        || file.isPhpSource();
  }
}
