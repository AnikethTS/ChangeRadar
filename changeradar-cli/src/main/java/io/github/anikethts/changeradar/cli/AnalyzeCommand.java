package io.github.anikethts.changeradar.cli;

import io.github.anikethts.changeradar.analyzer.java.JavaLanguageAnalyzer;
import io.github.anikethts.changeradar.core.AnalysisRequest;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ExitCode;
import io.github.anikethts.changeradar.core.GitAccessException;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.PolicyResult;
import io.github.anikethts.changeradar.core.Report;
import io.github.anikethts.changeradar.core.Version;
import io.github.anikethts.changeradar.git.JGitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "analyze", description = "Compare two Git revisions and write an impact report.")
public final class AnalyzeCommand implements Callable<Integer> {

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

    Report report;
    try (var git = JGitRepository.open(repository)) {
      git.resolve(base);
      git.resolve(head);
      List<ChangedFile> changed = git.diff(base, head);
      JavaLanguageAnalyzer javaAnalyzer = new JavaLanguageAnalyzer();
      List<Limitation> limitations =
          new ArrayList<>(javaAnalyzer.limitationsFor(repository, changed));
      limitations.add(
          new Limitation(
              "NO_POLICY_ENGINE",
              "Blocking policies are not evaluated yet. This run cannot fail with exit code 1."));
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
              List.copyOf(limitations),
              PolicyResult.none());
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
    return ExitCode.SUCCESS.code();
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
