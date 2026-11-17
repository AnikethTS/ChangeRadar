package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TsConfigPathsTest {

  @Test
  void parsesJsoncAndPrefersNearestTsconfig() {
    TsConfigPaths.PathMap pathMap =
        TsConfigPaths.index(
            Map.of(
                "tsconfig.json",
                """
                {
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": { "@root/*": ["src/*"] }
                  }
                }
                """,
                "packages/web/tsconfig.json",
                """
                {
                  // app aliases
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": { "@app/*": ["src/*"] },
                  }
                }
                """));
    assertEquals(
        "packages/web/src/api",
        pathMap.expand("packages/web/src/caller.ts", "@app/api").getFirst());
    assertEquals("src/api", pathMap.expand("src/caller.ts", "@root/api").getFirst());
    assertTrue(pathMap.hasMappings());
  }

  @Test
  void inheritsPathsFromARelativeExtends() {
    TsConfigPaths.PathMap pathMap =
        TsConfigPaths.index(
            Map.of(
                "tsconfig.base.json",
                """
                {
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": { "@app/*": ["src/*"] }
                  }
                }
                """,
                "packages/web/tsconfig.json",
                """
                {
                  "extends": "../../tsconfig.base.json",
                  "compilerOptions": { "outDir": "dist" }
                }
                """));
    assertEquals("src/api", pathMap.expand("packages/web/src/caller.ts", "@app/api").getFirst());
  }

  @Test
  void childPathsReplaceTheExtendedConfig() {
    TsConfigPaths.PathMap pathMap =
        TsConfigPaths.index(
            Map.of(
                "tsconfig.base.json",
                """
                { "compilerOptions": { "baseUrl": ".", "paths": { "@app/*": ["src/*"] } } }
                """,
                "packages/web/tsconfig.json",
                """
                {
                  "extends": "../../tsconfig.base.json",
                  "compilerOptions": { "baseUrl": ".", "paths": { "@app/*": ["lib/*"] } }
                }
                """));
    assertEquals(
        "packages/web/lib/api",
        pathMap.expand("packages/web/src/caller.ts", "@app/api").getFirst());
  }

  @Test
  void extendCyclesDoNotHang() {
    TsConfigPaths.PathMap pathMap =
        TsConfigPaths.index(
            Map.of(
                "tsconfig.json",
                """
                {
                  "extends": "./tsconfig.base.json",
                  "compilerOptions": { "baseUrl": ".", "paths": { "@a/*": ["src/*"] } }
                }
                """,
                "tsconfig.base.json",
                """
                { "extends": "./tsconfig.json" }
                """));
    assertEquals("src/api", pathMap.expand("src/caller.ts", "@a/api").getFirst());
  }
}
