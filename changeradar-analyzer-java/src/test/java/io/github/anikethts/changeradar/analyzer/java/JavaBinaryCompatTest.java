package io.github.anikethts.changeradar.analyzer.java;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbols;
import io.github.anikethts.changeradar.core.LineRange;
import io.github.anikethts.changeradar.core.PolicyEngine;
import io.github.anikethts.changeradar.core.PolicyResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JavaBinaryCompatTest {

  private final JavaLanguageAnalyzer analyzer = new JavaLanguageAnalyzer();

  @Test
  void returnTypeChangeBlocksPublicApiPolicy() {
    PolicyResult result =
        policy(
            """
            public class Api {
              public int n() { return 1; }
            }
            """,
            """
            public class Api {
              public String n() { return "1"; }
            }
            """);
    assertTrue(result.blocking());
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("signature changed")));
  }

  @Test
  void addedThrowsBlocksPublicApiPolicy() {
    PolicyResult result =
        policy(
            """
            public class Api {
              public void n() {}
            }
            """,
            """
            public class Api {
              public void n() throws java.io.IOException {}
            }
            """);
    assertTrue(result.blocking());
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("signature changed")));
  }

  @Test
  void fieldTypeChangeBlocksPublicApiPolicy() {
    PolicyResult result =
        policy(
            """
            public class Api {
              public int x;
            }
            """,
            """
            public class Api {
              public String x;
            }
            """);
    assertTrue(result.blocking());
    assertTrue(
        result.violations().stream()
            .anyMatch(
                v -> v.contains("field") && (v.contains("removed") || v.contains("signature"))));
  }

  @Test
  void removedOverloadBlocksPublicApiPolicy() {
    PolicyResult result =
        policy(
            """
            public class Api {
              public void n() {}
              public void n(int x) {}
            }
            """,
            """
            public class Api {
              public void n() {}
            }
            """);
    assertTrue(result.blocking());
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("was removed")));
  }

  @Test
  void narrowedVisibilityBlocksPublicApiPolicy() {
    PolicyResult result =
        policy(
            """
            public class Api {
              public void n() {}
            }
            """,
            """
            public class Api {
              void n() {}
            }
            """);
    assertTrue(result.blocking());
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("was removed")));
  }

  @Test
  void bodyOnlyEditDoesNotBlock() {
    PolicyResult result =
        policy(
            """
            public class Api {
              public void n() {}
            }
            """,
            """
            public class Api {
              public void n() { int x = 1; }
            }
            """);
    assertFalse(result.blocking());
  }

  private PolicyResult policy(String base, String head) {
    AnalysisResult analysis =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "src/Api.java", ChangeType.MODIFY, null, List.of(new LineRange(1, 20)))),
            Map.of("src/Api.java", head),
            Map.of("src/Api.java", base));
    ChangedSymbols.Result coalesced = ChangedSymbols.coalesce(analysis.changedSymbols());
    return PolicyEngine.evaluate(
        List.of(PolicyEngine.BREAKING_PUBLIC_JAVA_API),
        new AnalysisResult(
            coalesced.symbols(), analysis.findings(), analysis.tests(), analysis.limitations()),
        List.of(),
        Set.of(),
        coalesced.signatureChanges());
  }
}
