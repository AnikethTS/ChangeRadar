package io.github.anikethts.changeradar.core;

import java.util.List;

/** Shared limitation when a changed file is comments/whitespace only. */
public final class FormatOnly {

  public static final String CODE = "FORMAT_ONLY_CHANGES";

  private FormatOnly() {}

  public static Limitation limitation(List<String> paths) {
    return new Limitation(
        CODE,
        "Files whose tokens are unchanged except comments and whitespace: "
            + String.join(", ", paths)
            + ". Listed as changed files, not as symbol occupancy. Import order and identifier"
            + " edits are not format-only. ChangeRadar does not format source.");
  }
}
