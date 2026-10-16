package io.github.anikethts.changeradar.analyzer.php;

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
 * PHP analyzer: maps changed lines to classes and functions, then finds name-resolvable callers and
 * related tests. This is not php-parser; unmatched braces fail the file. {@code private}/{@code
 * protected} members are not public API.
 */
public final class PhpLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "php";

  private static final Pattern CLASS =
      Pattern.compile("^(?:(?:abstract|final)\\s+)?class\\s+([A-Za-z_][\\w]*)");
  private static final Pattern INTERFACE_OR_ENUM =
      Pattern.compile("^(?:interface|enum)\\s+([A-Za-z_][\\w]*)");
  private static final Pattern FUNCTION =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|final|abstract)\\s+)*function\\s+&?\\s*([A-Za-z_][\\w]*)");
  private static final Pattern CALL =
      Pattern.compile("(?:([A-Za-z_][\\w]*)\\s*(?:->|::))?([A-Za-z_][\\w]*)\\s*\\(");
  private static final Pattern NEW_TYPE =
      Pattern.compile("\\bnew\\s+\\\\?([A-Za-z_][\\w]*)\\s*\\(");
  private static final Set<String> NOT_CALLS =
      Set.of(
          "if",
          "elseif",
          "for",
          "foreach",
          "while",
          "switch",
          "catch",
          "function",
          "class",
          "return",
          "isset",
          "empty",
          "unset",
          "echo",
          "print",
          "array",
          "list",
          "match");

  @Override
  public String languageId() {
    return LANGUAGE_ID;
  }

  @Override
  public boolean supports(ChangedFile file) {
    return file.isPhpSource();
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
      if (!isPhpPath(entry.getKey())) {
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

    boolean anyPhp = !parsedHead.isEmpty() || changedFiles.stream().anyMatch(this::supports);
    if (anyPhp) {
      limitations.add(
          new Limitation(
              "PHP_NAME_BASED_DISPATCH",
              "PHP callers are matched by name without php-parser or autoload. Magic methods,"
                  + " call_user_func, and attributes may be missed or over-reported. Those matches"
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
    int depth = stripAndMeasure(source, codeLines);
    if (depth != 0) {
      limitations.add(
          new Limitation(
              "PHP_PARSE_FAILED",
              "Could not parse " + path + ": unmatched braces (depth " + depth + ")."));
      return;
    }
    List<Decl> decls = extractDecls(path, codeLines);
    into.put(path, new ParsedFile(path, codeLines, decls, isTestPath(path)));
  }

  private static int stripAndMeasure(String source, List<String> codeLines) {
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inLineComment = false;
    boolean inBlockComment = false;
    boolean inSingle = false;
    boolean inDouble = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
      if (c == '\n') {
        codeLines.add(current.toString());
        current.setLength(0);
        inLineComment = false;
        continue;
      }
      if (inLineComment) {
        continue;
      }
      if (inBlockComment) {
        if (c == '*' && next == '/') {
          inBlockComment = false;
          i++;
        }
        continue;
      }
      if (inSingle) {
        if (c == '\\') {
          i++;
        } else if (c == '\'') {
          inSingle = false;
        }
        current.append(' ');
        continue;
      }
      if (inDouble) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          inDouble = false;
        }
        current.append(' ');
        continue;
      }
      if (c == '/' && next == '/') {
        inLineComment = true;
        i++;
        continue;
      }
      if (c == '#') {
        inLineComment = true;
        continue;
      }
      if (c == '/' && next == '*') {
        inBlockComment = true;
        i++;
        continue;
      }
      if (c == '\'') {
        inSingle = true;
        current.append(' ');
        continue;
      }
      if (c == '"') {
        inDouble = true;
        current.append(' ');
        continue;
      }
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
      }
      current.append(c);
    }
    codeLines.add(current.toString());
    return depth;
  }

  private static List<Decl> extractDecls(String path, List<String> lines) {
    List<Decl> decls = new ArrayList<>();
    int brace = 0;
    String currentClass = null;
    int classLevel = -1;
    boolean testFile = isTestPath(path);
    for (int i = 0; i < lines.size(); i++) {
      int lineNo = i + 1;
      String trimmed = lines.get(i).trim();
      if (currentClass == null) {
        String typeName = typeName(trimmed);
        if (typeName != null) {
          decls.add(new Decl("type", typeName, typeName, true, testFile, path, lineNo, lineNo));
          currentClass = typeName;
          classLevel = brace + 1;
        } else {
          Matcher fn = FUNCTION.matcher(trimmed);
          if (fn.find()) {
            String name = fn.group(1);
            decls.add(
                new Decl(
                    "function",
                    name,
                    "",
                    isPublicFunction(trimmed, name),
                    testFile,
                    path,
                    lineNo,
                    lineNo));
          }
        }
      } else if (brace == classLevel) {
        Matcher fn = FUNCTION.matcher(trimmed);
        if (fn.find()) {
          String name = fn.group(1);
          String kind = "__construct".equals(name) ? "constructor" : "method";
          decls.add(
              new Decl(
                  kind,
                  name,
                  currentClass,
                  isPublicFunction(trimmed, name),
                  testFile,
                  path,
                  lineNo,
                  lineNo));
        }
      }
      brace += netBraces(lines.get(i));
      if (currentClass != null && brace < classLevel) {
        currentClass = null;
        classLevel = -1;
      }
    }
    return decls;
  }

  private static String typeName(String trimmed) {
    Matcher cls = CLASS.matcher(trimmed);
    if (cls.find()) {
      return cls.group(1);
    }
    Matcher iface = INTERFACE_OR_ENUM.matcher(trimmed);
    if (iface.find()) {
      return iface.group(1);
    }
    return null;
  }

  private static boolean isPublicFunction(String trimmed, String name) {
    if (trimmed.contains("private") || trimmed.contains("protected")) {
      return false;
    }
    return !name.startsWith("_") || name.startsWith("__");
  }

  private static int netBraces(String line) {
    int n = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '{') {
        n++;
      } else if (c == '}') {
        n--;
      }
    }
    return n;
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
        Matcher neu = NEW_TYPE.matcher(line);
        while (neu.find()) {
          String typeName = neu.group(1);
          if (!changedTypes.contains(typeName)) {
            continue;
          }
          String key = file.path + ":" + lineNo + ":new " + typeName;
          if (seen.add(key)) {
            findings.add(
                new ImpactFinding(
                    "CALLER",
                    Confidence.DIRECT,
                    file.path + " constructs " + typeName,
                    typeName,
                    new SourceLocation(file.path, lineNo, lineNo)));
          }
        }
        Matcher call = CALL.matcher(line);
        while (call.find()) {
          String receiver = call.group(1);
          String name = call.group(2);
          if (NOT_CALLS.contains(name) || !changedFns.contains(name)) {
            continue;
          }
          boolean definition =
              file.decls.stream()
                  .anyMatch(decl -> decl.startLine == lineNo && decl.name.equals(name));
          if (definition && receiver == null) {
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
        if (file.path.contains(changed)) {
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
      tests.putIfAbsent(
          file.path,
          new TestRecommendation(
              file.path,
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

  private static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPhpPath(String path) {
    return new ChangedFile(path, ChangeType.MODIFY, null).isPhpSource();
  }

  private static boolean isTestPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase();
    String file = lower.substring(lower.lastIndexOf('/') + 1);
    return lower.contains("/test/") || lower.contains("/tests/") || file.endsWith("test.php");
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
      int endLine) {

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
