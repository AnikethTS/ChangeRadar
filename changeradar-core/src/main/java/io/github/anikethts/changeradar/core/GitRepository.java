package io.github.anikethts.changeradar.core;

import java.nio.file.Path;
import java.util.List;

/** Git adapter. Implementations must not execute the analyzed project's build scripts. */
public interface GitRepository extends AutoCloseable {

  Path root();

  String resolve(String revision);

  List<ChangedFile> diff(String base, String head);

  @Override
  void close();
}
