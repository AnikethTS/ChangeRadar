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

class JavaCallerResolutionTest {

  private final JavaLanguageAnalyzer analyzer = new JavaLanguageAnalyzer();

  @Test
  void resolvedCallerMatchesOnlyTheChangedOverload() {
    String demo =
        """
        package demo;
        public class Demo {
          public void n(int x) {}
          public void n(String s) {}
        }
        """;
    String intCaller =
        """
        package demo;
        public class IntCaller {
          void run() {
            new Demo().n(1);
          }
        }
        """;
    String stringCaller =
        """
        package demo;
        public class StringCaller {
          void run() {
            new Demo().n("x");
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
                "src/main/java/demo/IntCaller.java", intCaller,
                "src/main/java/demo/StringCaller.java", stringCaller),
            Map.of());
    assertTrue(
        result.changedSymbols().stream()
            .anyMatch(
                symbol ->
                    "n".equals(symbol.name())
                        && "method".equals(symbol.kind())
                        && symbol.signature().contains("int")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.confidence() == Confidence.DIRECT
                        && finding.location().path().contains("IntCaller.java")
                        && finding.symbolId().contains("n(int)")));
    assertTrue(
        result.findings().stream()
            .noneMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("StringCaller.java")
                        && finding.symbolId().contains("#n")));
  }

  @Test
  void sameMethodNameOnAnotherTypeIsNotACaller() {
    String foo =
        """
        package demo;
        public class Foo {
          public void n() {}
        }
        """;
    String bar =
        """
        package demo;
        public class Bar {
          public void n() {}
        }
        """;
    String caller =
        """
        package demo;
        public class Caller {
          void run() {
            new Bar().n();
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
                "src/main/java/demo/Bar.java", bar,
                "src/main/java/demo/Caller.java", caller),
            Map.of());
    assertTrue(
        result.findings().stream()
            .noneMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("Caller.java")));
  }

  @Test
  void otherTypeUnscopedSameNameIsNotACaller() {
    String foo =
        """
        package demo;
        public class Foo {
          static void parseBase() {}
        }
        """;
    String other =
        """
        package other;
        public class PhpAnalyzer {
          static void parseBase() {}
          void run() {
            parseBase();
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
                "src/main/java/other/PhpAnalyzer.java", other),
            Map.of());
    assertTrue(
        result.findings().stream()
            .noneMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("PhpAnalyzer.java")));
  }

  @Test
  void unresolvedCallWithSameNameIsHeuristic() {
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
                "src/main/java/demo/Caller.java", caller),
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
    assertTrue(result.limitations().stream().anyMatch(l -> "NAME_BASED_DISPATCH".equals(l.code())));
  }
}
