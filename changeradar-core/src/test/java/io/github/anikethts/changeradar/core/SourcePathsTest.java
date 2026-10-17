package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourcePathsTest {

  @Test
  void resolvesTsRelativeImport() {
    assertEquals(
        "src/api.ts",
        SourcePaths.resolveExisting(
                "src/caller.ts",
                "./api",
                Set.of("src/api.ts", "src/other.ts"),
                List.of(".ts", ".js"))
            .orElseThrow());
  }

  @Test
  void resolvesPythonModuleName() {
    assertEquals(
        "pkg/api.py",
        SourcePaths.resolveExisting(
                "pkg/caller.py", "api", Set.of("pkg/api.py", "other.py"), List.of(".py"))
            .orElseThrow());
  }
}
