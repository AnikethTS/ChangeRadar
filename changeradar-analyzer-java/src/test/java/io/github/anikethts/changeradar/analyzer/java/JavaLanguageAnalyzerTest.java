package io.github.anikethts.changeradar.analyzer.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.Limitation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaLanguageAnalyzerTest {

  private final JavaLanguageAnalyzer analyzer = new JavaLanguageAnalyzer();

  @Test
  void reportsLimitationWhenJavaFilesChanged() {
    List<Limitation> limitations =
        analyzer.limitationsFor(
            Path.of("."), List.of(new ChangedFile("src/Main.java", ChangeType.MODIFY, null)));
    assertEquals(1, limitations.size());
    assertEquals("JAVA_SYMBOLS_NOT_IMPLEMENTED", limitations.get(0).code());
  }

  @Test
  void silentWhenNoJavaFiles() {
    assertTrue(
        analyzer
            .limitationsFor(
                Path.of("."), List.of(new ChangedFile("README.md", ChangeType.MODIFY, null)))
            .isEmpty());
  }
}
