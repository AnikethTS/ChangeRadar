package io.github.anikethts.changeradar.analyzer.ts;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedSymbol;
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

record Imports(Map<String, String> bindings, Set<String> modules, Set<String> starExports) {}

record Decl(
    String kind,
    String name,
    String owner,
    boolean exported,
    boolean test,
    String path,
    int startLine,
    int endLine) {

  String identity() {
    return kind + ":" + owner + "#" + name;
  }

  ChangedSymbol toSymbol(ChangeType changeType) {
    String id = path + ":" + identity() + "@" + startLine;
    return new ChangedSymbol(
        id, kind, name, owner, new SourceLocation(path, startLine, endLine), changeType, exported);
  }
}
