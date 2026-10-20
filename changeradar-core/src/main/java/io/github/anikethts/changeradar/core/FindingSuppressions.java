package io.github.anikethts.changeradar.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Drops findings whose stable {@link ImpactFinding#id()} is in a committed suppress file. */
public final class FindingSuppressions {

  private FindingSuppressions() {}

  public static List<ImpactFinding> apply(List<ImpactFinding> findings, Set<String> ids) {
    Objects.requireNonNull(findings, "findings");
    if (ids == null || ids.isEmpty()) {
      return List.copyOf(findings);
    }
    List<ImpactFinding> kept = new ArrayList<>();
    for (ImpactFinding finding : findings) {
      if (!ids.contains(finding.id())) {
        kept.add(finding);
      }
    }
    return List.copyOf(kept);
  }
}
