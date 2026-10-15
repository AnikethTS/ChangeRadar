package io.github.anikethts.changeradar.core;

/** How strongly the analyzer can support a finding. Never treat HEURISTIC or UNKNOWN as proven. */
public enum Confidence {
  DIRECT,
  INDIRECT,
  HEURISTIC,
  UNKNOWN
}
