package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
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
  void resolvesTsconfigPathAlias() {
    TsConfigPaths.PathMap pathMap =
        TsConfigPaths.index(
            Map.of(
                "tsconfig.json",
                """
                {
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": { "@app/*": ["src/*"] }
                  }
                }
                """));
    assertEquals(
        "src/api.ts",
        SourcePaths.resolveExisting(
                "src/caller.ts",
                "@app/api",
                Set.of("src/api.ts", "src/other.ts"),
                List.of(".ts", ".js"),
                pathMap)
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
