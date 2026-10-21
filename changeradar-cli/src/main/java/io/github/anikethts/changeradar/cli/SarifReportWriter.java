package io.github.anikethts.changeradar.cli;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.Report;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SarifReportWriter {

  private SarifReportWriter() {}

  static void writeSarif(ObjectMapper mapper, Report report, Path file) throws IOException {
    Map<String, SarifRule> rules = new LinkedHashMap<>();
    List<SarifResult> results = new ArrayList<>();
    for (ImpactFinding finding : report.findings()) {
      rules.putIfAbsent(
          finding.type(),
          new SarifRule(finding.type(), new SarifMessage(finding.type() + " impact finding")));
      String level = "note";
      results.add(
          new SarifResult(
              finding.type(),
              level,
              new SarifMessage(finding.summary() + " [" + finding.confidence() + "]"),
              List.of(
                  new SarifResultLocation(
                      new SarifPhysicalLocation(
                          new SarifArtifact(finding.location().path().replace('\\', '/')),
                          new SarifRegion(finding.location().startLine())))),
              Map.of("changeradar/id", finding.id()),
              Map.of("id", finding.id(), "confidence", finding.confidence().name())));
    }
    SarifLog log =
        new SarifLog(
            "2.1.0",
            "https://json.schemastore.org/sarif-2.1.0.json",
            List.of(
                new SarifRun(
                    new SarifTool(
                        new SarifDriver(
                            "ChangeRadar",
                            report.toolVersion(),
                            "https://github.com/AnikethTS/ChangeRadar",
                            List.copyOf(rules.values()))),
                    results)));
    mapper.writeValue(file.toFile(), log);
  }

  record SarifLog(String version, @JsonProperty("$schema") String schemaUrl, List<SarifRun> runs) {}

  record SarifRun(SarifTool tool, List<SarifResult> results) {}

  record SarifTool(SarifDriver driver) {}

  record SarifDriver(String name, String version, String informationUri, List<SarifRule> rules) {}

  record SarifRule(String id, SarifMessage shortDescription) {}

  record SarifResult(
      String ruleId,
      String level,
      SarifMessage message,
      List<SarifResultLocation> locations,
      Map<String, String> partialFingerprints,
      Map<String, String> properties) {}

  record SarifMessage(String text) {}

  record SarifResultLocation(SarifPhysicalLocation physicalLocation) {}

  record SarifPhysicalLocation(SarifArtifact artifactLocation, SarifRegion region) {}

  record SarifArtifact(String uri) {}

  record SarifRegion(int startLine) {}
}
