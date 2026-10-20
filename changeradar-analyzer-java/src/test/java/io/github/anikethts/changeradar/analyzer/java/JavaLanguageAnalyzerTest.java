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

class JavaLanguageAnalyzerTest {

  private final JavaLanguageAnalyzer analyzer = new JavaLanguageAnalyzer();

  @Test
  void mapsChangedMethodAndFindsCallerAndTest() {
    String demo =
        """
        package demo;
        public class Demo {
          public void n() {
            int x = 1;
          }
        }
        """;
    String caller =
        """
        package demo;
        public class Caller {
          void run() {
            Demo d = new Demo();
            d.n();
          }
        }
        """;
    String test =
        """
        package demo;
        public class DemoTest {
          @org.junit.jupiter.api.Test
          void coversN() {
            new Demo().n();
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
                    List.of(new LineRange(3, 5)))),
            Map.of(
                "src/main/java/demo/Demo.java", demo,
                "src/main/java/demo/Caller.java", caller,
                "src/test/java/demo/DemoTest.java", test),
            Map.of());

    assertTrue(
        result.changedSymbols().stream()
            .anyMatch(symbol -> "n".equals(symbol.name()) && "method".equals(symbol.kind())));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    finding.type().equals("CALLER")
                        && finding.confidence() == Confidence.DIRECT
                        && finding.location().path().contains("Caller.java")));
    assertTrue(result.limitations().stream().anyMatch(l -> "JAVA_SYMBOL_SOLVER".equals(l.code())));
    assertTrue(
        result.tests().stream()
            .anyMatch(recommendation -> recommendation.name().equals("DemoTest")));
  }

  @Test
  void parsesRecordsAndSwitchExpressions() {
    String source =
        """
        package demo;
        public record Point(int x, int y) {
          public int magnitudeKind() {
            return switch (x) {
              case 0 -> 0;
              default -> 1;
            };
          }
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/Point.java", ChangeType.ADD, null, List.of(new LineRange(1, 12)))),
            Map.of("src/Point.java", source),
            Map.of());
    assertTrue(result.limitations().stream().noneMatch(l -> "JAVA_PARSE_FAILED".equals(l.code())));
    assertTrue(result.changedSymbols().stream().anyMatch(s -> "Point".equals(s.name())));
  }

  @Test
  void marksRemovedPublicMethodAsDelete() {
    String base =
        """
        package demo;
        public class Api {
          public void gone() {}
          public void stays() {}
        }
        """;
    String head =
        """
        package demo;
        public class Api {
          public void stays() {}
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/Api.java", ChangeType.MODIFY, null, List.of(new LineRange(2, 4)))),
            Map.of("src/Api.java", head),
            Map.of("src/Api.java", base));
    assertTrue(
        result.changedSymbols().stream()
            .anyMatch(
                symbol ->
                    "gone".equals(symbol.name())
                        && symbol.changeType() == ChangeType.DELETE
                        && symbol.publiclyVisible()));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "PUBLIC_API".equals(finding.type())
                        && finding.summary().contains("was removed")));
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

  @Test
  void infersMavenStyleSourceRoot() {
    assertEquals(
        "src/main/java",
        JavaLanguageAnalyzer.inferSourceRoot(
            "src/main/java/demo/Foo.java", "package demo;\nclass Foo {}"));
  }

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

  @Test
  void silentWhenNoJavaFiles() {
    AnalysisResult result =
        analyzer.analyze(
            List.of(new ChangedFile("README.md", ChangeType.MODIFY, null)), Map.of(), Map.of());
    assertTrue(result.changedSymbols().isEmpty());
  }
}
