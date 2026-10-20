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
}
