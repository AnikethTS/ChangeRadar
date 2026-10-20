package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyFileTest {

  @TempDir Path tmp;

  @Test
  void readsPoliciesAndBaseline() throws Exception {
    Path file = tmp.resolve("changeradar.yml");
    Files.writeString(
        file,
        """
        # comment
        policies:
          - breaking-public-java-api
          - analysis-unavailable
        baseline: .changeradar/baseline.txt
        """);
    PolicyFile.Document document = PolicyFile.load(file);
    assertEquals(2, document.policies().size());
    assertTrue(document.policies().contains(PolicyEngine.BREAKING_PUBLIC_JAVA_API));
    assertEquals(".changeradar/baseline.txt", document.baseline());
  }
}
