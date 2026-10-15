package io.github.anikethts.changeradar.analyzer.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
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
import java.util.Optional;
import java.util.Set;

/**
 * Java analyzer: maps changed lines to types and methods, then finds name-resolvable callers and
 * related tests. Calls without a matching type scope are labeled HEURISTIC.
 */
public final class JavaLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "java";

  private static final JavaParser PARSER =
      new JavaParser(
          new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

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

  public AnalysisResult analyze(
      List<ChangedFile> changedFiles,
      Map<String, String> headJavaFiles,
      Map<String, String> baseJavaFiles) {
    List<Limitation> limitations = new ArrayList<>();
    List<ChangedSymbol> changedSymbols = new ArrayList<>();
    Map<String, ParsedFile> parsedHead = new LinkedHashMap<>();

    for (Map.Entry<String, String> entry : headJavaFiles.entrySet()) {
      parse(entry.getKey(), entry.getValue(), parsedHead, limitations);
    }

    for (ChangedFile changed : changedFiles) {
      if (!changed.isJavaSource()) {
        continue;
      }
      if (changed.changeType() == ChangeType.DELETE) {
        Map<String, ParsedFile> parsedBase = new LinkedHashMap<>();
        String source = baseJavaFiles.get(changed.path());
        if (source != null) {
          parse(changed.path(), source, parsedBase, limitations);
          ParsedFile file = parsedBase.get(changed.path());
          if (file != null) {
            for (Decl decl : file.decls) {
              changedSymbols.add(decl.toSymbol(ChangeType.DELETE));
            }
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
      for (Decl decl : file.decls) {
        if (wholeFile || overlaps(hunks, decl.startLine, decl.endLine)) {
          ChangeType type =
              changed.changeType() == ChangeType.ADD ? ChangeType.ADD : ChangeType.MODIFY;
          changedSymbols.add(decl.toSymbol(type));
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

    limitations.add(
        new Limitation(
            "NAME_BASED_DISPATCH",
            "Callers are matched by method/type name without a full symbol solver. Overloaded"
                + " methods, inherited members, and dynamic dispatch may be missed or over-reported."
                + " Those matches are labeled HEURISTIC unless the call site names the type."));

    return new AnalysisResult(changedSymbols, findings, tests, limitations);
  }

  private static void parse(
      String path, String source, Map<String, ParsedFile> into, List<Limitation> limitations) {
    try {
      ParseResult<CompilationUnit> parsed = PARSER.parse(source);
      if (parsed.getResult().isEmpty()) {
        limitations.add(
            new Limitation(
                "JAVA_PARSE_FAILED", "Could not parse " + path + ": " + parsed.getProblems()));
        return;
      }
      CompilationUnit cu = parsed.getResult().get();
      List<Decl> decls = new ArrayList<>();
      for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
        type.getRange()
            .ifPresent(
                range ->
                    decls.add(
                        new Decl(
                            "type",
                            type.getNameAsString(),
                            qualifiedType(type),
                            type.isPublic(),
                            isTestPath(path) || isTestName(type.getNameAsString()),
                            path,
                            range.begin.line,
                            range.end.line)));
      }
      for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
        addCallable(decls, path, method, "method", method.getNameAsString(), method.isPublic());
      }
      for (ConstructorDeclaration ctor : cu.findAll(ConstructorDeclaration.class)) {
        addCallable(decls, path, ctor, "constructor", ctor.getNameAsString(), ctor.isPublic());
      }
      into.put(path, new ParsedFile(path, cu, decls, isTestPath(path)));
    } catch (RuntimeException e) {
      limitations.add(
          new Limitation("JAVA_PARSE_FAILED", "Could not parse " + path + ": " + e.getMessage()));
    }
  }

  private static void addCallable(
      List<Decl> decls,
      String path,
      CallableDeclaration<?> callable,
      String kind,
      String name,
      boolean isPublic) {
    callable
        .getRange()
        .ifPresent(
            range -> {
              String owner = enclosingType(callable).orElse("");
              decls.add(
                  new Decl(
                      kind,
                      name,
                      owner,
                      isPublic,
                      isTestPath(path) || isTestName(owner),
                      path,
                      range.begin.line,
                      range.end.line));
            });
  }

  private static List<ImpactFinding> findCallers(
      Map<String, ParsedFile> parsedHead, List<ChangedSymbol> changedSymbols) {
    Set<String> changedMethods = new LinkedHashSet<>();
    Set<String> changedTypes = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("method".equals(symbol.kind()) || "constructor".equals(symbol.kind())) {
        changedMethods.add(symbol.owner() + "#" + symbol.name());
      }
      if ("type".equals(symbol.kind())) {
        changedTypes.add(symbol.name());
      } else {
        String simple = simpleName(symbol.owner());
        if (!simple.isEmpty()) {
          changedTypes.add(simple);
        }
      }
    }

    List<ImpactFinding> findings = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (ParsedFile file : parsedHead.values()) {
      for (MethodCallExpr call : file.cu.findAll(MethodCallExpr.class)) {
        String methodName = call.getNameAsString();
        Optional<String> scopeType =
            call.getScope()
                .filter(NameExpr.class::isInstance)
                .map(scope -> ((NameExpr) scope).getNameAsString());
        Optional<String> enclosing = enclosingType(call);
        if (enclosing.isPresent() && changedMethods.contains(enclosing.get() + "#" + methodName)) {
          boolean insideChanged =
              call.getRange()
                  .map(
                      range ->
                          changedSymbols.stream()
                              .anyMatch(
                                  symbol ->
                                      symbol.location().path().equals(file.path)
                                          && symbol.location().startLine() <= range.begin.line
                                          && range.end.line <= symbol.location().endLine()
                                          && methodName.equals(symbol.name())))
                  .orElse(false);
          if (insideChanged && scopeType.isEmpty()) {
            continue;
          }
        }
        final Confidence confidence;
        final String matchedOwner;
        if (scopeType.isPresent() && changedMethods.contains(scopeType.get() + "#" + methodName)) {
          confidence = Confidence.DIRECT;
          matchedOwner = scopeType.get();
        } else if (changedMethods.stream().anyMatch(id -> id.endsWith("#" + methodName))) {
          confidence = Confidence.HEURISTIC;
          matchedOwner =
              changedMethods.stream()
                  .filter(id -> id.endsWith("#" + methodName))
                  .map(id -> id.substring(0, id.indexOf('#')))
                  .findFirst()
                  .orElse("");
        } else {
          continue;
        }
        String symbolId = matchedOwner + "#" + methodName;
        Optional<String> caller = enclosing;
        call.getRange()
            .ifPresent(
                range -> {
                  String key = file.path + ":" + range.begin.line + ":" + symbolId;
                  if (seen.add(key)) {
                    findings.add(
                        new ImpactFinding(
                            "CALLER",
                            confidence,
                            caller.orElse(file.path)
                                + " calls "
                                + symbolId
                                + " ("
                                + confidence.name().toLowerCase()
                                + ")",
                            symbolId,
                            new SourceLocation(file.path, range.begin.line, range.end.line)));
                  }
                });
      }
      for (ObjectCreationExpr create : file.cu.findAll(ObjectCreationExpr.class)) {
        String typeName = create.getType().getNameAsString();
        if (!changedTypes.contains(typeName)) {
          continue;
        }
        create
            .getRange()
            .ifPresent(
                range -> {
                  String key = file.path + ":" + range.begin.line + ":new " + typeName;
                  if (seen.add(key)) {
                    Confidence confidence = Confidence.DIRECT;
                    findings.add(
                        new ImpactFinding(
                            "CALLER",
                            confidence,
                            enclosingType(create).orElse(file.path) + " constructs " + typeName,
                            typeName,
                            new SourceLocation(file.path, range.begin.line, range.end.line)));
                  }
                });
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
      if (!"type".equals(symbol.kind()) && !"method".equals(symbol.kind())) {
        continue;
      }
      findings.add(
          new ImpactFinding(
              "PUBLIC_API",
              Confidence.DIRECT,
              "Public "
                  + symbol.kind()
                  + " "
                  + ("type".equals(symbol.kind()) || symbol.owner().isEmpty()
                      ? symbol.name()
                      : symbol.owner() + "#" + symbol.name())
                  + " occupies changed lines",
              symbol.id(),
              symbol.location()));
    }
    return findings;
  }

  private static List<TestRecommendation> recommendTests(
      Map<String, ParsedFile> parsedHead,
      List<ChangedSymbol> changedSymbols,
      List<ImpactFinding> findings) {
    Map<String, TestRecommendation> tests = new LinkedHashMap<>();
    Set<String> changedTypeNames = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("type".equals(symbol.kind())) {
        changedTypeNames.add(symbol.name());
      } else {
        String simple = simpleName(symbol.owner());
        if (!simple.isEmpty()) {
          changedTypeNames.add(simple);
        }
      }
    }

    for (ParsedFile file : parsedHead.values()) {
      if (!file.testFile && file.decls.stream().noneMatch(decl -> decl.test)) {
        continue;
      }
      for (Decl decl : file.decls) {
        if (!"type".equals(decl.kind)) {
          continue;
        }
        for (String typeName : changedTypeNames) {
          if (decl.name.equals(typeName + "Test") || decl.name.equals(typeName + "Tests")) {
            tests.putIfAbsent(
                file.path + "#" + decl.name,
                new TestRecommendation(
                    decl.name,
                    file.path,
                    Confidence.HEURISTIC,
                    "Type name "
                        + decl.name
                        + " matches naming convention for changed type "
                        + typeName
                        + ". Suggestion only; the test was not executed."));
          }
        }
      }
    }

    for (ImpactFinding finding : findings) {
      ParsedFile file = parsedHead.get(finding.location().path());
      if (file == null || (!file.testFile && file.decls.stream().noneMatch(decl -> decl.test))) {
        continue;
      }
      String testName =
          file.decls.stream()
              .filter(decl -> "type".equals(decl.kind))
              .map(decl -> decl.name)
              .findFirst()
              .orElse(file.path);
      tests.putIfAbsent(
          file.path + "#" + testName,
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

  private static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  private static String qualifiedType(TypeDeclaration<?> type) {
    return typeName(type);
  }

  private static String typeName(TypeDeclaration<?> type) {
    List<String> parts = new ArrayList<>();
    Node current = type;
    while (current != null) {
      if (current instanceof TypeDeclaration<?> declaration) {
        parts.add(0, declaration.getNameAsString());
      }
      current = current.getParentNode().orElse(null);
    }
    return String.join(".", parts);
  }

  private static Optional<String> enclosingType(Node node) {
    Node current = node;
    while (current != null) {
      if (current instanceof TypeDeclaration<?> declaration) {
        return Optional.of(typeName(declaration));
      }
      current = current.getParentNode().orElse(null);
    }
    return Optional.empty();
  }

  private static String simpleName(String owner) {
    int dot = owner.lastIndexOf('.');
    return dot >= 0 ? owner.substring(dot + 1) : owner;
  }

  private static boolean isTestPath(String path) {
    return path.contains("/test/") || path.contains("\\test\\") || path.contains("/src/test");
  }

  private static boolean isTestName(String name) {
    return name.endsWith("Test") || name.endsWith("Tests");
  }

  private record ParsedFile(String path, CompilationUnit cu, List<Decl> decls, boolean testFile) {}

  private record Decl(
      String kind,
      String name,
      String owner,
      boolean isPublic,
      boolean test,
      String path,
      int startLine,
      int endLine) {

    ChangedSymbol toSymbol(ChangeType changeType) {
      String id = path + ":" + owner + "#" + name + "@" + startLine;
      return new ChangedSymbol(
          id,
          kind,
          name,
          owner,
          new SourceLocation(path, startLine, endLine),
          changeType,
          isPublic);
    }
  }
}
