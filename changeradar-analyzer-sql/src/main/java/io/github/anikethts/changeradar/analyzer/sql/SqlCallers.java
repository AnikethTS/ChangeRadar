package io.github.anikethts.changeradar.analyzer.sql;

import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SqlCallers {

  private SqlCallers() {}

  static List<ImpactFinding> findCallers(
      Map<String, ParsedFile> parsedHead, List<ChangedSymbol> changedSymbols) {
    Map<String, Integer> declarations = new LinkedHashMap<>();
    for (ParsedFile file : parsedHead.values()) {
      Set<String> names = new LinkedHashSet<>();
      for (Decl decl : file.decls()) {
        names.add(decl.name().toLowerCase(Locale.ROOT));
      }
      for (String name : names) {
        declarations.merge(name, 1, Integer::sum);
      }
    }
    List<ImpactFinding> findings = new ArrayList<>();
    for (ChangedSymbol symbol : changedSymbols) {
      String needle = symbol.name().toLowerCase(Locale.ROOT);
      int declaredIn = declarations.getOrDefault(needle, 1);
      Confidence confidence = declaredIn > 1 ? Confidence.HEURISTIC : Confidence.DIRECT;
      for (ParsedFile file : parsedHead.values()) {
        int line = firstReference(file, symbol);
        if (line < 0) {
          continue;
        }
        String label =
            symbol.owner().isEmpty() ? symbol.name() : symbol.owner() + "." + symbol.name();
        findings.add(
            new ImpactFinding(
                "CALLER",
                confidence,
                file.path() + " references " + symbol.kind() + " " + label,
                symbol.id(),
                new SourceLocation(file.path(), line, line)));
      }
    }
    return findings;
  }

  private static int firstReference(ParsedFile file, ChangedSymbol symbol) {
    String masked = file.masked();
    String needle = symbol.name().toLowerCase(Locale.ROOT);
    int i = 0;
    while (i < masked.length()) {
      if (!isIdentStart(masked.charAt(i))) {
        i++;
        continue;
      }
      int start = i;
      i++;
      while (i < masked.length() && isIdentPart(masked.charAt(i))) {
        i++;
      }
      if (!masked.substring(start, i).toLowerCase(Locale.ROOT).equals(needle)) {
        continue;
      }
      if (qualifierMismatch(masked, start, symbol.owner())) {
        continue;
      }
      int line = SqlSupport.lineOf(masked, start);
      boolean definedHere = false;
      for (Decl decl : file.decls()) {
        if (decl.name().equalsIgnoreCase(symbol.name()) && decl.covers(line)) {
          definedHere = true;
          break;
        }
      }
      if (!definedHere) {
        return line;
      }
    }
    return -1;
  }

  private static boolean qualifierMismatch(String masked, int nameStart, String owner) {
    int i = nameStart - 1;
    while (i >= 0 && Character.isWhitespace(masked.charAt(i))) {
      i--;
    }
    if (i < 0 || masked.charAt(i) != '.') {
      return false;
    }
    i--;
    while (i >= 0 && Character.isWhitespace(masked.charAt(i))) {
      i--;
    }
    int end = i + 1;
    while (i >= 0 && isIdentPart(masked.charAt(i))) {
      i--;
    }
    if (end <= i + 1) {
      return false;
    }
    String qualifier = masked.substring(i + 1, end);
    return !owner.isEmpty() && !qualifier.equalsIgnoreCase(owner);
  }

  private static boolean isIdentStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  private static boolean isIdentPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }
}
