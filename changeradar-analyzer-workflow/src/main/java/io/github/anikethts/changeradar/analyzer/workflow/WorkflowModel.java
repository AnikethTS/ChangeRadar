package io.github.anikethts.changeradar.analyzer.workflow;

import java.util.List;

record ParsedWorkflow(
    String path,
    boolean actionFile,
    boolean callable,
    int onStart,
    int onEnd,
    List<Job> jobs,
    List<Ref> refs) {}

record Job(String name, int startLine, int endLine) {

  boolean covers(int line) {
    return line >= startLine && line <= endLine;
  }
}

/** A same-file {@code needs} or a local {@code uses} path resolved from the repository root. */
record Ref(String kind, String target, String fromJob, int line) {}
