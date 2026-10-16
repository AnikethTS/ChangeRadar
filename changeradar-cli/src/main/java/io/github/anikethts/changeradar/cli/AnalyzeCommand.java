package io.github.anikethts.changeradar.cli;

import io.github.anikethts.changeradar.analyzer.java.JavaLanguageAnalyzer;
import io.github.anikethts.changeradar.analyzer.php.PhpLanguageAnalyzer;
import io.github.anikethts.changeradar.analyzer.py.PythonLanguageAnalyzer;
import io.github.anikethts.changeradar.analyzer.ts.TypeScriptLanguageAnalyzer;
import io.github.anikethts.changeradar.core.AnalysisRequest;
import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ExitCode;
import io.github.anikethts.changeradar.core.GitAccessException;
import io.github.anikethts.changeradar.core.LanguageAnalyzer;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.PolicyEngine;
import io.github.anikethts.changeradar.core.PolicyResult;
import io.github.anikethts.changeradar.core.Report;
import io.github.anikethts.changeradar.core.Version;
import io.github.anikethts.changeradar.git.JGitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
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
          ".java", ".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs", ".py", ".pyi",
          ".php");

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
      description = "Comma-separated formats: terminal, markdown, json. Default: terminal.")
  List<String> formats;

  @Option(
      names = "--output",
      description = "Directory for markdown/json report files. Created if missing.")
  Path output;

  @Option(
      names = "--policy",
      split = ",",
      description =
          "Named blocking policies to enable (off by default): breaking-public-java-api,"
              + " breaking-public-export, breaking-public-python, breaking-public-php,"
              + " analysis-unavailable. Repeatable or comma-separated.")
  List<String> policies;

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
    Set<String> enabledPolicies = PolicyEngine.parseEnabled(policies);

    Report report;
    try (var git = JGitRepository.open(repository)) {
      git.resolve(base);
      git.resolve(head);
      List<ChangedFile> changed = git.diff(base, head);
      List<LanguageAnalyzer> analyzers =
          List.of(
              new JavaLanguageAnalyzer(),
              new TypeScriptLanguageAnalyzer(),
              new PythonLanguageAnalyzer(),
              new PhpLanguageAnalyzer());
      Map<String, String> headSources = new LinkedHashMap<>();
      for (String suffix : SOURCE_SUFFIXES) {
        for (String path : git.listPaths(head, suffix)) {
          git.readFile(head, path).ifPresent(source -> headSources.put(path, source));
        }
      }
      Map<String, String> baseSources = new LinkedHashMap<>();
      for (ChangedFile file : changed) {
        if (file.isJavaSource()
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
      List<AnalysisResult> parts = new ArrayList<>();
      for (LanguageAnalyzer analyzer : analyzers) {
        parts.add(
            analyzer.analyze(
                changed,
                filterSources(headSources, analyzer),
                filterSources(baseSources, analyzer)));
      }
      AnalysisResult analysis = AnalysisResult.merge(parts);
      PolicyResult policy = PolicyEngine.evaluate(enabledPolicies, analysis, changed);
      List<Limitation> limitations = new ArrayList<>(analysis.limitations());
      if (enabledPolicies.isEmpty()) {
        limitations.add(
            new Limitation(
                "POLICIES_DISABLED",
                "No blocking policies were enabled. Exit 0 means analysis completed, not that the"
                    + " change is safe. Pass --policy to opt in."));
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
              analysis.findings(),
              analysis.tests(),
              List.copyOf(limitations),
              policy);
    }

    Set<String> selected = selectedFormats();
    if (selected.contains("terminal")) {
      writers.writeTerminal(report, System.out);
    }
    if (selected.contains("markdown") || selected.contains("json")) {
      Path dest = output == null ? repository.resolve("build").resolve("changeradar") : output;
      Files.createDirectories(dest);
      if (selected.contains("markdown")) {
        writers.writeMarkdown(report, dest.resolve("report.md"));
      }
      if (selected.contains("json")) {
        writers.writeJson(report, dest.resolve("report.json"));
      }
    }
    return report.policy().blocking() ? ExitCode.POLICY_VIOLATION.code() : ExitCode.SUCCESS.code();
  }

  private static Map<String, String> filterSources(
      Map<String, String> sources, LanguageAnalyzer analyzer) {
    Map<String, String> filtered = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      if (analyzer.supports(new ChangedFile(entry.getKey(), ChangeType.MODIFY, null))) {
        filtered.put(entry.getKey(), entry.getValue());
      }
    }
    return filtered;
  }

  private Set<String> selectedFormats() {
    Set<String> selected = new LinkedHashSet<>();
    for (String format : formats) {
      String normalized = format.trim().toLowerCase();
      if (!Set.of("terminal", "markdown", "json").contains(normalized)) {
        throw new GitAccessException("Unsupported --format value: " + format);
      }
      selected.add(normalized);
    }
    return selected;
  }
}
