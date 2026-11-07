package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComposerAutoloadTest {

  @Test
  void psr4PrefixIsRelativeToTheComposerFile() {
    ComposerAutoload.PrefixMap map =
        ComposerAutoload.index(
            Map.of(
                "packages/pay/composer.json",
                """
                { "autoload": { "psr-4": { "Pay\\\\": ["src/", "lib/"] } } }
                """));
    assertEquals(
        List.of("packages/pay/src/Bill", "packages/pay/lib/Bill"), map.expand("Pay\\Bill"));
  }

  @Test
  void psr0KeepsThePrefixAndSplitsUnderscores() {
    ComposerAutoload.PrefixMap map =
        ComposerAutoload.index(
            Map.of(
                "composer.json",
                """
                { "autoload": { "psr-0": { "Zend_": "library/" } } }
                """));
    assertEquals(List.of("library/Zend/Mail"), map.expand("Zend_Mail"));
  }

  @Test
  void autoloadDevIsIncludedAndPathsAreNotClassNames() {
    ComposerAutoload.PrefixMap map =
        ComposerAutoload.index(
            Map.of(
                "composer.json",
                """
                { "autoload-dev": { "psr-4": { "Tests\\\\": "tests/" } } }
                """));
    assertTrue(map.hasPrefixes());
    assertEquals(List.of("tests/PayTest"), map.expand("Tests\\PayTest"));
    assertTrue(map.expand("./Pay.php").isEmpty());
    assertTrue(map.expand("App\\{Pay, Other}").isEmpty());
  }

  @Test
  void brokenComposerJsonAddsNoPrefixes() {
    ComposerAutoload.PrefixMap map = ComposerAutoload.index(Map.of("composer.json", "{ not json"));
    assertFalse(map.hasPrefixes());
    assertFalse(ComposerAutoload.isComposerPath("composer.lock"));
    assertTrue(ComposerAutoload.isComposerPath("apps/pay/composer.json"));
  }
}
