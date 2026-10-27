package io.github.anikethts.changeradar.analyzer.shell;

import java.util.List;

record ParsedShell(String path, String masked, List<Func> functions, List<SourceRef> sources) {}

record Func(String name, int startLine, int endLine) {

  boolean declares(int line) {
    return line == startLine;
  }
}

record SourceRef(String target, int line) {}
