package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoverageIndexTest {

  @Test
  void jacocoPackagePathMatchesMavenLayout() {
    assertTrue(CoverageIndex.pathMatches("src/main/java/demo/Demo.java", "demo/Demo.java"));
    assertFalse(CoverageIndex.pathMatches("src/main/java/demo/Other.java", "demo/Demo.java"));
  }

  @Test
  void productionStemsStripTestSuffixes() {
    assertTrue(CoverageIndex.productionStems("src/test/java/demo/DemoTest.java").contains("demo"));
    assertTrue(CoverageIndex.productionStems("src/api.test.ts").contains("api"));
    assertTrue(CoverageIndex.productionStems("test_demo.py").contains("demo"));
  }
}
