package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceScopeTest {

  @Test
  void keepsChangedFileImporterAndSkipsUnrelated() {
    Map<String, String> head =
        Map.of(
            "src/api.ts",
            "export function n() { return 1; }\n",
            "src/caller.ts",
            "import { n } from \"./api\";\nexport function run() { return n(); }\n",
            "src/unrelated.ts",
            "export function other() { return 2; }\n");
    Map<String, String> subset =
        SourceScope.subset(List.of(new ChangedFile("src/api.ts", ChangeType.MODIFY, null)), head);
    assertTrue(subset.containsKey("src/api.ts"));
    assertTrue(subset.containsKey("src/caller.ts"));
    assertFalse(subset.containsKey("src/unrelated.ts"));
    assertEquals(2, subset.size());
  }
}
