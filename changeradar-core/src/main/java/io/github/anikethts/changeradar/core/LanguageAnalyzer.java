package io.github.anikethts.changeradar.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Language-neutral analyzer contract. Java is the only implementation in the MVP. Future TypeScript
 * and Python adapters should map into the same report model rather than inventing a parallel one.
 */
public interface LanguageAnalyzer {

  String languageId();

  boolean supports(ChangedFile file);

  List<Limitation> limitationsFor(Path repositoryRoot, List<ChangedFile> changedFiles);
}
