package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FindingIdsTest {

  @Test
  void isStableForSameSite() {
    SourceLocation location = new SourceLocation("src/Caller.java", 4, 4);
    assertEquals(
        "CALLER|demo.Demo#n|src/Caller.java:4", FindingIds.of("CALLER", "demo.Demo#n", location));
    assertEquals(
        FindingIds.of("CALLER", "demo.Demo#n", location),
        new ImpactFinding("CALLER", Confidence.DIRECT, "summary", "demo.Demo#n", location).id());
  }
}
