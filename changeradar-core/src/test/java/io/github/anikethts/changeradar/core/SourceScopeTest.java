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

  @Test
  void keepsImporterThatUsesTsconfigPathAlias() {
    Map<String, String> head =
        Map.of(
            "src/api.ts",
            "export function n() { return 1; }\n",
            "src/caller.ts",
            "import { n } from \"@app/api\";\nexport function run() { return n(); }\n",
            "src/unrelated.ts",
            "export function other() { return 2; }\n",
            "tsconfig.json",
            """
            {
              "compilerOptions": {
                "baseUrl": ".",
                "paths": { "@app/*": ["src/*"] }
              }
            }
            """);
    Map<String, String> subset =
        SourceScope.subset(List.of(new ChangedFile("src/api.ts", ChangeType.MODIFY, null)), head);
    assertTrue(subset.containsKey("src/api.ts"));
    assertTrue(subset.containsKey("src/caller.ts"));
    assertTrue(subset.containsKey("tsconfig.json"));
    assertFalse(subset.containsKey("src/unrelated.ts"));
  }

  @Test
  void keepsImporterThatInheritsTsconfigPaths() {
    Map<String, String> head =
        Map.of(
            "src/api.ts",
            "export function n() { return 1; }\n",
            "packages/web/caller.ts",
            "import { n } from \"@app/api\";\n",
            "packages/web/other.ts",
            "export function other() { return 2; }\n",
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
            { "extends": "../../tsconfig.base.json" }
            """);
    Map<String, String> subset =
        SourceScope.subset(List.of(new ChangedFile("src/api.ts", ChangeType.MODIFY, null)), head);
    assertTrue(subset.containsKey("packages/web/caller.ts"));
    assertTrue(subset.containsKey("packages/web/tsconfig.json"));
    assertTrue(subset.containsKey("tsconfig.base.json"));
    assertFalse(subset.containsKey("packages/web/other.ts"));
  }

  @Test
  void keepsOtherSqlFilesWhenASqlFileChanged() {
    Map<String, String> head =
        Map.of(
            "db/accounts.sql",
            "create table accounts (id int);\n",
            "reports/monthly.sql",
            "select id from accounts;\n",
            "src/other.ts",
            "export function other() { return 1; }\n");
    Map<String, String> subset =
        SourceScope.subset(
            List.of(new ChangedFile("db/accounts.sql", ChangeType.MODIFY, null)), head);
    assertTrue(subset.containsKey("db/accounts.sql"));
    assertTrue(subset.containsKey("reports/monthly.sql"));
    assertFalse(subset.containsKey("src/other.ts"));
  }

  @Test
  void keepsOtherWorkflowFilesWhenAWorkflowFileChanged() {
    Map<String, String> head =
        Map.of(
            ".github/workflows/called.yml",
            "on: workflow_call\n",
            ".github/workflows/caller.yml",
            "jobs:\n  call:\n    uses: ./.github/workflows/called.yml\n",
            "config/app.yml",
            "name: app\n");
    Map<String, String> subset =
        SourceScope.subset(
            List.of(new ChangedFile(".github/workflows/called.yml", ChangeType.MODIFY, null)),
            head);
    assertTrue(subset.containsKey(".github/workflows/called.yml"));
    assertTrue(subset.containsKey(".github/workflows/caller.yml"));
    assertFalse(subset.containsKey("config/app.yml"));
  }

  @Test
  void keepsOtherShellFilesWhenAShellFileChanged() {
    Map<String, String> head =
        Map.of(
            "lib.sh",
            "build() { echo 1; }\n",
            "run.sh",
            "source ./lib.sh\n",
            "src/other.ts",
            "export function other() { return 1; }\n");
    Map<String, String> subset =
        SourceScope.subset(List.of(new ChangedFile("lib.sh", ChangeType.MODIFY, null)), head);
    assertTrue(subset.containsKey("lib.sh"));
    assertTrue(subset.containsKey("run.sh"));
    assertFalse(subset.containsKey("src/other.ts"));
  }

  @Test
  void keepsPhpImporterThatUsesComposerPsr4() {
    Map<String, String> head =
        Map.of(
            "src/Pay.php",
            "<?php\nnamespace App;\nclass Pay { function n() { return 1; } }\n",
            "src/Caller.php",
            "<?php\nuse App\\Pay;\nfunction run() { return 1; }\n",
            "src/Other.php",
            "<?php\nfunction other() { return 2; }\n",
            "composer.json",
            """
            { "autoload": { "psr-4": { "App\\\\": "src/" } } }
            """);
    Map<String, String> subset =
        SourceScope.subset(List.of(new ChangedFile("src/Pay.php", ChangeType.MODIFY, null)), head);
    assertTrue(subset.containsKey("src/Pay.php"));
    assertTrue(subset.containsKey("src/Caller.php"));
    assertTrue(subset.containsKey("composer.json"));
    assertFalse(subset.containsKey("src/Other.php"));
  }

  @Test
  void keepsImporterOfAnExportStarAsBarrel() {
    Map<String, String> head =
        Map.of(
            "src/api.ts",
            "export function n() { return 1; }\n",
            "src/index.ts",
            "export * as api from \"./api\";\n",
            "src/caller.ts",
            "import { api } from \"./index\";\nexport function run() { return api.n(); }\n",
            "src/other.ts",
            "export function other() { return 2; }\n",
            "src/via-caller.ts",
            "import { run } from \"./caller\";\n");
    Map<String, String> subset =
        SourceScope.subset(List.of(new ChangedFile("src/api.ts", ChangeType.MODIFY, null)), head);
    assertTrue(subset.containsKey("src/api.ts"));
    assertTrue(subset.containsKey("src/index.ts"));
    assertTrue(subset.containsKey("src/caller.ts"));
    assertFalse(subset.containsKey("src/other.ts"));
    assertFalse(subset.containsKey("src/via-caller.ts"));
  }
}
