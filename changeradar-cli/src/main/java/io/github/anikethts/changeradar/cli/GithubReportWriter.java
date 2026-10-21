package io.github.anikethts.changeradar.cli;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.Report;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GithubReportWriter {

  private GithubReportWriter() {}

  static void writeGithub(ObjectMapper mapper, Report report, Path file) throws IOException {
    mapper.writeValue(file.toFile(), githubCheck(report));
  }

  static GithubCheck githubCheck(Report report) {
    List<GithubAnnotation> annotations = new ArrayList<>();
    List<ImpactFinding> ordered = new ArrayList<>(report.findings());
    ordered.sort(
        Comparator.comparing((ImpactFinding f) -> f.confidence() == Confidence.DIRECT ? 0 : 1)
            .thenComparing(f -> f.location().path())
            .thenComparing(f -> f.location().startLine())
            .thenComparing(ImpactFinding::id));
    for (ImpactFinding finding : ordered) {
      if (finding.confidence() == Confidence.HEURISTIC) {
        continue;
      }
      if (annotations.size() >= 50) {
        break;
      }
      int line = Math.max(1, finding.location().startLine());
      annotations.add(
          new GithubAnnotation(
              finding.location().path().replace('\\', '/'),
              line,
              line,
              "notice",
              finding.type() + " [" + finding.confidence() + "]",
              finding.summary() + " (`" + finding.id() + "`)"));
    }
    return new GithubCheck(
        "ChangeRadar impact", TextReportWriter.compactMarkdown(report), annotations);
  }

  record GithubCheck(String title, String summary, List<GithubAnnotation> annotations) {}

  record GithubAnnotation(
      String path,
      @JsonProperty("start_line") int startLine,
      @JsonProperty("end_line") int endLine,
      @JsonProperty("annotation_level") String annotationLevel,
      String title,
      String message) {}
}
