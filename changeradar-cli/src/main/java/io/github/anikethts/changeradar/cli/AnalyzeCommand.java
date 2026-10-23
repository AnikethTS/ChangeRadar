package io.github.anikethts.changeradar.cli;

import io.github.anikethts.changeradar.analyzer.java.JavaLanguageAnalyzer;
import io.github.anikethts.changeradar.analyzer.kt.KotlinLanguageAnalyzer;
import io.github.anikethts.changeradar.analyzer.php.PhpLanguageAnalyzer;
import io.github.anikethts.changeradar.analyzer.py.PythonLanguageAnalyzer;
import io.github.anikethts.changeradar.analyzer.ts.TypeScriptLanguageAnalyzer;
import io.github.anikethts.changeradar.core.AnalysisOptions;
import io.github.anikethts.changeradar.core.AnalysisRequest;
import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbols;
import io.github.anikethts.changeradar.core.ExitCode;
import io.github.anikethts.changeradar.core.FindingSuppressions;
import io.github.anikethts.changeradar.core.GitAccessException;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.LanguageAnalyzer;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.PolicyEngine;
import io.github.anikethts.changeradar.core.PolicyFile;
import io.github.anikethts.changeradar.core.PolicyResult;
import io.github.anikethts.changeradar.core.Report;
import io.github.anikethts.changeradar.core.SourceScope;
import io.github.anikethts.changeradar.core.TestEvidence;
import io.github.anikethts.changeradar.core.TestRecommendation;
import io.github.anikethts.changeradar.core.TsConfigPaths;
import io.github.anikethts.changeradar.core.Version;
import io.github.anikethts.changeradar.git.JGitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "analyze", description = "Compare two Git revisions and write an impact report.")
public final class AnalyzeCommand implements Callable<Integer> {

  private static final List<String> SOURCE_SUFFIXES =
      List.of(
          ".java", ".kt", ".kts", ".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs",
          ".py", ".pyi", ".php");

  private static final Set<String> FORMATS =
      Set.of("terminal", "markdown", "json", "sarif", "compact", "github");

  @Option(
      names = "--base",
      required = true,
      description = "Base Git ref or SHA (the revision the change is compared against).")
  String base;

  @Option(
      names = "--head",
      required = true,
      description = "Head Git ref or SHA (the revision that contains the change).")
  String head;

  @Option(
      names = "--repo",
      defaultValue = ".",
      description = "Path to the Git repository. Default: current directory.")
  Path repo;

  @Option(
      names = "--format",
      defaultValue = "terminal",
      split = ",",
      description =
          "Comma-separated formats: terminal, markdown, json, compact, sarif, github. Default:"
              + " terminal.")
  List<String> formats;

  @Option(names = "--output", description = "Directory for report files. Created if missing.")
  Path output;

  @Option(
      names = "--policy",
      split = ",",
      description =
          "Named blocking policies to enable (off by default). Repeatable or comma-separated."
              + " See `changeradar policies`.")
  List<String> policies;

  @Option(
      names = "--policy-file",
      description = "YAML file of enabled policies and optional baseline/suppressions paths.")
  Path policyFile;

  @Option(
      names = "--baseline",
      description =
          "Existing policy baseline (one violation key per line). Overrides baseline in"
              + " --policy-file.")
  Path baseline;

  @Option(
      names = "--write-baseline",
      description =
          "Write current policy violation keys to this file and do not fail the job for those"
              + " keys this run.")
  Path writeBaseline;

  @Option(
      names = "--suppress-file",
      description =
          "Existing finding suppressions (one finding id per line). Overrides suppressions in"
              + " --policy-file.")
  Path suppressFile;

  @Option(
      names = "--write-suppressions",
      description =
          "Write current finding ids to this file and omit those findings from this run's"
              + " report.")
  Path writeSuppressions;

  @Option(
      names = "--classpath",
      description =
          "Already-built jars or directories of jars for Java type resolution. Does not run the"
              + " target build. Repeatable. Each value may be a "
              + "path-separator-separated list.")
  List<String> classpath;

  @Option(
      names = "--coverage",
      description =
          "JaCoCo XML or LCOV file, or a directory of those files. Suite-wide coverage is"
              + " intersected with changed lines. Does not run tests. Repeatable.")
  List<String> coverage;

  @Option(
      names = "--junit",
      description =
          "JUnit XML file (Surefire/Gradle/pytest/jest-junit) or a directory of TEST-*.xml."
              + " Annotates recommended tests with pass/fail. Does not run tests. Repeatable.")
  List<String> junit;

  @Option(
      names = "--full-index",
      description = "Parse every HEAD source file instead of changed files plus importers.")
  boolean fullIndex;

  private final Clock clock;
  private final ReportWriters writers;

  public AnalyzeCommand() {
    this(Clock.systemUTC(), new ReportWriters());
  }

  AnalyzeCommand(Clock clock, ReportWriters writers) {
    this.clock = clock;
    this.writers = writers;
  }

  @Override
  public Integer call() throws Exception {
    Path repository = repo.toAbsolutePath().normalize();
    if (!Files.isDirectory(repository)) {
      throw new GitAccessException("Repository path is not a directory: " + repository);
    }
    Set<String> enabledPolicies = new LinkedHashSet<>();
    Path baselinePath = baseline;
    Path suppressPath = suppressFile;
    if (policyFile != null) {
      Path resolvedPolicy = repository.resolve(policyFile).normalize();
      PolicyFile.Document document = PolicyFile.load(resolvedPolicy);
      enabledPolicies.addAll(document.policies());
      if (baselinePath == null && document.baseline() != null) {
        baselinePath = repository.resolve(document.baseline()).normalize();
      }
      if (suppressPath == null && document.suppressions() != null) {
        suppressPath = repository.resolve(document.suppressions()).normalize();
      }
    }
    enabledPolicies.addAll(PolicyEngine.parseEnabled(policies));

    Report report;
    try (var git = JGitRepository.open(repository)) {
      git.resolve(base);
      git.resolve(head);
      List<ChangedFile> changed = git.diff(base, head);
      List<LanguageAnalyzer> analyzers =
          List.of(
              new JavaLanguageAnalyzer(),
              new KotlinLanguageAnalyzer(),
              new TypeScriptLanguageAnalyzer(),
              new PythonLanguageAnalyzer(),
              new PhpLanguageAnalyzer());
      Map<String, String> headSources = new LinkedHashMap<>();
      for (String suffix : SOURCE_SUFFIXES) {
        for (String path : git.listPaths(head, suffix)) {
          git.readFile(head, path).ifPresent(source -> headSources.put(path, source));
        }
      }
      for (String path : git.listPaths(head, ".json")) {
        if (TsConfigPaths.isConfigPath(path)) {
          git.readFile(head, path).ifPresent(source -> headSources.put(path, source));
        }
      }
      boolean scoped = !fullIndex;
      Map<String, String> parsedHead =
          scoped ? SourceScope.subset(changed, headSources) : headSources;
      Map<String, String> baseSources = new LinkedHashMap<>();
      for (ChangedFile file : changed) {
        if (file.isJavaSource()
            || file.isKotlinSource()
            || file.isEcmaSource()
            || file.isPythonSource()
            || file.isPhpSource()) {
          git.readFile(base, file.path()).ifPresent(source -> baseSources.put(file.path(), source));
          if (file.previousPath() != null) {
            git.readFile(base, file.previousPath())
                .ifPresent(source -> baseSources.put(file.previousPath(), source));
          }
        }
      }
      AnalysisOptions options = new AnalysisOptions(parseClasspath(repository), scoped);
      List<AnalysisResult> parts = new ArrayList<>();
      for (LanguageAnalyzer analyzer : analyzers) {
        parts.add(
            analyzer.analyze(
                changed,
                filterSources(parsedHead, analyzer),
                filterSources(baseSources, analyzer),
                options));
      }
      AnalysisResult merged = AnalysisResult.merge(parts);
      ChangedSymbols.Result coalesced = ChangedSymbols.coalesce(merged.changedSymbols());
      List<Limitation> analysisLimitations = new ArrayList<>(merged.limitations());
      List<TestRecommendation> tests = merged.tests();
      List<Path> coverageFiles = TestEvidence.expand(repository, coverage, "coverage");
      List<Path> junitFiles = TestEvidence.expand(repository, junit, "junit");
      if (!coverageFiles.isEmpty() || !junitFiles.isEmpty()) {
        TestEvidence.Result evidence =
            TestEvidence.apply(tests, changed, coverageFiles, junitFiles);
        tests = evidence.tests();
        analysisLimitations.addAll(evidence.limitations());
      }
      AnalysisResult analysis =
          new AnalysisResult(coalesced.symbols(), merged.findings(), tests, analysisLimitations);
      Set<String> baselineKeys =
          baselinePath == null ? Set.of() : PolicyFile.readBaseline(baselinePath);
      PolicyResult raw =
          PolicyEngine.evaluate(
              enabledPolicies, analysis, changed, Set.of(), coalesced.signatureChanges());
      if (writeBaseline != null) {
        Path dest = repository.resolve(writeBaseline).normalize();
        PolicyFile.writeBaseline(dest, raw.violations());
      }
      PolicyResult policy =
          PolicyEngine.evaluate(
              enabledPolicies, analysis, changed, baselineKeys, coalesced.signatureChanges());
      if (writeBaseline != null) {
        policy = PolicyResult.none();
      }
      Set<String> suppressed = new LinkedHashSet<>();
      if (suppressPath != null) {
        suppressed.addAll(PolicyFile.readBaseline(suppressPath));
      }
      if (writeSuppressions != null) {
        Path dest = repository.resolve(writeSuppressions).normalize();
        List<String> ids = analysis.findings().stream().map(ImpactFinding::id).toList();
        PolicyFile.writeSuppressions(dest, ids);
        suppressed.addAll(ids);
      }
      List<ImpactFinding> findings = FindingSuppressions.apply(analysis.findings(), suppressed);
      List<Limitation> limitations = new ArrayList<>(analysis.limitations());
      if (scoped && parsedHead.size() < headSources.size()) {
        limitations.add(
            new Limitation(
                "SCOPED_SOURCES",
                "Parsers ran on "
                    + parsedHead.size()
                    + " of "
                    + headSources.size()
                    + " HEAD source files (changed paths, same-directory Java neighbors, and"
                    + " import/require/use importers). Pass --full-index to parse everything."));
      }
      if (enabledPolicies.isEmpty()) {
        limitations.add(
            new Limitation(
                "POLICIES_DISABLED",
                "No blocking policies were enabled. Exit 0 means analysis completed, not that the"
                    + " change is safe. Pass --policy or --policy-file to opt in."));
      } else if (baselinePath != null && Files.isRegularFile(baselinePath)) {
        limitations.add(
            new Limitation(
                "POLICY_BASELINE",
                "Policy violations listed in "
                    + baselinePath
                    + " are ignored. Re-run with --write-baseline to refresh."));
      }
      if (!suppressed.isEmpty()) {
        limitations.add(
            new Limitation(
                "FINDING_SUPPRESSIONS",
                "Findings listed in "
                    + (suppressPath != null ? suppressPath : writeSuppressions)
                    + " are omitted from the report. Re-run with --write-suppressions to"
                    + " refresh. Line shifts can invalidate ids."));
      }
      if (enabledPolicies.contains(PolicyEngine.FAILED_RELATED_TESTS) && junitFiles.isEmpty()) {
        limitations.add(
            new Limitation(
                "FAILED_RELATED_TESTS_NO_JUNIT",
                "Policy failed-related-tests is enabled but --junit was not passed. The rule did"
                    + " not fire. Provide JUnit XML from a test job you already ran."));
      }
      limitations.add(
          new Limitation(
              "STATIC_ANALYSIS_LIMITS",
              "Reflection, dynamic dispatch, generated code, dependency injection, and external"
                  + " services are not fully resolvable. Absence of a finding is not proof of no"
                  + " impact."));
      report =
          new Report(
              Report.SCHEMA_VERSION,
              Version.current(),
              Instant.now(clock),
              new AnalysisRequest(repository, base, head),
              changed,
              analysis.changedSymbols(),
              findings,
              analysis.tests(),
              List.copyOf(limitations),
              policy);
    }

    Set<String> selected = selectedFormats();
    if (selected.contains("terminal")) {
      writers.writeTerminal(report, System.out);
    }
    boolean files =
        selected.contains("markdown")
            || selected.contains("json")
            || selected.contains("sarif")
            || selected.contains("compact")
            || selected.contains("github");
    Path dest = output == null ? repository.resolve("build").resolve("changeradar") : output;
    if (files) {
      Files.createDirectories(dest);
      if (selected.contains("markdown")) {
        writers.writeMarkdown(report, dest.resolve("report.md"));
      }
      if (selected.contains("json")) {
        writers.writeJson(report, dest.resolve("report.json"));
      }
      if (selected.contains("compact")) {
        writers.writeCompact(report, dest.resolve("report.compact.md"));
      }
      if (selected.contains("sarif")) {
        writers.writeSarif(report, dest.resolve("report.sarif"));
      }
      if (selected.contains("github")) {
        writers.writeGithub(report, dest.resolve("report.github.json"));
      }
    }
    String summary = System.getenv("GITHUB_STEP_SUMMARY");
    if (summary != null && !summary.isBlank()) {
      Path summaryPath = Path.of(summary);
      Files.writeString(
          summaryPath,
          writers.compactMarkdown(report),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
    return report.policy().blocking() ? ExitCode.POLICY_VIOLATION.code() : ExitCode.SUCCESS.code();
  }

  private static Map<String, String> filterSources(
      Map<String, String> sources, LanguageAnalyzer analyzer) {
    Map<String, String> filtered = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      ChangedFile asFile = new ChangedFile(entry.getKey(), ChangeType.MODIFY, null);
      if (analyzer.supports(asFile)
          || (TsConfigPaths.isConfigPath(entry.getKey())
              && TypeScriptLanguageAnalyzer.LANGUAGE_ID.equals(analyzer.languageId()))) {
        filtered.put(entry.getKey(), entry.getValue());
      }
    }
    return filtered;
  }

  private List<Path> parseClasspath(Path repository) {
    List<Path> jars = new ArrayList<>();
    if (classpath == null) {
      return jars;
    }
    for (String raw : classpath) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      for (String part : raw.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
        if (part.isBlank()) {
          continue;
        }
        Path path = Path.of(part);
        jars.add(path.isAbsolute() ? path : repository.resolve(path));
      }
    }
    return jars;
  }

  private Set<String> selectedFormats() {
    Set<String> selected = new LinkedHashSet<>();
    for (String format : formats) {
      String normalized = format.trim().toLowerCase();
      if (!FORMATS.contains(normalized)) {
        throw new GitAccessException("Unsupported --format value: " + format);
      }
      selected.add(normalized);
    }
    return selected;
  }
}
