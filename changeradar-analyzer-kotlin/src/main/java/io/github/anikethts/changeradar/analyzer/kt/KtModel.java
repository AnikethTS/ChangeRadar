package io.github.anikethts.changeradar.analyzer.kt;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.SourceLocation;
import java.util.List;
import java.util.Map;
import java.util.Set;

record ParsedFile(
    String path,
    List<String> lines,
    List<Decl> decls,
    boolean testFile,
    Imports imports,
    Map<String, List<String>> supers) {}

record CallerScan(List<ImpactFinding> findings, boolean chainCapped) {}

record Imports(Map<String, String> bindings, Set<String> modules) {}

record Decl(
    String kind,
    String name,
    String owner,
    boolean publiclyVisible,
    boolean test,
    String path,
    int startLine,
    int endLine) {

  String identity() {
    return kind + ":" + owner + "#" + name;
  }

  ChangedSymbol toSymbol(ChangeType changeType) {
    return new ChangedSymbol(
        path + ":" + identity() + "@" + startLine,
        kind,
        name,
        owner,
        new SourceLocation(path, startLine, endLine),
        changeType,
        publiclyVisible);
  }
}
