package io.github.anikethts.changeradar.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Language-neutral analyzer contract. Java, TypeScript/JavaScript, Python, and PHP adapters map
 * into the same report model.
 */
public interface LanguageAnalyzer {

  String languageId();

  boolean supports(ChangedFile file);

  List<Limitation> limitationsFor(Path repositoryRoot, List<ChangedFile> changedFiles);

  AnalysisResult analyze(
      List<ChangedFile> changedFiles, Map<String, String> headFiles, Map<String, String> baseFiles);

  default AnalysisResult analyze(
      List<ChangedFile> changedFiles,
      Map<String, String> headFiles,
      Map<String, String> baseFiles,
      AnalysisOptions options) {
    return analyze(changedFiles, headFiles, baseFiles);
  }
}
