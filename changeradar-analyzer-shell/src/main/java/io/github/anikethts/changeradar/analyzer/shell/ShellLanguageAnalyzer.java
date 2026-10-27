package io.github.anikethts.changeradar.analyzer.shell;

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
 * Shell analyzer: maps changed lines to functions in {@code .sh} files. Other scripts that call the
 * function, or {@code source} the file, are callers. The script is not executed.
 */
public final class ShellLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "shell";

  @Override
  public String languageId() {
    return LANGUAGE_ID;
  }

  @Override
  public boolean supports(ChangedFile file) {
    return file.isShellSource();
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
    Map<String, ParsedShell> parsedHead = new LinkedHashMap<>();

    for (Map.Entry<String, String> entry : headFiles.entrySet()) {
      if (!ShellSupport.isShellPath(entry.getKey())) {
        continue;
      }
      ParsedShell parsed = ShellParsing.parse(entry.getKey(), entry.getValue(), limitations);
      if (parsed != null) {
        parsedHead.put(parsed.path(), parsed);
      }
    }

    for (ChangedFile changed : changedFiles) {
      if (!supports(changed)) {
        continue;
      }
      String path = changed.path().replace('\\', '/');
      if (changed.changeType() == ChangeType.DELETE) {
        addAll(
            path,
            baseFiles.get(changed.previousPath() != null ? changed.previousPath() : path),
            limitations,
            changedSymbols,
            ChangeType.DELETE,
            true);
        continue;
      }
      ParsedShell file = parsedHead.get(path);
      if (file == null) {
        continue;
      }
      String basePath = changed.previousPath() != null ? changed.previousPath() : path;
      if (changed.changeType() == ChangeType.MODIFY
          && FormatTrivia.sameIgnoringTrivia(
              FormatTrivia.Dialect.SHELL, baseFiles.get(basePath), headFiles.get(changed.path()))) {
        formatOnly.add(changed.path());
        continue;
      }
      List<LineRange> hunks = changed.headLineRanges();
      boolean wholeFile = hunks.isEmpty() || changed.changeType() == ChangeType.ADD;
      ChangeType type = changed.changeType() == ChangeType.ADD ? ChangeType.ADD : ChangeType.MODIFY;
      Set<String> headNames = new LinkedHashSet<>();
      changedSymbols.add(scriptSymbol(file, type, headFiles.get(changed.path())));
      for (Func func : file.functions()) {
        headNames.add(func.name());
        if (wholeFile || ShellSupport.overlaps(hunks, func.startLine(), func.endLine())) {
          changedSymbols.add(functionSymbol(file.path(), func, type));
        }
      }
      if (changed.changeType() == ChangeType.MODIFY || changed.changeType() == ChangeType.RENAME) {
        ParsedShell base =
            baseFiles.get(basePath) == null
                ? null
                : ShellParsing.parse(basePath, baseFiles.get(basePath), limitations);
        if (base != null) {
          for (Func func : base.functions()) {
            if (!headNames.contains(func.name())) {
              changedSymbols.add(functionSymbol(path, func, ChangeType.DELETE));
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
        new ArrayList<>(ShellCallers.findCallers(parsedHead, changedSymbols));
    findings.sort(
        Comparator.comparing((ImpactFinding finding) -> finding.location().path())
            .thenComparing(finding -> finding.location().startLine())
            .thenComparing(ImpactFinding::summary));

    boolean anyShell = !parsedHead.isEmpty() || changedFiles.stream().anyMatch(this::supports);
    if (anyShell) {
      limitations.add(
          new Limitation(
              "SHELL_REFERENCES",
              "Shell references match function names in other .sh files after comments and"
                  + " quotes are removed, and source or dot paths relative to the sourcing"
                  + " script. A name declared more than once is HEURISTIC. The script is not"
                  + " executed. eval and functions built at runtime are missed."));
    }
    return new AnalysisResult(changedSymbols, findings, List.of(), limitations);
  }

  private static void addAll(
      String path,
      String source,
      List<Limitation> limitations,
      List<ChangedSymbol> changedSymbols,
      ChangeType type,
      boolean deleted) {
    if (source == null) {
      return;
    }
    ParsedShell file = ShellParsing.parse(path, source, limitations);
    if (file == null) {
      return;
    }
    changedSymbols.add(scriptSymbol(file, type, source));
    if (deleted || true) {
      for (Func func : file.functions()) {
        changedSymbols.add(functionSymbol(file.path(), func, type));
      }
    }
  }

  private static ChangedSymbol scriptSymbol(ParsedShell file, ChangeType type, String source) {
    int end = ShellSupport.lineCount(source == null ? "" : source);
    String name = fileName(file.path());
    return new ChangedSymbol(
        file.path() + ":script#" + name + "@1",
        "script",
        name,
        parent(file.path()),
        new SourceLocation(file.path(), 1, end),
        type,
        false);
  }

  private static ChangedSymbol functionSymbol(String path, Func func, ChangeType type) {
    return new ChangedSymbol(
        path + ":function#" + func.name() + "@" + func.startLine(),
        "function",
        func.name(),
        path,
        new SourceLocation(path, func.startLine(), func.endLine()),
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
