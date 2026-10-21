package io.github.anikethts.changeradar.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns DELETE+ADD pairs into RENAME (same name, new path) or MODIFY (signature change) so policies
 * do not treat refactors as removals.
 */
public final class ChangedSymbols {

  private ChangedSymbols() {}

  public record Result(List<ChangedSymbol> symbols, List<ChangedSymbol> signatureChanges) {
    public Result {
      Objects.requireNonNull(symbols, "symbols");
      Objects.requireNonNull(signatureChanges, "signatureChanges");
      symbols = List.copyOf(symbols);
      signatureChanges = List.copyOf(signatureChanges);
    }
  }

  public static Result coalesce(List<ChangedSymbol> symbols) {
    Objects.requireNonNull(symbols, "symbols");
    Map<String, List<ChangedSymbol>> deleted = new LinkedHashMap<>();
    Map<String, List<ChangedSymbol>> added = new LinkedHashMap<>();
    List<ChangedSymbol> others = new ArrayList<>();
    for (ChangedSymbol symbol : symbols) {
      if (symbol.changeType() == ChangeType.DELETE) {
        deleted.computeIfAbsent(logicalKey(symbol), key -> new ArrayList<>()).add(symbol);
      } else if (symbol.changeType() == ChangeType.ADD) {
        added.computeIfAbsent(logicalKey(symbol), key -> new ArrayList<>()).add(symbol);
      } else {
        others.add(symbol);
      }
    }
    List<ChangedSymbol> out = new ArrayList<>(others);
    List<ChangedSymbol> signatureChanges = new ArrayList<>();
    for (String key : unionKeys(deleted, added)) {
      List<ChangedSymbol> del = deleted.getOrDefault(key, List.of());
      List<ChangedSymbol> add = added.getOrDefault(key, List.of());
      if (del.size() == 1 && add.size() == 1) {
        ChangedSymbol from = del.get(0);
        ChangedSymbol to = add.get(0);
        if (from.location().path().equals(to.location().path())) {
          if (from.publiclyVisible() && !to.publiclyVisible()) {
            out.add(from);
            out.add(to);
          } else {
            ChangedSymbol modified =
                new ChangedSymbol(
                    to.id(),
                    to.kind(),
                    to.name(),
                    to.owner(),
                    to.location(),
                    ChangeType.MODIFY,
                    to.publiclyVisible(),
                    to.signature());
            out.add(modified);
            if (from.publiclyVisible() && to.publiclyVisible()) {
              signatureChanges.add(modified);
            }
          }
        } else {
          out.add(
              new ChangedSymbol(
                  to.id(),
                  to.kind(),
                  to.name(),
                  to.owner(),
                  to.location(),
                  ChangeType.RENAME,
                  to.publiclyVisible(),
                  to.signature()));
        }
      } else {
        out.addAll(del);
        out.addAll(add);
      }
    }
    return new Result(out, signatureChanges);
  }

  private static String logicalKey(ChangedSymbol symbol) {
    return symbol.kind() + "|" + symbol.owner() + "#" + symbol.name();
  }

  private static List<String> unionKeys(
      Map<String, List<ChangedSymbol>> deleted, Map<String, List<ChangedSymbol>> added) {
    List<String> keys = new ArrayList<>(deleted.keySet());
    for (String key : added.keySet()) {
      if (!deleted.containsKey(key)) {
        keys.add(key);
      }
    }
    return keys;
  }
}
