package io.github.anikethts.changeradar.analyzer.java;

import com.github.javaparser.JavaParser;
import io.github.anikethts.changeradar.core.AnalysisOptions;
import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.CallChains;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.LanguageAnalyzer;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.LineRange;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java analyzer: maps changed lines to types and methods, then finds callers. In-repo types plus
 * the JRE are resolved with JavaParser's symbol solver (DIRECT). Resolved callers of those callers
 * are INDIRECT, capped by depth and count. Unresolved calls fall back to name matching (HEURISTIC)
 * and are not chained. Overrides, field reads, annotation uses, and inject-style fields are extra
 * structural findings. Compiled Maven/Gradle dependencies are not on the type path.
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
    return List.of();
  }

  @Override
  public AnalysisResult analyze(
      List<ChangedFile> changedFiles,
      Map<String, String> headJavaFiles,
      Map<String, String> baseJavaFiles) {
    return analyze(changedFiles, headJavaFiles, baseJavaFiles, AnalysisOptions.defaults());
  }

  @Override
  public AnalysisResult analyze(
      List<ChangedFile> changedFiles,
      Map<String, String> headJavaFiles,
      Map<String, String> baseJavaFiles,
      AnalysisOptions options) {
    List<Limitation> limitations = new ArrayList<>();
    List<ChangedSymbol> changedSymbols = new ArrayList<>();
    Map<String, ParsedFile> parsedHead = new LinkedHashMap<>();
    Path solverRoot = null;
    JavaParser headParser = JavaParsing.PARSER;
    if (!headJavaFiles.isEmpty()) {
      try {
        solverRoot = JavaParsing.materializeSources(headJavaFiles);
        headParser = JavaParsing.parserWithSolver(solverRoot, headJavaFiles, options.classpath());
        if (!options.classpath().isEmpty()) {
          limitations.add(
              new Limitation(
                  "JAVA_CLASSPATH",
                  "Java callers also resolve types from "
                      + options.classpath().size()
                      + " provided jar/directory entries. ChangeRadar did not run the target"
                      + " build."));
        }
        String solverScope =
            options.classpath().isEmpty()
                ? "HEAD sources and the JRE. Types that exist only in compiled dependencies are"
                    + " not on the path"
                : "HEAD sources, the JRE, and --classpath jars. Types still missing from that path";
        limitations.add(
            new Limitation(
                "JAVA_SYMBOL_SOLVER",
                "Java callers are type-resolved against "
                    + solverScope
                    + "; resolved sites match a specific overload signature. Unresolved sites"
                    + " fall back to name matching (HEURISTIC)."));
        limitations.add(
            new Limitation(
                "JAVA_CALL_CHAINS",
                "Indirect Java callers follow type-resolved method and constructor edges up to "
                    + CallChains.MAX_DEPTH
                    + " hops (depth 1 DIRECT, deeper INDIRECT). Heuristic name matches are not"
                    + " chained. Indirect findings are capped at "
                    + CallChains.MAX_INDIRECT
                    + "."));
        limitations.add(
            new Limitation(
                "JAVA_STRUCTURAL_EDGES",
                "Java overrides, field access, annotation uses, and inject-style fields"
                    + " (@Autowired/@Inject/@Resource) are resolved against HEAD sources and the"
                    + " JRE only. Spring/Jakarta containers are not simulated."));
      } catch (IOException e) {
        limitations.add(
            new Limitation(
                "JAVA_SYMBOL_SOLVER",
                "Could not set up the Java symbol solver ("
                    + e.getMessage()
                    + "); callers fall back to name matching."));
      }
    }

    try {
      for (Map.Entry<String, String> entry : headJavaFiles.entrySet()) {
        JavaParsing.parse(headParser, entry.getKey(), entry.getValue(), parsedHead, limitations);
      }

      for (ChangedFile changed : changedFiles) {
        if (!changed.isJavaSource()) {
          continue;
        }
        if (changed.changeType() == ChangeType.DELETE) {
          ParsedFile baseFile = JavaParsing.parseBase(changed.path(), baseJavaFiles, limitations);
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
        List<LineRange> hunks = changed.headLineRanges();
        boolean wholeFile = hunks.isEmpty() || changed.changeType() == ChangeType.ADD;
        Set<String> headKeys = new LinkedHashSet<>();
        for (Decl decl : file.decls()) {
          headKeys.add(decl.identity());
          if (wholeFile || JavaSupport.overlaps(hunks, decl.startLine(), decl.endLine())) {
            ChangeType type =
                changed.changeType() == ChangeType.ADD ? ChangeType.ADD : ChangeType.MODIFY;
            changedSymbols.add(decl.toSymbol(type));
          }
        }
        if (changed.changeType() == ChangeType.MODIFY
            || changed.changeType() == ChangeType.RENAME) {
          ParsedFile baseFile =
              JavaParsing.parseBase(
                  changed.previousPath() != null ? changed.previousPath() : changed.path(),
                  baseJavaFiles,
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

      changedSymbols.sort(
          Comparator.comparing((ChangedSymbol s) -> s.location().path())
              .thenComparing(s -> s.location().startLine())
              .thenComparing(ChangedSymbol::id));

      CallerScan callers = JavaCallers.findCallers(parsedHead, changedSymbols);
      if (callers.chainCapped()) {
        limitations.add(
            new Limitation(
                "JAVA_CALL_CHAIN_CAP",
                "Indirect Java callers were capped at "
                    + CallChains.MAX_INDIRECT
                    + "; later hops in the graph are omitted."));
      }
      List<ImpactFinding> findings = new ArrayList<>(callers.findings());
      findings.addAll(JavaRecommendations.publicApiFindings(changedSymbols));
      findings.sort(
          Comparator.comparing((ImpactFinding f) -> f.location().path())
              .thenComparing(f -> f.location().startLine())
              .thenComparing(ImpactFinding::summary));

      List<TestRecommendation> tests =
          JavaRecommendations.recommendTests(parsedHead, changedSymbols, findings);
      tests.sort(
          Comparator.comparing(TestRecommendation::path).thenComparing(TestRecommendation::name));

      if (findings.stream()
          .anyMatch(
              finding ->
                  "CALLER".equals(finding.type())
                      && finding.confidence() == Confidence.HEURISTIC)) {
        limitations.add(
            new Limitation(
                "NAME_BASED_DISPATCH",
                "Some Java call sites could not be type-resolved (missing dependency types,"
                    + " reflection, or incomplete HEAD sources) and were matched by method name"
                    + " only. Those matches are labeled HEURISTIC."));
      }

      return new AnalysisResult(changedSymbols, findings, tests, limitations);
    } finally {
      JavaParsing.deleteRecursively(solverRoot);
    }
  }

  static String inferSourceRoot(String path, String source) {
    return JavaParsing.inferSourceRoot(path, source);
  }
}
