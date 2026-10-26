package io.github.anikethts.changeradar.analyzer.workflow;

import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkflowCallers {

  private WorkflowCallers() {}

  static List<ImpactFinding> findCallers(
      Map<String, ParsedWorkflow> parsedHead,
      List<ChangedSymbol> changedSymbols,
      Map<String, String> previousPaths) {
    List<ImpactFinding> findings = new ArrayList<>();
    for (ChangedSymbol symbol : changedSymbols) {
      String symbolPath = symbol.location().path().replace('\\', '/');
      String previous = previousPaths.get(symbolPath);
      for (ParsedWorkflow file : parsedHead.values()) {
        int line = -1;
        String summary = null;
        Set<String> seen = new LinkedHashSet<>();
        for (Ref ref : file.refs()) {
          if (!matches(file.path(), ref, symbol, symbolPath, previous)) {
            continue;
          }
          if (symbol.kind().equals("job")
              && ref.line() >= symbol.location().startLine()
              && ref.line() <= symbol.location().endLine()) {
            continue;
          }
          if (symbol.kind().equals("workflow") && file.path().equals(symbolPath)) {
            continue;
          }
          if (symbol.kind().equals("action") && file.path().equals(symbolPath)) {
            continue;
          }
          String key = ref.kind() + ":" + ref.target();
          if (!seen.add(key)) {
            continue;
          }
          line = ref.line();
          summary = summary(file, ref, symbol);
          break;
        }
        if (line < 0 || summary == null) {
          continue;
        }
        findings.add(
            new ImpactFinding(
                "CALLER",
                Confidence.DIRECT,
                summary,
                symbol.id(),
                new SourceLocation(file.path(), line, line)));
      }
    }
    return findings;
  }

  private static boolean matches(
      String filePath, Ref ref, ChangedSymbol symbol, String symbolPath, String previous) {
    if (ref.kind().equals("needs") && symbol.kind().equals("job")) {
      return filePath.equals(symbolPath) && ref.target().equals(symbol.name());
    }
    if (ref.kind().equals("uses") && symbol.kind().equals("workflow")) {
      return ref.target().equals(symbolPath) || (previous != null && ref.target().equals(previous));
    }
    if (ref.kind().equals("uses") && symbol.kind().equals("action")) {
      String directory = parent(symbolPath);
      String previousDirectory = previous == null ? null : parent(previous);
      return ref.target().equals(directory)
          || (previousDirectory != null && ref.target().equals(previousDirectory));
    }
    return false;
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private static String summary(ParsedWorkflow file, Ref ref, ChangedSymbol symbol) {
    if (ref.kind().equals("needs")) {
      String job = ref.fromJob().isEmpty() ? file.path() : file.path() + " job " + ref.fromJob();
      return job + " needs job " + symbol.name();
    }
    if (symbol.kind().equals("action")) {
      return file.path() + " uses action " + symbol.name();
    }
    return file.path() + " uses workflow " + symbol.name();
  }
}
