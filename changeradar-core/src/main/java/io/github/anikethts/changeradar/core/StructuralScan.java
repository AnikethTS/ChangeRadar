package io.github.anikethts.changeradar.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers for override / field / annotation-style findings in the regex-based analyzers. */
public final class StructuralScan {

  private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");

  private StructuralScan() {}

  public static Set<String> typeDeclsWithoutNestedMembers(List<ChangedSymbol> symbols) {
    Objects.requireNonNull(symbols, "symbols");
    Set<String> names = new LinkedHashSet<>();
    for (ChangedSymbol symbol : symbols) {
      if (!"type".equals(symbol.kind())) {
        continue;
      }
      boolean nestedMemberAlsoChanged = false;
      for (ChangedSymbol other : symbols) {
        if ("type".equals(other.kind())) {
          continue;
        }
        if (!other.location().path().equals(symbol.location().path())) {
          continue;
        }
        if (symbol.location().startLine() <= other.location().startLine()
            && other.location().endLine() <= symbol.location().endLine()) {
          nestedMemberAlsoChanged = true;
          break;
        }
      }
      if (!nestedMemberAlsoChanged
          || symbol.changeType() == ChangeType.ADD
          || symbol.changeType() == ChangeType.DELETE) {
        names.add(symbol.name());
      }
    }
    return names;
  }

  public static List<String> identifiers(String text) {
    List<String> names = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return names;
    }
    Matcher matcher = IDENT.matcher(text);
    while (matcher.find()) {
      names.add(matcher.group());
    }
    return names;
  }

  public static void addFinding(
      Set<String> seen, List<ImpactFinding> findings, ImpactFinding finding) {
    String key =
        finding.location().path()
            + ":"
            + finding.location().startLine()
            + ":"
            + finding.type()
            + ":"
            + finding.symbolId();
    if (seen.add(key)) {
      findings.add(finding);
    }
  }
}
