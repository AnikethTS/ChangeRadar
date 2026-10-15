package io.github.anikethts.changeradar.core;

import java.util.Objects;

public record TestRecommendation(String name, String path, Confidence confidence, String reason) {

  public TestRecommendation {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(confidence, "confidence");
    Objects.requireNonNull(reason, "reason");
  }
}
