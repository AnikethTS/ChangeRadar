package io.github.anikethts.changeradar.core;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Relative import/require path math. Language analyzers resolve specifiers against HEAD paths. */
public final class SourcePaths {

  private SourcePaths() {}

  public static boolean isTestSource(String path) {
    if (path == null || path.isBlank()) {
      return false;
    }
    String n = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    String file = n.substring(n.lastIndexOf('/') + 1);
    return n.contains("/test/")
        || n.contains("/tests/")
        || n.contains("/src/test")
        || file.startsWith("test_")
        || file.endsWith("_test.py")
        || file.endsWith("test.php");
  }

  public static String normalize(String path) {
    String unix = path.replace('\\', '/');
    Deque<String> parts = new ArrayDeque<>();
    boolean absolute = unix.startsWith("/");
    for (String part : unix.split("/")) {
      if (part.isEmpty() || ".".equals(part)) {
        continue;
      }
      if ("..".equals(part)) {
        if (!parts.isEmpty()) {
          parts.removeLast();
        }
      } else {
        parts.addLast(part);
      }
    }
    String joined = String.join("/", parts);
    if (absolute) {
      return "/" + joined;
    }
    return joined;
  }

  public static Optional<String> resolveExisting(
      String fromFile, String specifier, Collection<String> knownPaths, List<String> suffixes) {
    return resolveExisting(fromFile, specifier, knownPaths, suffixes, TsConfigPaths.PathMap.EMPTY);
  }

  public static Optional<String> resolveExisting(
      String fromFile,
      String specifier,
      Collection<String> knownPaths,
      List<String> suffixes,
      TsConfigPaths.PathMap pathMap) {
    if (specifier == null || specifier.isBlank()) {
      return Optional.empty();
    }
    String spec = specifier.replace('\\', '/');
    if (spec.startsWith(".")) {
      int slash = fromFile.replace('\\', '/').lastIndexOf('/');
      String dir = slash < 0 ? "" : fromFile.substring(0, slash);
      String joined = dir.isEmpty() ? spec : dir + "/" + spec;
      return matchStem(normalize(joined), knownPaths, suffixes);
    }
    if (pathMap != null) {
      for (String stem : pathMap.expand(fromFile, spec)) {
        Optional<String> aliased = matchStem(stem, knownPaths, suffixes);
        if (aliased.isPresent()) {
          return aliased;
        }
      }
    }
    Optional<String> dotted = matchStem(spec.replace('.', '/'), knownPaths, suffixes);
    if (dotted.isPresent()) {
      return dotted;
    }
    return matchStem(spec, knownPaths, suffixes);
  }

  private static Optional<String> matchStem(
      String stem, Collection<String> knownPaths, List<String> suffixes) {
    for (String known : knownPaths) {
      String unix = known.replace('\\', '/');
      if (unix.equals(stem)) {
        return Optional.of(known);
      }
      for (String suffix : suffixes) {
        if (unix.equals(stem + suffix) || unix.equals(stem + "/index" + suffix)) {
          return Optional.of(known);
        }
      }
      int slash = unix.lastIndexOf('/');
      String file = slash < 0 ? unix : unix.substring(slash + 1);
      for (String suffix : suffixes) {
        if (file.equals(stem + suffix) && !stem.contains("/")) {
          return Optional.of(known);
        }
      }
    }
    return Optional.empty();
  }
}
