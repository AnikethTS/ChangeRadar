package io.github.anikethts.changeradar.analyzer.java;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaStructuralTest {

  private final JavaLanguageAnalyzer analyzer = new JavaLanguageAnalyzer();

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
}
