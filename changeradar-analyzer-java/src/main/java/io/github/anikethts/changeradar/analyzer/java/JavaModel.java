package io.github.anikethts.changeradar.analyzer.java;

import com.github.javaparser.ast.CompilationUnit;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.SourceLocation;
import java.util.List;

record ParsedFile(String path, CompilationUnit cu, List<Decl> decls, boolean testFile) {}

record ResolvedCall(String owner, String name) {}

record CallerScan(List<ImpactFinding> findings, boolean chainCapped) {}

record Decl(
    String kind,
    String name,
    String owner,
    String signature,
    String abi,
    boolean isPublic,
    boolean test,
    String path,
    int startLine,
    int endLine) {

  String identity() {
    String vis = isPublic ? "pub" : "npub";
    if ("type".equals(kind)) {
      return "type:" + owner + "|" + vis;
    }
    if ("field".equals(kind)) {
      return kind + ":" + owner + "#" + name + "(" + signature + ")|" + vis;
    }
    return kind + ":" + owner + "#" + signature + ":" + abi + "|" + vis;
  }

  ChangedSymbol toSymbol(ChangeType changeType) {
    String id = path + ":" + identity() + "@" + startLine;
    String reportedSignature = "method".equals(kind) || "constructor".equals(kind) ? signature : "";
    return new ChangedSymbol(
        id,
        kind,
        name,
        owner,
        new SourceLocation(path, startLine, endLine),
        changeType,
        isPublic,
        reportedSignature);
  }
}
