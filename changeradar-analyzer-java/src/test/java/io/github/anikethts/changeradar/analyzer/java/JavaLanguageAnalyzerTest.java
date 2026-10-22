package io.github.anikethts.changeradar.analyzer.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
  void whitespaceAndCommentOnlyJavaIsFormatOnly() {
    String base =
        """
        package demo;
        public class Api {
          public void n() {}
        }
        """;
    String head =
        """
        package demo;
        public class Api {
            /** indent and comment */
            public void n() {  }
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/Api.java", ChangeType.MODIFY, null, List.of(new LineRange(1, 8)))),
            Map.of("src/Api.java", head),
            Map.of("src/Api.java", base));
    assertTrue(result.limitations().stream().anyMatch(l -> "FORMAT_ONLY_CHANGES".equals(l.code())));
    assertFalse(result.changedSymbols().stream().anyMatch(s -> "n".equals(s.name())));
    assertFalse(result.findings().stream().anyMatch(f -> "PUBLIC_API".equals(f.type())));
  }

  @Test
  void bodyTokenChangeIsNotFormatOnly() {
    String base =
        """
        package demo;
        public class Api {
          public int n() { return 1; }
        }
        """;
    String head =
        """
        package demo;
        public class Api {
          public int n() { return 2; }
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/Api.java", ChangeType.MODIFY, null, List.of(new LineRange(3, 3)))),
            Map.of("src/Api.java", head),
            Map.of("src/Api.java", base));
    assertFalse(
        result.limitations().stream().anyMatch(l -> "FORMAT_ONLY_CHANGES".equals(l.code())));
    assertTrue(result.changedSymbols().stream().anyMatch(s -> "n".equals(s.name())));
  }

  @Test
  void infersMavenStyleSourceRoot() {
    assertEquals(
        "src/main/java",
        JavaLanguageAnalyzer.inferSourceRoot(
            "src/main/java/demo/Foo.java", "package demo;\nclass Foo {}"));
  }

  @Test
  void silentWhenNoJavaFiles() {
    AnalysisResult result =
        analyzer.analyze(
            List.of(new ChangedFile("README.md", ChangeType.MODIFY, null)), Map.of(), Map.of());
    assertTrue(result.changedSymbols().isEmpty());
  }
}
