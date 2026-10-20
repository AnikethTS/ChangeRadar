package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChangedSymbolsTest {

  @Test
  void deleteAndAddSameNameDifferentPathIsRename() {
    ChangedSymbol gone =
        new ChangedSymbol(
            "old",
            "method",
            "n",
            "Api",
            new SourceLocation("src/Old.java", 2, 2),
            ChangeType.DELETE,
            true);
    ChangedSymbol added =
        new ChangedSymbol(
            "new",
            "method",
            "n",
            "Api",
            new SourceLocation("src/New.java", 2, 2),
            ChangeType.ADD,
            true);
    ChangedSymbols.Result result = ChangedSymbols.coalesce(List.of(gone, added));
    assertEquals(1, result.symbols().size());
    assertEquals(ChangeType.RENAME, result.symbols().get(0).changeType());
    assertTrue(result.signatureChanges().isEmpty());
  }

  @Test
  void deleteAndAddSamePathIsSignatureChange() {
    ChangedSymbol gone =
        new ChangedSymbol(
            "old",
            "method",
            "n",
            "Api",
            new SourceLocation("src/Api.java", 2, 2),
            ChangeType.DELETE,
            true);
    ChangedSymbol added =
        new ChangedSymbol(
            "new",
            "method",
            "n",
            "Api",
            new SourceLocation("src/Api.java", 2, 3),
            ChangeType.ADD,
            true);
    ChangedSymbols.Result result = ChangedSymbols.coalesce(List.of(gone, added));
    assertEquals(ChangeType.MODIFY, result.symbols().get(0).changeType());
    assertEquals(1, result.signatureChanges().size());
  }
}
