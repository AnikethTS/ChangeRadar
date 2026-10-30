package io.github.anikethts.changeradar.analyzer.kt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KotlinLanguageAnalyzerTest {

  private final KotlinLanguageAnalyzer analyzer = new KotlinLanguageAnalyzer();

  @Test
  void mapsChangedFunctionAndFindsCallerAndTest() {
    String api =
        """
        fun n(): Int {
          return 1
        }
        """;
    String caller =
        """
        fun run(): Int {
          return n()
        }
        """;
    String test =
        """
        fun test_n() {
          n()
        }
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.kt", ChangeType.MODIFY, null, List.of(new LineRange(1, 3)))),
            Map.of("api.kt", api, "caller.kt", caller, "test_api.kt", test),
            Map.of());
    assertTrue(
        result.changedSymbols().stream()
            .anyMatch(symbol -> "n".equals(symbol.name()) && "function".equals(symbol.kind())));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    finding.type().equals("CALLER")
                        && finding.confidence() == Confidence.DIRECT
                        && finding.location().path().contains("caller.kt")));
    assertTrue(
        result.tests().stream()
            .anyMatch(recommendation -> recommendation.path().contains("test_api.kt")));
  }

  @Test
  void overridePropertyAndAnnotationAreReported() {
    String parent =
        """
        open class Parent {
          open fun n() {}
          var tax: Int = 1
        }
        """;
    String child =
        """
        class Child : Parent() {
          override fun n() {}
        }
        fun read(child: Child): Int {
          return child.tax
        }
        """;
    String marked =
        """
        annotation class Mark
        """;
    String host =
        """
        @Mark
        class Host
        val box: Mark = Mark()
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("Parent.kt", ChangeType.MODIFY, null, List.of(new LineRange(2, 3))),
                new ChangedFile("Mark.kt", ChangeType.ADD, null, List.of())),
            Map.of("Parent.kt", parent, "Child.kt", child, "Mark.kt", marked, "Host.kt", host),
            Map.of());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "OVERRIDE".equals(finding.type())
                        && finding.summary().contains("Child#n overrides Parent#n")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "FIELD_ACCESS".equals(finding.type())
                        && finding.location().path().equals("Child.kt")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "ANNOTATION_USE".equals(finding.type())
                        && finding.location().path().equals("Host.kt")));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "FIELD_TYPE".equals(finding.type()) && finding.summary().contains("Mark")));
    assertTrue(result.limitations().stream().anyMatch(l -> "KT_STRUCTURAL_EDGES".equals(l.code())));
  }

  @Test
  void marksRemovedPublicFunctionAsDelete() {
    String base =
        """
        fun gone() {}
        fun stays() {}
        """;
    String head = "fun stays() {}\n";
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.kt", ChangeType.MODIFY, null, List.of(new LineRange(1, 2)))),
            Map.of("api.kt", head),
            Map.of("api.kt", base));
    assertTrue(
        result.changedSymbols().stream()
            .anyMatch(
                symbol ->
                    "gone".equals(symbol.name())
                        && symbol.changeType() == ChangeType.DELETE
                        && symbol.publiclyVisible()));
  }

  @Test
  void whitespaceAndCommentOnlyIsFormatOnly() {
    String base = "fun n(): Int { return 1 }\n";
    String head = "fun n(): Int {  /* pad */ return 1 }\n";
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("api.kt", ChangeType.MODIFY, null, List.of(new LineRange(1, 1)))),
            Map.of("api.kt", head),
            Map.of("api.kt", base));
    assertTrue(result.limitations().stream().anyMatch(l -> "FORMAT_ONLY_CHANGES".equals(l.code())));
    assertFalse(result.changedSymbols().stream().anyMatch(s -> "n".equals(s.name())));
  }
}
