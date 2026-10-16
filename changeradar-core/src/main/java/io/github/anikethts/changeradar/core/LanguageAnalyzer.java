package io.github.anikethts.changeradar.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Language-neutral analyzer contract. Java and TypeScript/JavaScript adapters map into the same
 * report model. Python is next.
 */
public interface LanguageAnalyzer {

  String languageId();

  boolean supports(ChangedFile file);

  List<Limitation> limitationsFor(Path repositoryRoot, List<ChangedFile> changedFiles);

  AnalysisResult analyze(
      List<ChangedFile> changedFiles, Map<String, String> headFiles, Map<String, String> baseFiles);
}
