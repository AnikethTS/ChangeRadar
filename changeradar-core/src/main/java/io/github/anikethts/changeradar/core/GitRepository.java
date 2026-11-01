package io.github.anikethts.changeradar.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Git adapter. Implementations must not execute the analyzed project's build scripts. */
public interface GitRepository extends AutoCloseable {

  Path root();

  String resolve(String revision);

  List<ChangedFile> diff(String base, String head);

  List<String> listPaths(String revision, String suffix);

  Optional<String> readFile(String revision, String path);

  /**
   * Reads matching blobs at {@code revision} in one tree walk. Keys are sorted paths. The predicate
   * sees the Git path and decides whether the blob is loaded.
   */
  Map<String, String> readSources(String revision, Predicate<String> include);

  @Override
  void close();
}
