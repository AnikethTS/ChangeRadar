package io.github.anikethts.changeradar.core;

/** Inclusive 1-based line range in a file at HEAD (or empty when the path was deleted). */
public record LineRange(int startLine, int endLine) {

  public boolean overlaps(int otherStart, int otherEnd) {
    return startLine <= otherEnd && otherStart <= endLine;
  }
}
