package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChangedFileTest {

  @Test
  void javaSourceDetection() {
    assertTrue(new ChangedFile("src/Main.java", ChangeType.MODIFY, null).isJavaSource());
    assertFalse(new ChangedFile("README.md", ChangeType.MODIFY, null).isJavaSource());
  }
}
