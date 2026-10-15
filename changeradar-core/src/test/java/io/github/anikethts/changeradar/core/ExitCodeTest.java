package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExitCodeTest {

  @Test
  void documentedContract() {
    assertEquals(0, ExitCode.SUCCESS.code());
    assertEquals(1, ExitCode.POLICY_VIOLATION.code());
    assertEquals(2, ExitCode.FAILURE.code());
  }
}
