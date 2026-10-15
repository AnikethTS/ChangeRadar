package io.github.anikethts.changeradar.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Report(
    String schemaVersion,
    String toolVersion,
    Instant generatedAt,
    AnalysisRequest request,
    List<ChangedFile> changedFiles,
    List<Limitation> limitations,
    PolicyResult policy) {

  public static final String SCHEMA_VERSION = "1.0.0";

  public Report {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(toolVersion, "toolVersion");
    Objects.requireNonNull(generatedAt, "generatedAt");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(changedFiles, "changedFiles");
    Objects.requireNonNull(limitations, "limitations");
    Objects.requireNonNull(policy, "policy");
    changedFiles = List.copyOf(changedFiles);
    limitations = List.copyOf(limitations);
  }
}
