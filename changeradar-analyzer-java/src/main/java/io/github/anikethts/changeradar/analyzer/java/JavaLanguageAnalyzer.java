package io.github.anikethts.changeradar.analyzer.java;

import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.LanguageAnalyzer;
import io.github.anikethts.changeradar.core.Limitation;
import java.nio.file.Path;
import java.util.List;

/**
 * Java analyzer adapter. Symbol extraction and call-graph traversal are not implemented yet; the
 * analyzer still participates so the CLI can report the limitation instead of implying
 * completeness.
 */
public final class JavaLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "java";

  @Override
  public String languageId() {
    return LANGUAGE_ID;
  }

  @Override
  public boolean supports(ChangedFile file) {
    return file.isJavaSource();
  }

  @Override
  public List<Limitation> limitationsFor(Path repositoryRoot, List<ChangedFile> changedFiles) {
    boolean anyJava = changedFiles.stream().anyMatch(this::supports);
    if (!anyJava) {
      return List.of();
    }
    return List.of(
        new Limitation(
            "JAVA_SYMBOLS_NOT_IMPLEMENTED",
            "Changed Java files were detected, but mapping changed lines to methods, callers, tests,"
                + " and public API contracts is not implemented yet."));
  }
}
