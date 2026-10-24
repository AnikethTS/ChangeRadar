package io.github.anikethts.changeradar.analyzer.sql;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.FormatOnly;
import io.github.anikethts.changeradar.core.FormatTrivia;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.LanguageAnalyzer;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.LineRange;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL analyzer: maps changed lines to tables, views, functions, and procedures, then finds other
 * {@code .sql} files that name those objects. This is not a database parser. Application strings
 * are not scanned.
 */
public final class SqlLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "sql";

  @Override
  public String languageId() {
    return LANGUAGE_ID;
  }

  @Override
  public boolean supports(ChangedFile file) {
    return file.isSqlSource();
  }

  @Override
  public List<Limitation> limitationsFor(Path repositoryRoot, List<ChangedFile> changedFiles) {
    return List.of();
  }

  @Override
  public AnalysisResult analyze(
      List<ChangedFile> changedFiles,
      Map<String, String> headFiles,
      Map<String, String> baseFiles) {
    List<Limitation> limitations = new ArrayList<>();
    List<ChangedSymbol> changedSymbols = new ArrayList<>();
    List<String> formatOnly = new ArrayList<>();
    Map<String, ParsedFile> parsedHead = new LinkedHashMap<>();

    for (Map.Entry<String, String> entry : headFiles.entrySet()) {
      if (!SqlSupport.isSqlPath(entry.getKey())) {
        continue;
      }
      ParsedFile parsed = SqlParsing.parse(entry.getKey(), entry.getValue(), limitations);
      if (parsed != null) {
        parsedHead.put(entry.getKey(), parsed);
      }
    }

    for (ChangedFile changed : changedFiles) {
      if (!supports(changed)) {
        continue;
      }
      if (changed.changeType() == ChangeType.DELETE) {
        ParsedFile baseFile = SqlParsing.parseBase(changed.path(), baseFiles, limitations);
        if (baseFile != null) {
          for (Decl decl : baseFile.decls()) {
            changedSymbols.add(decl.toSymbol(ChangeType.DELETE));
          }
        }
        continue;
      }
      ParsedFile file = parsedHead.get(changed.path());
      if (file == null) {
        continue;
      }
      if (changed.changeType() == ChangeType.MODIFY) {
        String basePath = changed.previousPath() != null ? changed.previousPath() : changed.path();
        if (FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.SQL, baseFiles.get(basePath), headFiles.get(changed.path()))) {
          formatOnly.add(changed.path());
          continue;
        }
      }
      List<LineRange> hunks = changed.headLineRanges();
      boolean wholeFile = hunks.isEmpty() || changed.changeType() == ChangeType.ADD;
      Set<String> headKeys = new LinkedHashSet<>();
      for (Decl decl : file.decls()) {
        headKeys.add(decl.identity());
        if (wholeFile || SqlSupport.overlaps(hunks, decl.startLine(), decl.endLine())) {
          ChangeType type =
              changed.changeType() == ChangeType.ADD ? ChangeType.ADD : ChangeType.MODIFY;
          changedSymbols.add(decl.toSymbol(type));
        }
      }
      if (changed.changeType() == ChangeType.MODIFY || changed.changeType() == ChangeType.RENAME) {
        ParsedFile baseFile =
            SqlParsing.parseBase(
                changed.previousPath() != null ? changed.previousPath() : changed.path(),
                baseFiles,
                limitations);
        if (baseFile != null) {
          for (Decl decl : baseFile.decls()) {
            if (!headKeys.contains(decl.identity())) {
              changedSymbols.add(decl.toSymbol(ChangeType.DELETE));
            }
          }
        }
      }
    }

    if (!formatOnly.isEmpty()) {
      limitations.add(FormatOnly.limitation(formatOnly));
    }

    changedSymbols.sort(
        Comparator.comparing((ChangedSymbol symbol) -> symbol.location().path())
            .thenComparing(symbol -> symbol.location().startLine())
            .thenComparing(ChangedSymbol::id));

    List<ImpactFinding> findings =
        new ArrayList<>(SqlCallers.findCallers(parsedHead, changedSymbols));
    findings.sort(
        Comparator.comparing((ImpactFinding finding) -> finding.location().path())
            .thenComparing(finding -> finding.location().startLine())
            .thenComparing(ImpactFinding::summary));

    List<TestRecommendation> tests = SqlRecommendations.recommendTests(parsedHead, findings);
    tests.sort(
        Comparator.comparing(TestRecommendation::path).thenComparing(TestRecommendation::name));

    boolean anySql = !parsedHead.isEmpty() || changedFiles.stream().anyMatch(this::supports);
    if (anySql) {
      limitations.add(
          new Limitation(
              "SQL_REFERENCES",
              "SQL references match table, view, function, and procedure names in other .sql"
                  + " files after comments and string literals are removed. Matching is"
                  + " case-insensitive and does not use a database. A name declared more than"
                  + " once is HEURISTIC. Embedded SQL in application code is not scanned."
                  + " Dollar-quoted bodies may be missed."));
    }

    return new AnalysisResult(changedSymbols, findings, tests, limitations);
  }
}
