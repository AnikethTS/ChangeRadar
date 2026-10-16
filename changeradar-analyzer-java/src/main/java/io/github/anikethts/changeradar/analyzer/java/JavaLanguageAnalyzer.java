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
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java analyzer: maps changed lines to types and methods, then finds callers. In-repo types plus
 * the JRE are resolved with JavaParser's symbol solver (DIRECT). Unresolved calls fall back to name
 * matching (HEURISTIC). Compiled Maven/Gradle dependencies are not on the type path.
 */
public final class JavaLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "java";

  private static final JavaParser PARSER =
      new JavaParser(
          new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

  private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

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
    List<Limitation> limitations = new ArrayList<>();
    List<ChangedSymbol> changedSymbols = new ArrayList<>();
    Map<String, ParsedFile> parsedHead = new LinkedHashMap<>();
    Path solverRoot = null;
    JavaParser headParser = PARSER;
    if (!headJavaFiles.isEmpty()) {
      try {
        solverRoot = materializeSources(headJavaFiles);
        headParser = parserWithSolver(solverRoot, headJavaFiles);
        limitations.add(
            new Limitation(
                "JAVA_SYMBOL_SOLVER",
                "Java callers are type-resolved against HEAD sources and the JRE. Types that exist"
                    + " only in compiled dependencies are not on the path; those call sites fall"
                    + " back to name matching (HEURISTIC)."));
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
        parse(headParser, entry.getKey(), entry.getValue(), parsedHead, limitations);
      }

      for (ChangedFile changed : changedFiles) {
        if (!changed.isJavaSource()) {
          continue;
        }
        if (changed.changeType() == ChangeType.DELETE) {
          ParsedFile baseFile = parseBase(changed.path(), baseJavaFiles, limitations);
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
        if (changed.changeType() == ChangeType.MODIFY
            || changed.changeType() == ChangeType.RENAME) {
          ParsedFile baseFile =
              parseBase(
                  changed.previousPath() != null ? changed.previousPath() : changed.path(),
                  baseJavaFiles,
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
      deleteRecursively(solverRoot);
    }
  }

  private static void parse(
      JavaParser parser,
      String path,
      String source,
      Map<String, ParsedFile> into,
      List<Limitation> limitations) {
    try {
      ParseResult<CompilationUnit> parsed = parser.parse(source);
      if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
        limitations.add(
            new Limitation(
                "JAVA_PARSE_FAILED", "Could not parse " + path + ": " + parsed.getProblems()));
        return;
      }
      CompilationUnit cu = parsed.getResult().get();
      String pkg =
          cu.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
      List<Decl> decls = new ArrayList<>();
      for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
        type.getRange()
            .ifPresent(
                range ->
                    decls.add(
                        new Decl(
                            "type",
                            type.getNameAsString(),
                            qualify(pkg, typeName(type)),
                            "",
                            type.isPublic(),
                            isTestPath(path) || isTestName(type.getNameAsString()),
                            path,
                            range.begin.line,
                            range.end.line)));
      }
      for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
        addCallable(
            decls, pkg, path, method, "method", method.getNameAsString(), method.isPublic());
      }
      for (ConstructorDeclaration ctor : cu.findAll(ConstructorDeclaration.class)) {
        addCallable(decls, pkg, path, ctor, "constructor", ctor.getNameAsString(), ctor.isPublic());
      }
      into.put(path, new ParsedFile(path, cu, decls, isTestPath(path)));
    } catch (RuntimeException e) {
      limitations.add(
          new Limitation("JAVA_PARSE_FAILED", "Could not parse " + path + ": " + e.getMessage()));
    }
  }

  private static void addCallable(
      List<Decl> decls,
      String pkg,
      String path,
      CallableDeclaration<?> callable,
      String kind,
      String name,
      boolean isPublic) {
    callable
        .getRange()
        .ifPresent(
            range -> {
              String owner = qualify(pkg, enclosingType(callable).orElse(""));
              decls.add(
                  new Decl(
                      kind,
                      name,
                      owner,
                      parameterSignature(callable),
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
        String simpleOwner = simpleName(symbol.owner());
        if (!simpleOwner.isEmpty()) {
          changedMethods.add(simpleOwner + "#" + symbol.name());
        }
      }
      if ("type".equals(symbol.kind())) {
        changedTypes.add(symbol.name());
        changedTypes.add(simpleName(symbol.name()));
      } else {
        String simple = simpleName(symbol.owner());
        if (!simple.isEmpty()) {
          changedTypes.add(simple);
        }
        if (!symbol.owner().isEmpty()) {
          changedTypes.add(symbol.owner());
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
        Optional<ResolvedCall> resolved = resolveMethodCall(call);
        final Confidence confidence;
        final String matchedOwner;
        if (resolved.isPresent()) {
          ResolvedCall match = resolved.get();
          if (!changedMethods.contains(match.owner() + "#" + match.name())
              && !changedMethods.contains(simpleName(match.owner()) + "#" + match.name())) {
            continue;
          }
          confidence = Confidence.DIRECT;
          matchedOwner = match.owner();
        } else if (scopeType.isPresent()
            && changedMethods.contains(scopeType.get() + "#" + methodName)) {
          confidence = Confidence.DIRECT;
          matchedOwner = scopeType.get();
        } else if (scopeType.isEmpty()
            && enclosing.isPresent()
            && changedMethods.contains(enclosing.get() + "#" + methodName)) {
          // Unresolved this/static call: only the enclosing type, not every parseBase() in the
          // repo.
          confidence = Confidence.HEURISTIC;
          matchedOwner = enclosing.get();
        } else if (scopeType.isPresent()
            && changedMethods.stream().anyMatch(id -> id.endsWith("#" + methodName))) {
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
        Optional<String> resolvedType = resolveCreatedType(create);
        String typeName = resolvedType.orElseGet(() -> create.getType().getNameAsString());
        boolean matched =
            resolvedType.isPresent()
                ? changedTypes.contains(typeName) || changedTypes.contains(simpleName(typeName))
                : changedTypes.contains(typeName);
        if (!matched) {
          continue;
        }
        Confidence confidence = resolvedType.isPresent() ? Confidence.DIRECT : Confidence.HEURISTIC;
        create
            .getRange()
            .ifPresent(
                range -> {
                  String key = file.path + ":" + range.begin.line + ":new " + typeName;
                  if (seen.add(key)) {
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
              publicApiSummary(symbol),
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

  private static ParsedFile parseBase(
      String path, Map<String, String> baseJavaFiles, List<Limitation> limitations) {
    String source = baseJavaFiles.get(path);
    if (source == null) {
      return null;
    }
    Map<String, ParsedFile> parsed = new LinkedHashMap<>();
    parse(PARSER, path, source, parsed, limitations);
    return parsed.get(path);
  }

  private static Optional<ResolvedCall> resolveMethodCall(MethodCallExpr call) {
    try {
      ResolvedMethodDeclaration resolved = call.resolve();
      return Optional.of(
          new ResolvedCall(resolved.declaringType().getQualifiedName(), resolved.getName()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static Optional<String> resolveCreatedType(ObjectCreationExpr create) {
    try {
      ResolvedConstructorDeclaration resolved = create.resolve();
      return Optional.of(resolved.declaringType().getQualifiedName());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static JavaParser parserWithSolver(Path solverRoot, Map<String, String> headJavaFiles) {
    ParserConfiguration language =
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    CombinedTypeSolver types = new CombinedTypeSolver();
    types.add(new ReflectionTypeSolver(true));
    for (String root : sourceRoots(headJavaFiles)) {
      Path dir = root.isEmpty() ? solverRoot : solverRoot.resolve(root);
      if (Files.isDirectory(dir)) {
        types.add(new JavaParserTypeSolver(dir, language));
      }
    }
    ParserConfiguration configured =
        new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            .setSymbolResolver(new JavaSymbolSolver(types));
    return new JavaParser(configured);
  }

  private static Path materializeSources(Map<String, String> headJavaFiles) throws IOException {
    Path tmp = Files.createTempDirectory("changeradar-java-");
    for (Map.Entry<String, String> entry : headJavaFiles.entrySet()) {
      Path dest = tmp.resolve(entry.getKey()).normalize();
      if (!dest.startsWith(tmp)) {
        throw new IOException("Refusing to write outside the solver workspace: " + entry.getKey());
      }
      Path parent = dest.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(dest, entry.getValue());
    }
    tmp.toFile().deleteOnExit();
    return tmp;
  }

  private static Set<String> sourceRoots(Map<String, String> headJavaFiles) {
    Set<String> roots = new LinkedHashSet<>();
    for (Map.Entry<String, String> entry : headJavaFiles.entrySet()) {
      roots.add(inferSourceRoot(entry.getKey(), entry.getValue()));
    }
    return roots;
  }

  static String inferSourceRoot(String path, String source) {
    String unix = path.replace('\\', '/');
    Matcher matcher = PACKAGE_DECL.matcher(source);
    String pkg = matcher.find() ? matcher.group(1) : "";
    String fileName = unix.substring(unix.lastIndexOf('/') + 1);
    if (pkg.isEmpty()) {
      int slash = unix.lastIndexOf('/');
      return slash < 0 ? "" : unix.substring(0, slash);
    }
    String relative = pkg.replace('.', '/') + "/" + fileName;
    if (unix.endsWith("/" + relative) || unix.equals(relative)) {
      if (unix.length() == relative.length()) {
        return "";
      }
      return unix.substring(0, unix.length() - relative.length() - 1);
    }
    int slash = unix.lastIndexOf('/');
    return slash < 0 ? "" : unix.substring(0, slash);
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  path.toFile().deleteOnExit();
                }
              });
    } catch (IOException ignored) {
      root.toFile().deleteOnExit();
    }
  }

  private record ResolvedCall(String owner, String name) {}

  private static String parameterSignature(CallableDeclaration<?> callable) {
    List<String> types = new ArrayList<>();
    callable.getParameters().forEach(parameter -> types.add(parameter.getType().asString()));
    return String.join(",", types);
  }

  private static String publicApiSummary(ChangedSymbol symbol) {
    String label =
        "type".equals(symbol.kind()) || symbol.owner().isEmpty()
            ? symbol.name()
            : symbol.owner() + "#" + symbol.name();
    if (symbol.changeType() == ChangeType.DELETE) {
      return "Public " + symbol.kind() + " " + label + " was removed";
    }
    if (symbol.changeType() == ChangeType.ADD) {
      return "Public " + symbol.kind() + " " + label + " was added";
    }
    return "Public " + symbol.kind() + " " + label + " occupies changed lines";
  }

  private static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  private static String qualify(String pkg, String nested) {
    if (nested.isEmpty()) {
      return pkg;
    }
    if (pkg.isEmpty()) {
      return nested;
    }
    return pkg + "." + nested;
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
      String signature,
      boolean isPublic,
      boolean test,
      String path,
      int startLine,
      int endLine) {

    String identity() {
      if ("type".equals(kind)) {
        return "type:" + owner;
      }
      return kind + ":" + owner + "#" + name + "(" + signature + ")";
    }

    ChangedSymbol toSymbol(ChangeType changeType) {
      String id = path + ":" + identity() + "@" + startLine;
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
