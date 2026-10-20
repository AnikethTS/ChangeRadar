package io.github.anikethts.changeradar.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Analyzer knobs that do not belong on Git state. {@code classpath} is already-built jars, never a
 * command to run the target build.
 */
public record AnalysisOptions(List<Path> classpath, boolean scopedSources) {

  public AnalysisOptions {
    Objects.requireNonNull(classpath, "classpath");
    classpath = List.copyOf(classpath);
  }

  public static AnalysisOptions defaults() {
    return new AnalysisOptions(List.of(), true);
  }
}
