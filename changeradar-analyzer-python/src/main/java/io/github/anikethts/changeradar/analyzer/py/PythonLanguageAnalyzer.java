package io.github.anikethts.changeradar.analyzer.py;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.LanguageAnalyzer;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.LineRange;
import io.github.anikethts.changeradar.core.SourceLocation;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python analyzer: maps changed lines to classes and functions, then finds name-resolvable callers
 * and related tests. This is not CPython; unclosed triples fail the file, and name matches without
 * a receiver are HEURISTIC. Leading-underscore names are treated as private.
 */
public final class PythonLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "python";

  private static final Pattern CLASS = Pattern.compile("^class\\s+([A-Za-z_][\\w]*)");
  private static final Pattern DEF = Pattern.compile("^(?:async\\s+)?def\\s+([A-Za-z_][\\w]*)");
  private static final Pattern CALL =
      Pattern.compile("(?:([A-Za-z_][\\w]*)\\s*\\.)?([A-Za-z_][\\w]*)\\s*\\(");
  private static final Set<String> NOT_CALLS =
      Set.of(
          "if", "elif", "for", "while", "def", "class", "return", "with", "except", "assert",
          "lambda", "not", "and", "or", "in", "is");

  @Override
  public String languageId() {
    return LANGUAGE_ID;
  }

  @Override
  public boolean supports(ChangedFile file) {
    return file.isPythonSource();
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
    Map<String, ParsedFile> parsedHead = new LinkedHashMap<>();

    for (Map.Entry<String, String> entry : headFiles.entrySet()) {
      if (!isPythonPath(entry.getKey())) {
        continue;
      }
      parse(entry.getKey(), entry.getValue(), parsedHead, limitations);
    }

    for (ChangedFile changed : changedFiles) {
      if (!supports(changed)) {
        continue;
      }
      if (changed.changeType() == ChangeType.DELETE) {
        ParsedFile baseFile = parseBase(changed.path(), baseFiles, limitations);
        if (baseFile != null) {
          for (Decl decl : baseFile.decls) {
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
      for (Decl decl : file.decls) {
        headKeys.add(decl.identity());
        if (wholeFile || overlaps(hunks, decl.startLine, decl.endLine)) {
          ChangeType type =
              changed.changeType() == ChangeType.ADD ? ChangeType.ADD : ChangeType.MODIFY;
          changedSymbols.add(decl.toSymbol(type));
        }
      }
      if (changed.changeType() == ChangeType.MODIFY || changed.changeType() == ChangeType.RENAME) {
        ParsedFile baseFile =
            parseBase(
                changed.previousPath() != null ? changed.previousPath() : changed.path(),
                baseFiles,
                limitations);
        if (baseFile != null) {
          for (Decl decl : baseFile.decls) {
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

    List<ImpactFinding> findings = findCallers(parsedHead, changedSymbols);
    findings.addAll(publicApiFindings(changedSymbols));
    findings.sort(
        Comparator.comparing((ImpactFinding f) -> f.location().path())
            .thenComparing(f -> f.location().startLine())
            .thenComparing(ImpactFinding::summary));

    List<TestRecommendation> tests = recommendTests(parsedHead, changedSymbols, findings);
    tests.sort(
        Comparator.comparing(TestRecommendation::path).thenComparing(TestRecommendation::name));

    boolean anyPython = !parsedHead.isEmpty() || changedFiles.stream().anyMatch(this::supports);
    if (anyPython) {
      limitations.add(
          new Limitation(
              "PY_NAME_BASED_DISPATCH",
              "Python callers are matched by name without CPython or import resolution. Decorators,"
                  + " getattr, and dynamic imports may be missed or over-reported. Those matches"
                  + " are labeled HEURISTIC unless the receiver names the type."));
    }

    return new AnalysisResult(changedSymbols, findings, tests, limitations);
  }

  private static ParsedFile parseBase(
      String path, Map<String, String> baseFiles, List<Limitation> limitations) {
    String source = baseFiles.get(path);
    if (source == null) {
      return null;
    }
    Map<String, ParsedFile> parsed = new LinkedHashMap<>();
    parse(path, source, parsed, limitations);
    return parsed.get(path);
  }

  private static void parse(
      String path, String source, Map<String, ParsedFile> into, List<Limitation> limitations) {
    List<String> codeLines = new ArrayList<>();
    if (!mask(source, codeLines)) {
      limitations.add(
          new Limitation(
              "PY_PARSE_FAILED", "Could not parse " + path + ": unclosed string literal."));
      return;
    }
    List<Decl> decls = extractDecls(path, codeLines);
    into.put(path, new ParsedFile(path, codeLines, decls, isTestPath(path)));
  }

  private static boolean mask(String source, List<String> codeLines) {
    StringBuilder current = new StringBuilder();
    boolean inHash = false;
    boolean inSq = false;
    boolean inDq = false;
    int triple = 0;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\n') {
        codeLines.add(current.toString());
        current.setLength(0);
        inHash = false;
        continue;
      }
      if (inHash) {
        continue;
      }
      if (triple == 3) {
        if (i + 2 < source.length()
            && c == '\''
            && source.charAt(i + 1) == '\''
            && source.charAt(i + 2) == '\'') {
          triple = 0;
          i += 2;
        }
        current.append(' ');
        continue;
      }
      if (triple == 6) {
        if (i + 2 < source.length()
            && c == '"'
            && source.charAt(i + 1) == '"'
            && source.charAt(i + 2) == '"') {
          triple = 0;
          i += 2;
        }
        current.append(' ');
        continue;
      }
      if (inSq) {
        if (c == '\\') {
          i++;
        } else if (c == '\'') {
          inSq = false;
        }
        current.append(' ');
        continue;
      }
      if (inDq) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          inDq = false;
        }
        current.append(' ');
        continue;
      }
      if (c == '#') {
        inHash = true;
        continue;
      }
      if (i + 2 < source.length()
          && c == '\''
          && source.charAt(i + 1) == '\''
          && source.charAt(i + 2) == '\'') {
        triple = 3;
        i += 2;
        current.append(' ');
        continue;
      }
      if (i + 2 < source.length()
          && c == '"'
          && source.charAt(i + 1) == '"'
          && source.charAt(i + 2) == '"') {
        triple = 6;
        i += 2;
        current.append(' ');
        continue;
      }
      if (c == '\'') {
        inSq = true;
        current.append(' ');
        continue;
      }
      if (c == '"') {
        inDq = true;
        current.append(' ');
        continue;
      }
      current.append(c);
    }
    codeLines.add(current.toString());
    return !inSq && !inDq && triple == 0;
  }

  private static List<Decl> extractDecls(String path, List<String> lines) {
    List<Decl> decls = new ArrayList<>();
    String currentClass = null;
    int classIndent = -1;
    boolean testFile = isTestPath(path);
    for (int i = 0; i < lines.size(); i++) {
      String raw = lines.get(i);
      if (raw.trim().isEmpty()) {
        continue;
      }
      int indent = leadingIndent(raw);
      String trimmed = raw.trim();
      int lineNo = i + 1;
      if (currentClass != null && indent <= classIndent) {
        currentClass = null;
        classIndent = -1;
      }
      if (currentClass == null) {
        Matcher cls = CLASS.matcher(trimmed);
        if (cls.find()) {
          String name = cls.group(1);
          decls.add(
              new Decl(
                  "type", name, name, isPublicName(name), testFile, path, lineNo, lineNo, indent));
          currentClass = name;
          classIndent = indent;
          continue;
        }
        Matcher def = DEF.matcher(trimmed);
        if (def.find()) {
          String name = def.group(1);
          decls.add(
              new Decl(
                  "function",
                  name,
                  "",
                  isPublicName(name),
                  testFile,
                  path,
                  lineNo,
                  lineNo,
                  indent));
        }
      } else {
        Matcher def = DEF.matcher(trimmed);
        if (def.find()) {
          String name = def.group(1);
          String kind = "__init__".equals(name) ? "constructor" : "method";
          decls.add(
              new Decl(
                  kind,
                  name,
                  currentClass,
                  isPublicName(name),
                  testFile,
                  path,
                  lineNo,
                  lineNo,
                  indent));
        }
      }
    }
    return closeBodies(decls, lines.size());
  }

  private static List<Decl> closeBodies(List<Decl> decls, int lineCount) {
    List<Decl> closed = new ArrayList<>();
    for (int i = 0; i < decls.size(); i++) {
      Decl decl = decls.get(i);
      int end = lineCount;
      for (int j = i + 1; j < decls.size(); j++) {
        if (decls.get(j).indent <= decl.indent) {
          end = decls.get(j).startLine - 1;
          break;
        }
      }
      closed.add(
          new Decl(
              decl.kind,
              decl.name,
              decl.owner,
              decl.publiclyVisible,
              decl.test,
              decl.path,
              decl.startLine,
              Math.max(decl.startLine, end),
              decl.indent));
    }
    return closed;
  }

  private static int leadingIndent(String line) {
    int n = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == ' ') {
        n++;
      } else if (c == '\t') {
        n += 8;
      } else {
        break;
      }
    }
    return n;
  }

  private static boolean isPublicName(String name) {
    if (name.startsWith("__") && name.endsWith("__")) {
      return true;
    }
    return !name.startsWith("_");
  }

  private static List<ImpactFinding> findCallers(
      Map<String, ParsedFile> parsedHead, List<ChangedSymbol> changedSymbols) {
    Set<String> changedFns = new LinkedHashSet<>();
    Set<String> changedTypes = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("function".equals(symbol.kind())
          || "method".equals(symbol.kind())
          || "constructor".equals(symbol.kind())) {
        changedFns.add(symbol.name());
      }
      if ("type".equals(symbol.kind())) {
        changedTypes.add(symbol.name());
      } else if (!symbol.owner().isEmpty()) {
        changedTypes.add(symbol.owner());
      }
    }
    List<ImpactFinding> findings = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (ParsedFile file : parsedHead.values()) {
      for (int i = 0; i < file.lines.size(); i++) {
        String line = file.lines.get(i);
        int lineNo = i + 1;
        Matcher call = CALL.matcher(line);
        while (call.find()) {
          String receiver = call.group(1);
          String name = call.group(2);
          if (NOT_CALLS.contains(name)) {
            continue;
          }
          boolean definition =
              file.decls.stream()
                  .anyMatch(decl -> decl.startLine == lineNo && decl.name.equals(name));
          if (definition && receiver == null) {
            continue;
          }
          if (changedTypes.contains(name) && receiver == null) {
            String key = file.path + ":" + lineNo + ":new " + name;
            if (seen.add(key)) {
              findings.add(
                  new ImpactFinding(
                      "CALLER",
                      Confidence.DIRECT,
                      file.path + " constructs " + name,
                      name,
                      new SourceLocation(file.path, lineNo, lineNo)));
            }
            continue;
          }
          if (!changedFns.contains(name)) {
            continue;
          }
          Confidence confidence =
              receiver != null && changedTypes.contains(receiver)
                  ? Confidence.DIRECT
                  : Confidence.HEURISTIC;
          String symbolId = receiver != null ? receiver + "#" + name : name;
          String key = file.path + ":" + lineNo + ":" + symbolId;
          if (seen.add(key)) {
            findings.add(
                new ImpactFinding(
                    "CALLER",
                    confidence,
                    file.path + " calls " + symbolId + " (" + confidence.name().toLowerCase() + ")",
                    symbolId,
                    new SourceLocation(file.path, lineNo, lineNo)));
          }
        }
      }
    }
    return findings;
  }

  private static List<ImpactFinding> publicApiFindings(List<ChangedSymbol> changedSymbols) {
    List<ImpactFinding> findings = new ArrayList<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if (!symbol.publiclyVisible()) {
        continue;
      }
      String label =
          symbol.owner().isEmpty() || "type".equals(symbol.kind())
              ? symbol.name()
              : symbol.owner() + "#" + symbol.name();
      String summary =
          symbol.changeType() == ChangeType.DELETE
              ? "Public " + symbol.kind() + " " + label + " was removed"
              : "Public " + symbol.kind() + " " + label + " occupies changed lines";
      findings.add(
          new ImpactFinding(
              "PUBLIC_API", Confidence.DIRECT, summary, symbol.id(), symbol.location()));
    }
    return findings;
  }

  private static List<TestRecommendation> recommendTests(
      Map<String, ParsedFile> parsedHead,
      List<ChangedSymbol> changedSymbols,
      List<ImpactFinding> findings) {
    Map<String, TestRecommendation> tests = new LinkedHashMap<>();
    Set<String> names = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      names.add(symbol.name());
      if (!symbol.owner().isEmpty()) {
        names.add(symbol.owner());
      }
    }
    for (ParsedFile file : parsedHead.values()) {
      if (!file.testFile) {
        continue;
      }
      for (String changed : names) {
        if (file.path.contains(changed) || file.path.contains(toSnake(changed))) {
          tests.putIfAbsent(
              file.path,
              new TestRecommendation(
                  file.path,
                  file.path,
                  Confidence.HEURISTIC,
                  "Test path relates to changed symbol "
                      + changed
                      + ". Suggestion only; the test was not executed."));
        }
      }
    }
    for (ImpactFinding finding : findings) {
      ParsedFile file = parsedHead.get(finding.location().path());
      if (file == null || !file.testFile) {
        continue;
      }
      String testName =
          file.decls.stream()
              .filter(decl -> "function".equals(decl.kind) && decl.name.startsWith("test_"))
              .map(decl -> decl.name)
              .findFirst()
              .orElse(file.path);
      tests.putIfAbsent(
          file.path,
          new TestRecommendation(
              testName,
              file.path,
              finding.confidence() == Confidence.DIRECT ? Confidence.DIRECT : Confidence.HEURISTIC,
              "This test "
                  + (finding.confidence() == Confidence.DIRECT ? "directly" : "may")
                  + " call changed code at "
                  + finding.location().path()
                  + ":"
                  + finding.location().startLine()
                  + ". Suggestion only; the test was not executed."));
    }
    return new ArrayList<>(tests.values());
  }

  private static String toSnake(String name) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c) && i > 0) {
        out.append('_');
      }
      out.append(Character.toLowerCase(c));
    }
    return out.toString();
  }

  private static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPythonPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isPythonSource();
  }

  private static boolean isTestPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase();
    String file = lower.substring(lower.lastIndexOf('/') + 1);
    return lower.contains("/test/")
        || lower.contains("/tests/")
        || file.startsWith("test_")
        || file.endsWith("_test.py")
        || file.endsWith("_test.pyi");
  }

  private record ParsedFile(String path, List<String> lines, List<Decl> decls, boolean testFile) {}

  private record Decl(
      String kind,
      String name,
      String owner,
      boolean publiclyVisible,
      boolean test,
      String path,
      int startLine,
      int endLine,
      int indent) {

    String identity() {
      return kind + ":" + owner + "#" + name;
    }

    ChangedSymbol toSymbol(ChangeType changeType) {
      return new ChangedSymbol(
          path + ":" + identity() + "@" + startLine,
          kind,
          name,
          owner,
          new SourceLocation(path, startLine, endLine),
          changeType,
          publiclyVisible);
    }
  }
}
