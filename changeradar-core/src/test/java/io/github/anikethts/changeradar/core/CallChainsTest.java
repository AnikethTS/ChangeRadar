package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CallChainsTest {

  @Test
  void depthOneIsDirectAndDepthTwoIsIndirect() {
    SourceLocation a = new SourceLocation("Caller.java", 4, 4);
    SourceLocation b = new SourceLocation("Main.java", 6, 6);
    CallChains.Expansion expansion =
        CallChains.fromResolved(
            Set.of("demo.Demo#n"),
            List.of(
                new CallChains.Edge("demo.Demo#n", "demo.Caller#run", a),
                new CallChains.Edge("demo.Caller#run", "demo.Main#main", b)));
    assertEquals(2, expansion.findings().size());
    assertEquals(Confidence.DIRECT, expansion.findings().get(0).confidence());
    assertEquals(Confidence.INDIRECT, expansion.findings().get(1).confidence());
    assertTrue(expansion.findings().get(1).summary().contains("Caller#run"));
    assertFalse(expansion.capped());
  }

  @Test
  void capsIndirectFindings() {
    List<CallChains.Edge> edges = new ArrayList<>();
    SourceLocation hop = new SourceLocation("Caller.java", 1, 1);
    edges.add(new CallChains.Edge("demo.Demo#n", "demo.Caller#run", hop));
    for (int i = 0; i < CallChains.MAX_INDIRECT + 5; i++) {
      edges.add(
          new CallChains.Edge(
              "demo.Caller#run", "demo.Main#m" + i, new SourceLocation("Main.java", i + 1, i + 1)));
    }
    CallChains.Expansion expansion = CallChains.fromResolved(Set.of("demo.Demo#n"), edges);
    long indirect =
        expansion.findings().stream().filter(f -> f.confidence() == Confidence.INDIRECT).count();
    assertEquals(CallChains.MAX_INDIRECT, indirect);
    assertTrue(expansion.capped());
  }
}
