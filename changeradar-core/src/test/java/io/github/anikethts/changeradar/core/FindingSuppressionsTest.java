package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FindingSuppressionsTest {

  @Test
  void dropsMatchingIds() {
    ImpactFinding keep =
        new ImpactFinding(
            "CALLER", Confidence.DIRECT, "kept", "a", new SourceLocation("src/A.java", 1, 1));
    ImpactFinding drop =
        new ImpactFinding(
            "CALLER", Confidence.DIRECT, "drop", "b", new SourceLocation("src/B.java", 2, 2));
    List<ImpactFinding> out = FindingSuppressions.apply(List.of(keep, drop), Set.of(drop.id()));
    assertEquals(1, out.size());
    assertEquals(keep.id(), out.get(0).id());
  }
}
