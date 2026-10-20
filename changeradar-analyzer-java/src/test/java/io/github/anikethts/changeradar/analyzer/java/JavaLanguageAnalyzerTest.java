package io.github.anikethts.changeradar.analyzer.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisOptions;
import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.LineRange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  void recordsClasspathLimitationWhenJarsProvided(@TempDir Path tmp) throws Exception {
    Path libs = tmp.resolve("libs");
    Files.createDirectories(libs);
    String demo =
        """
        package demo;
        public class Demo {
          public void n() {}
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/main/java/demo/Demo.java",
                    ChangeType.MODIFY,
                    null,
                    List.of(new LineRange(3, 4)))),
            Map.of("src/main/java/demo/Demo.java", demo),
            Map.of(),
            new AnalysisOptions(List.of(libs), true));
    assertTrue(result.limitations().stream().anyMatch(l -> "JAVA_CLASSPATH".equals(l.code())));
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
  void overrideOfChangedMethodIsDirect() {
    String api =
        """
        package demo;
        public interface Api {
          void n();
        }
        """;
    String impl =
        """
        package demo;
        public class Impl implements Api {
          public void n() {}
        }
        """;
    String main =
        """
        package demo;
        public class Main {
          void run(Impl impl) {
            impl.n();
          }
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/main/java/demo/Api.java",
                    ChangeType.MODIFY,
                    null,
                    List.of(new LineRange(3, 3)))),
            Map.of(
                "src/main/java/demo/Api.java", api,
                "src/main/java/demo/Impl.java", impl,
                "src/main/java/demo/Main.java", main),
            Map.of());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "OVERRIDE".equals(finding.type())
                        && finding.confidence() == Confidence.DIRECT
                        && finding.summary().contains("Impl#n")
                        && finding.summary().contains("Api#n")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.location().path().contains("Main.java")));
    assertTrue(
        result.limitations().stream().anyMatch(l -> "JAVA_STRUCTURAL_EDGES".equals(l.code())));
  }

  @Test
  void fieldReadOfChangedFieldIsDirect() {
    String demo =
        """
        package demo;
        public class Demo {
          public int timeout;
        }
        """;
    String caller =
        """
        package demo;
        public class Caller {
          int read(Demo d) {
            return d.timeout;
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
                "src/main/java/demo/Caller.java", caller),
            Map.of());
    assertTrue(
        result.changedSymbols().stream()
            .anyMatch(symbol -> "timeout".equals(symbol.name()) && "field".equals(symbol.kind())));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "FIELD_ACCESS".equals(finding.type())
                        && finding.confidence() == Confidence.DIRECT
                        && finding.location().path().contains("Caller.java")));
  }

  @Test
  void annotationUseAndInjectedFieldOfChangedType() {
    String flag =
        """
        package demo;
        public @interface Flag {}
        """;
    String demo =
        """
        package demo;
        public class Demo {}
        """;
    String worker =
        """
        package demo;
        public class Worker {
          @Flag
          @Autowired Demo demo;
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/main/java/demo/Flag.java",
                    ChangeType.MODIFY,
                    null,
                    List.of(new LineRange(1, 2))),
                new ChangedFile(
                    "src/main/java/demo/Demo.java",
                    ChangeType.MODIFY,
                    null,
                    List.of(new LineRange(1, 2)))),
            Map.of(
                "src/main/java/demo/Flag.java", flag,
                "src/main/java/demo/Demo.java", demo,
                "src/main/java/demo/Worker.java", worker),
            Map.of());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "ANNOTATION_USE".equals(finding.type())
                        && finding.location().path().contains("Worker.java")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "INJECTION".equals(finding.type())
                        && finding.summary().contains("injects")
                        && finding.location().path().contains("Worker.java")));
  }

  @Test
  void methodOnlyChangeDoesNotFlagInjectedField() {
    String demo =
        """
        package demo;
        public class Demo {
          public void n() {}
        }
        """;
    String worker =
        """
        package demo;
        public class Worker {
          @Autowired Demo demo;
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
                "src/main/java/demo/Worker.java", worker),
            Map.of());
    assertTrue(result.findings().stream().noneMatch(finding -> "INJECTION".equals(finding.type())));
    assertTrue(
        result.findings().stream().noneMatch(finding -> "FIELD_TYPE".equals(finding.type())));
  }

  @Test
  void silentWhenNoJavaFiles() {
    AnalysisResult result =
        analyzer.analyze(
            List.of(new ChangedFile("README.md", ChangeType.MODIFY, null)), Map.of(), Map.of());
    assertTrue(result.changedSymbols().isEmpty());
  }
}
