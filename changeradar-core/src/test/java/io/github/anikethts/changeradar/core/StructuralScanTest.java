package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StructuralScanTest {

  @Test
  void typeDeclsSkipWhenNestedMethodAlsoChanged() {
    ChangedSymbol type =
        new ChangedSymbol(
            "t",
            "type",
            "Demo",
            "Demo",
            new SourceLocation("demo.py", 1, 10),
            ChangeType.MODIFY,
            true);
    ChangedSymbol method =
        new ChangedSymbol(
            "m",
            "method",
            "n",
            "Demo",
            new SourceLocation("demo.py", 3, 5),
            ChangeType.MODIFY,
            true);
    assertTrue(StructuralScan.typeDeclsWithoutNestedMembers(List.of(type, method)).isEmpty());
    assertEquals(
        List.of("Demo"), List.copyOf(StructuralScan.typeDeclsWithoutNestedMembers(List.of(type))));
  }
}
