package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

  @Test
  void nothingBlocksWhenNoPolicyEnabled() {
    AnalysisResult analysis =
        new AnalysisResult(List.of(deletedPublicMethod()), List.of(), List.of(), List.of());
    PolicyResult result = PolicyEngine.evaluate(List.of(), analysis, List.of());
    assertFalse(result.blocking());
    assertTrue(result.violations().isEmpty());
  }

  @Test
  void publicMethodRemovalBlocksOnlyWhenEnabled() {
    AnalysisResult analysis =
        new AnalysisResult(List.of(deletedPublicMethod()), List.of(), List.of(), List.of());
    PolicyResult off = PolicyEngine.evaluate(List.of(), analysis, List.of());
    assertFalse(off.blocking());
    PolicyResult on =
        PolicyEngine.evaluate(List.of(PolicyEngine.BREAKING_PUBLIC_JAVA_API), analysis, List.of());
    assertTrue(on.blocking());
    assertTrue(
        on.violations().stream().anyMatch(v -> v.contains(PolicyEngine.BREAKING_PUBLIC_JAVA_API)));
  }

  @Test
  void publicBodyEditDoesNotCountAsBreaking() {
    ChangedSymbol edited =
        new ChangedSymbol(
            "id",
            "method",
            "n",
            "Api",
            new SourceLocation("src/Api.java", 2, 4),
            ChangeType.MODIFY,
            true);
    AnalysisResult analysis = new AnalysisResult(List.of(edited), List.of(), List.of(), List.of());
    PolicyResult result =
        PolicyEngine.evaluate(List.of(PolicyEngine.BREAKING_PUBLIC_JAVA_API), analysis, List.of());
    assertFalse(result.blocking());
  }

  @Test
  void parseFailureOnChangedJavaBlocksWhenEnabled() {
    AnalysisResult analysis =
        new AnalysisResult(
            List.of(),
            List.of(),
            List.of(),
            List.of(new Limitation("JAVA_PARSE_FAILED", "Could not parse src/Broken.java: boom")));
    List<ChangedFile> files = List.of(new ChangedFile("src/Broken.java", ChangeType.MODIFY, null));
    PolicyResult off = PolicyEngine.evaluate(List.of(), analysis, files);
    assertFalse(off.blocking());
    PolicyResult on =
        PolicyEngine.evaluate(List.of(PolicyEngine.ANALYSIS_UNAVAILABLE), analysis, files);
    assertTrue(on.blocking());
  }

  @Test
  void unknownPolicyIsInputFailure() {
    GitAccessException ex =
        assertThrows(GitAccessException.class, () -> PolicyEngine.parseEnabled(List.of("nope")));
    assertTrue(ex.getMessage().contains("Unknown --policy"));
  }

  @Test
  void exportedTsRemovalUsesExportPolicyNotJavaPolicy() {
    ChangedSymbol gone =
        new ChangedSymbol(
            "id",
            "function",
            "gone",
            "",
            new SourceLocation("src/api.ts", 1, 1),
            ChangeType.DELETE,
            true);
    AnalysisResult analysis = new AnalysisResult(List.of(gone), List.of(), List.of(), List.of());
    assertFalse(
        PolicyEngine.evaluate(List.of(PolicyEngine.BREAKING_PUBLIC_JAVA_API), analysis, List.of())
            .blocking());
    assertTrue(
        PolicyEngine.evaluate(List.of(PolicyEngine.BREAKING_PUBLIC_EXPORT), analysis, List.of())
            .blocking());
  }

  @Test
  void pythonRemovalUsesPythonPolicyNotJavaPolicy() {
    ChangedSymbol gone =
        new ChangedSymbol(
            "id",
            "function",
            "gone",
            "",
            new SourceLocation("api.py", 1, 2),
            ChangeType.DELETE,
            true);
    AnalysisResult analysis = new AnalysisResult(List.of(gone), List.of(), List.of(), List.of());
    assertFalse(
        PolicyEngine.evaluate(List.of(PolicyEngine.BREAKING_PUBLIC_JAVA_API), analysis, List.of())
            .blocking());
    assertTrue(
        PolicyEngine.evaluate(List.of(PolicyEngine.BREAKING_PUBLIC_PYTHON), analysis, List.of())
            .blocking());
  }

  @Test
  void phpRemovalUsesPhpPolicyNotJavaPolicy() {
    ChangedSymbol gone =
        new ChangedSymbol(
            "id",
            "function",
            "gone",
            "",
            new SourceLocation("api.php", 2, 2),
            ChangeType.DELETE,
            true);
    AnalysisResult analysis = new AnalysisResult(List.of(gone), List.of(), List.of(), List.of());
    assertFalse(
        PolicyEngine.evaluate(List.of(PolicyEngine.BREAKING_PUBLIC_JAVA_API), analysis, List.of())
            .blocking());
    assertTrue(
        PolicyEngine.evaluate(List.of(PolicyEngine.BREAKING_PUBLIC_PHP), analysis, List.of())
            .blocking());
  }

  @Test
  void parseEnabledAcceptsCommaSeparatedNames() {
    assertEquals(
        2,
        PolicyEngine.parseEnabled(
                List.of(
                    PolicyEngine.BREAKING_PUBLIC_JAVA_API
                        + ","
                        + PolicyEngine.ANALYSIS_UNAVAILABLE))
            .size());
  }

  private static ChangedSymbol deletedPublicMethod() {
    return new ChangedSymbol(
        "id",
        "method",
        "n",
        "Api",
        new SourceLocation("src/Api.java", 2, 3),
        ChangeType.DELETE,
        true);
  }
}
