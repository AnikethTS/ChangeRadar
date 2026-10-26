package io.github.anikethts.changeradar.analyzer.workflow;

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
import io.github.anikethts.changeradar.core.SourceLocation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GitHub Actions analyzer: changed jobs, reusable workflows, and local actions. Other workflow
 * files that {@code needs} a job or {@code uses} a local path are callers. Remote actions are not
 * resolved, and the workflow is not run.
 */
public final class WorkflowLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "workflow";

  @Override
  public String languageId() {
    return LANGUAGE_ID;
  }

  @Override
  public boolean supports(ChangedFile file) {
    return file.isWorkflowSource();
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
    Map<String, ParsedWorkflow> parsedHead = new LinkedHashMap<>();
    Map<String, String> previousPaths = new LinkedHashMap<>();

    for (Map.Entry<String, String> entry : headFiles.entrySet()) {
      if (!WorkflowSupport.isWorkflowPath(entry.getKey())) {
        continue;
      }
      ParsedWorkflow parsed = WorkflowParsing.parse(entry.getKey(), entry.getValue(), limitations);
      if (parsed != null) {
        parsedHead.put(parsed.path(), parsed);
      }
    }

    for (ChangedFile changed : changedFiles) {
      if (!supports(changed)) {
        continue;
      }
      String path = changed.path().replace('\\', '/');
      if (changed.previousPath() != null) {
        previousPaths.put(path, changed.previousPath().replace('\\', '/'));
      }
      if (changed.changeType() == ChangeType.DELETE) {
        addDeleted(changed, baseFiles, limitations, changedSymbols);
        continue;
      }
      ParsedWorkflow file = parsedHead.get(path);
      if (file == null) {
        continue;
      }
      if (changed.changeType() == ChangeType.MODIFY
          && FormatTrivia.sameIgnoringTrivia(
              FormatTrivia.Dialect.YAML,
              baseFiles.get(changed.previousPath() != null ? changed.previousPath() : path),
              headFiles.get(changed.path()))) {
        formatOnly.add(changed.path());
        continue;
      }
      List<LineRange> hunks = changed.headLineRanges();
      boolean wholeFile = hunks.isEmpty() || changed.changeType() == ChangeType.ADD;
      ChangeType type = changed.changeType() == ChangeType.ADD ? ChangeType.ADD : ChangeType.MODIFY;
      Set<String> headKeys = new LinkedHashSet<>();
      boolean jobChanged = false;
      for (Job job : file.jobs()) {
        String key = "job#" + job.name();
        headKeys.add(key);
        if (wholeFile || WorkflowSupport.overlaps(hunks, job.startLine(), job.endLine())) {
          changedSymbols.add(jobSymbol(file.path(), job, type));
          jobChanged = true;
        }
      }
      if (file.actionFile()) {
        changedSymbols.add(actionSymbol(file, type, headFiles.get(changed.path())));
        headKeys.add("action");
      } else if (file.callable() && (wholeFile || jobChanged || overlapsCall(file, hunks))) {
        changedSymbols.add(workflowSymbol(file, type, headFiles.get(changed.path())));
        headKeys.add("workflow");
      } else if (!file.callable()
          && file.onStart() > 0
          && (wholeFile || WorkflowSupport.overlaps(hunks, file.onStart(), file.onEnd()))) {
        changedSymbols.add(triggerSymbol(file, type));
        headKeys.add("trigger");
      }
      if (changed.changeType() == ChangeType.MODIFY || changed.changeType() == ChangeType.RENAME) {
        addRemoved(changed, baseFiles, limitations, changedSymbols, headKeys);
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
        new ArrayList<>(WorkflowCallers.findCallers(parsedHead, changedSymbols, previousPaths));
    findings.sort(
        Comparator.comparing((ImpactFinding finding) -> finding.location().path())
            .thenComparing(finding -> finding.location().startLine())
            .thenComparing(ImpactFinding::summary));

    boolean anyWorkflow = !parsedHead.isEmpty() || changedFiles.stream().anyMatch(this::supports);
    if (anyWorkflow) {
      limitations.add(
          new Limitation(
              "WORKFLOW_REFERENCES",
              "Workflow references are local uses paths (./ from the repository root) and"
                  + " same-file needs. Remote actions, matrices, workflow_call inputs, and"
                  + " reusable-workflow secrets are not resolved. The workflow is not run."
                  + " A # inside a literal block scalar is treated as a comment."));
    }

    return new AnalysisResult(changedSymbols, findings, List.of(), limitations);
  }

  private static void addDeleted(
      ChangedFile changed,
      Map<String, String> baseFiles,
      List<Limitation> limitations,
      List<ChangedSymbol> changedSymbols) {
    String basePath = changed.previousPath() != null ? changed.previousPath() : changed.path();
    String source = baseFiles.get(basePath);
    if (source == null) {
      return;
    }
    ParsedWorkflow file = WorkflowParsing.parse(basePath, source, limitations);
    if (file == null) {
      return;
    }
    for (Job job : file.jobs()) {
      changedSymbols.add(jobSymbol(file.path(), job, ChangeType.DELETE));
    }
    if (file.actionFile()) {
      changedSymbols.add(actionSymbol(file, ChangeType.DELETE, source));
    } else if (file.callable()) {
      changedSymbols.add(workflowSymbol(file, ChangeType.DELETE, source));
    }
  }

  private static void addRemoved(
      ChangedFile changed,
      Map<String, String> baseFiles,
      List<Limitation> limitations,
      List<ChangedSymbol> changedSymbols,
      Set<String> headKeys) {
    String basePath = changed.previousPath() != null ? changed.previousPath() : changed.path();
    String source = baseFiles.get(basePath);
    if (source == null) {
      return;
    }
    ParsedWorkflow baseFile = WorkflowParsing.parse(basePath, source, limitations);
    if (baseFile == null) {
      return;
    }
    for (Job job : baseFile.jobs()) {
      if (!headKeys.contains("job#" + job.name())) {
        changedSymbols.add(jobSymbol(changed.path(), job, ChangeType.DELETE));
      }
    }
  }

  private static boolean overlapsCall(ParsedWorkflow file, List<LineRange> hunks) {
    return file.onStart() > 0 && WorkflowSupport.overlaps(hunks, file.onStart(), file.onEnd());
  }

  private static ChangedSymbol jobSymbol(String path, Job job, ChangeType type) {
    return new ChangedSymbol(
        path + ":job#" + job.name() + "@" + job.startLine(),
        "job",
        job.name(),
        path,
        new SourceLocation(path, job.startLine(), job.endLine()),
        type,
        false);
  }

  private static ChangedSymbol workflowSymbol(ParsedWorkflow file, ChangeType type, String source) {
    int end =
        file.onEnd() > 0 ? file.onEnd() : WorkflowSupport.lineCount(source == null ? "" : source);
    int start = file.onStart() > 0 ? file.onStart() : 1;
    String name = fileName(file.path());
    return new ChangedSymbol(
        file.path() + ":workflow#" + name + "@" + start,
        "workflow",
        name,
        parent(file.path()),
        new SourceLocation(file.path(), start, end),
        type,
        false);
  }

  private static ChangedSymbol triggerSymbol(ParsedWorkflow file, ChangeType type) {
    return new ChangedSymbol(
        file.path() + ":trigger#on@" + file.onStart(),
        "trigger",
        "on",
        file.path(),
        new SourceLocation(file.path(), file.onStart(), file.onEnd()),
        type,
        false);
  }

  private static ChangedSymbol actionSymbol(ParsedWorkflow file, ChangeType type, String source) {
    int end = WorkflowSupport.lineCount(source == null ? "" : source);
    String directory = parent(file.path());
    String name = fileName(directory.isEmpty() ? file.path() : directory);
    return new ChangedSymbol(
        file.path() + ":action#" + name + "@1",
        "action",
        name,
        parent(directory),
        new SourceLocation(file.path(), 1, end),
        type,
        false);
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private static String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }
}
