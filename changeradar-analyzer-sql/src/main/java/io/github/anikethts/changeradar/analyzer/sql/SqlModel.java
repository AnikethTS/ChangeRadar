package io.github.anikethts.changeradar.analyzer.sql;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.SourceLocation;
import java.util.List;

record ParsedFile(String path, String masked, List<Decl> decls, boolean testFile) {}

record Decl(
    String kind, String name, String owner, boolean test, String path, int startLine, int endLine) {

  String identity() {
    return kind + ":" + owner.toLowerCase() + "#" + name.toLowerCase();
  }

  boolean covers(int line) {
    return line >= startLine && line <= endLine;
  }

  ChangedSymbol toSymbol(ChangeType changeType) {
    return new ChangedSymbol(
        path + ":" + identity() + "@" + startLine,
        kind,
        name,
        owner,
        new SourceLocation(path, startLine, endLine),
        changeType,
        true);
  }
}
