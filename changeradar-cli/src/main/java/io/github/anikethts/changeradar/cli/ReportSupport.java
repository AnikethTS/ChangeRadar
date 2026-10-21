package io.github.anikethts.changeradar.cli;

import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.ImpactFinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReportSupport {

  private ReportSupport() {}

  static Map<String, List<ImpactFinding>> groupedFindings(List<ImpactFinding> findings) {
    Map<String, List<ImpactFinding>> grouped = new LinkedHashMap<>();
    List<ImpactFinding> sorted = new ArrayList<>(findings);
    sorted.sort(
        Comparator.comparing((ImpactFinding f) -> f.location().path())
            .thenComparing(f -> f.location().startLine())
            .thenComparing(ImpactFinding::id));
    for (ImpactFinding finding : sorted) {
      grouped.computeIfAbsent(finding.location().path(), key -> new ArrayList<>()).add(finding);
    }
    return grouped;
  }

  static String displayName(ChangedSymbol symbol) {
    if ("type".equals(symbol.kind()) || symbol.owner().isEmpty()) {
      return symbol.name();
    }
    if (!symbol.signature().isEmpty()) {
      return symbol.methodKey();
    }
    return symbol.owner() + "#" + symbol.name();
  }
}
