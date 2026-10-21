package io.github.anikethts.changeradar.analyzer.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaCallChainTest {

  private final JavaLanguageAnalyzer analyzer = new JavaLanguageAnalyzer();

  @Test
  void resolvedCallerOfCallerIsIndirect() {
    String demo =
        """
        package demo;
        public class Demo {
          public void n() {}
        }
        """;
    String caller =
        """
        package demo;
        public class Caller {
          void run() {
            new Demo().n();
          }
        }
        """;
    String main =
        """
        package demo;
        public class Main {
          public static void main(String[] args) {
            new Caller().run();
          }
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/main/java/demo/Demo.java",
                    ChangeType.MODIFY,
                    null,
                    List.of(new LineRange(3, 3)))),
            Map.of(
                "src/main/java/demo/Demo.java", demo,
                "src/main/java/demo/Caller.java", caller,
                "src/main/java/demo/Main.java", main),
            Map.of());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.confidence() == Confidence.DIRECT
                        && finding.location().path().contains("Caller.java")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.confidence() == Confidence.INDIRECT
                        && finding.location().path().contains("Main.java")
                        && finding.summary().contains("Caller#run")));
    assertTrue(result.limitations().stream().anyMatch(l -> "JAVA_CALL_CHAINS".equals(l.code())));
  }

  @Test
  void heuristicCallersAreNotChained() {
    String foo =
        """
        package demo;
        public class Foo {
          public void n() {}
        }
        """;
    String caller =
        """
        package demo;
        public class Caller {
          void run(lib.Unknown x) {
            x.n();
          }
        }
        """;
    String main =
        """
        package demo;
        public class Main {
          public static void main(String[] args) {
            new Caller().run(null);
          }
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/main/java/demo/Foo.java",
                    ChangeType.MODIFY,
                    null,
                    List.of(new LineRange(3, 3)))),
            Map.of(
                "src/main/java/demo/Foo.java", foo,
                "src/main/java/demo/Caller.java", caller,
                "src/main/java/demo/Main.java", main),
            Map.of());
    assertEquals(
        Confidence.HEURISTIC,
        result.findings().stream()
            .filter(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("Caller.java"))
            .findFirst()
            .orElseThrow()
            .confidence());
    assertTrue(
        result.findings().stream()
            .noneMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.confidence() == Confidence.INDIRECT
                        && finding.location().path().contains("Main.java")));
  }
}
