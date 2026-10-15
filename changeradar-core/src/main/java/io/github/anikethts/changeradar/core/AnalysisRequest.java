package io.github.anikethts.changeradar.core;

import java.nio.file.Path;
import java.util.Objects;

public record AnalysisRequest(Path repository, String base, String head) {

  public AnalysisRequest {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(head, "head");
  }
}
