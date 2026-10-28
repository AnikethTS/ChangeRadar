package io.github.anikethts.changeradar.analyzer.shell;

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

final class ShellCallers {

  private ShellCallers() {}

  static List<ImpactFinding> findCallers(
      Map<String, ParsedShell> parsedHead, List<ChangedSymbol> changedSymbols) {
    Map<String, Integer> declarations = new LinkedHashMap<>();
    for (ParsedShell file : parsedHead.values()) {
      Set<String> names = new LinkedHashSet<>();
      for (Func func : file.functions()) {
        names.add(func.name().toLowerCase(Locale.ROOT));
      }
      for (String name : names) {
        declarations.merge(name, 1, Integer::sum);
      }
    }
    List<ImpactFinding> findings = new ArrayList<>();
    for (ChangedSymbol symbol : changedSymbols) {
      String symbolPath = symbol.location().path().replace('\\', '/');
      for (ParsedShell file : parsedHead.values()) {
        if (symbol.kind().equals("script")) {
          int line = sourceLine(file, symbolPath);
          if (line < 0 || file.path().equals(symbolPath)) {
            continue;
          }
          findings.add(
              finding(
                  file.path(),
                  line,
                  file.path() + " sources script " + symbol.name(),
                  symbol,
                  Confidence.DIRECT));
          continue;
        }
        if (!symbol.kind().equals("function")) {
          continue;
        }
        int declaredIn = declarations.getOrDefault(symbol.name().toLowerCase(Locale.ROOT), 1);
        int line = callLine(file, symbol);
        if (line < 0) {
          continue;
        }
        Confidence confidence = declaredIn > 1 ? Confidence.HEURISTIC : Confidence.DIRECT;
        findings.add(
            finding(
                file.path(),
                line,
                file.path() + " calls function " + symbol.name(),
                symbol,
                confidence));
      }
    }
    return findings;
  }

  private static int sourceLine(ParsedShell file, String symbolPath) {
    for (SourceRef ref : file.sources()) {
      if (ref.target().equals(symbolPath)) {
        return ref.line();
      }
    }
    return -1;
  }

  private static int callLine(ParsedShell file, ChangedSymbol symbol) {
    String masked = file.masked();
    String needle = symbol.name().toLowerCase(Locale.ROOT);
    int i = 0;
    while (i < masked.length()) {
      char c = masked.charAt(i);
      if (!isIdentStart(c)) {
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
      int line = ShellSupport.lineOf(masked, start);
      boolean declared = false;
      for (Func func : file.functions()) {
        if (func.name().equalsIgnoreCase(symbol.name()) && func.declares(line)) {
          declared = true;
          break;
        }
      }
      if (!declared) {
        return line;
      }
    }
    return -1;
  }

  private static ImpactFinding finding(
      String path, int line, String summary, ChangedSymbol symbol, Confidence confidence) {
    return new ImpactFinding(
        "CALLER", confidence, summary, symbol.id(), new SourceLocation(path, line, line));
  }

  private static boolean isIdentStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  private static boolean isIdentPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }
}
