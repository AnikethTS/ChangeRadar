package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpToolsTest {

  @Test
  void configurePassesCiEvidenceWithoutWritingFiles() {
    ObjectNode args = new ReportWriters().mapper().createObjectNode();
    args.put("base", "main");
    args.put("head", "HEAD");
    args.put("policyFile", "changeradar.yml");
    args.put("baseline", "policy-baseline.txt");
    args.put("suppressFile", "suppress.txt");
    args.put("fullIndex", true);
    args.putArray("policies").add("breaking-public-java-api");
    args.putArray("classpath").add("build/libs");
    args.putArray("coverage").add("build/jacoco.xml");
    args.putArray("junit").add("build/test-results");

    AnalyzeCommand command = McpTools.configure(args);

    assertEquals("main", command.base);
    assertEquals("HEAD", command.head);
    assertEquals(List.of("agent"), command.formats);
    assertEquals(List.of("breaking-public-java-api"), command.policies);
    assertEquals(Path.of("changeradar.yml"), command.policyFile);
    assertEquals(Path.of("policy-baseline.txt"), command.baseline);
    assertEquals(Path.of("suppress.txt"), command.suppressFile);
    assertNull(command.writeBaseline);
    assertNull(command.writeSuppressions);
    assertEquals(List.of("build/libs"), command.classpath);
    assertEquals(List.of("build/jacoco.xml"), command.coverage);
    assertEquals(List.of("build/test-results"), command.junit);
    assertTrue(command.fullIndex);
  }

  @Test
  void configureRejectsAMissingRef() {
    ObjectNode args = new ReportWriters().mapper().createObjectNode();
    args.put("head", "HEAD");
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> McpTools.configure(args));
    assertEquals("base and head are required", failure.getMessage());
    assertFalse(args.has("base"));
  }
}
