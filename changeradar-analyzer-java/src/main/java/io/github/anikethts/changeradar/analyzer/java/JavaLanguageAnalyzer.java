package io.github.anikethts.changeradar.analyzer.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
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
 * the JRE are resolved with JavaParser's symbol solver (DIRECT). Resolved callers of those callers
 * are INDIRECT, capped by depth and count. Unresolved calls fall back to name matching (HEURISTIC)
 * and are not chained. Overrides, field reads, annotation uses, and inject-style fields are extra
 * structural findings. Compiled Maven/Gradle dependencies are not on the type path.
 */
public final class JavaLanguageAnalyzer implements LanguageAnalyzer {

  public static final String LANGUAGE_ID = "java";

  private static final JavaParser PARSER =
      new JavaParser(
          new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

  private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

  private static final Set<String> INJECT_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");

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
    JavaParser headParser = PARSER;
    if (!headJavaFiles.isEmpty()) {
      try {
        solverRoot = materializeSources(headJavaFiles);
        headParser = parserWithSolver(solverRoot, headJavaFiles, options.classpath());
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
                    + "; those call sites fall back to name matching (HEURISTIC)."));
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

      CallerScan callers = findCallers(parsedHead, changedSymbols);
      if (callers.chainCapped()) {
        limitations.add(
            new Limitation(
                "JAVA_CALL_CHAIN_CAP",
                "Indirect Java callers were capped at "
                    + CallChains.MAX_INDIRECT
                    + "; later hops in the graph are omitted."));
      }
      List<ImpactFinding> findings = new ArrayList<>(callers.findings());
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
      for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
        addFields(decls, pkg, path, field);
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

  private static void addFields(List<Decl> decls, String pkg, String path, FieldDeclaration field) {
    String owner = qualify(pkg, enclosingType(field).orElse(""));
    for (VariableDeclarator variable : field.getVariables()) {
      variable
          .getRange()
          .ifPresent(
              range ->
                  decls.add(
                      new Decl(
                          "field",
                          variable.getNameAsString(),
                          owner,
                          variable.getType().asString(),
                          field.isPublic(),
                          isTestPath(path) || isTestName(owner),
                          path,
                          range.begin.line,
                          range.end.line)));
    }
  }

  private static CallerScan findCallers(
      Map<String, ParsedFile> parsedHead, List<ChangedSymbol> changedSymbols) {
    Set<String> changedMethods = new LinkedHashSet<>();
    Set<String> changedTypes = new LinkedHashSet<>();
    Set<String> changedTypeDecls = new LinkedHashSet<>();
    Set<String> changedFields = new LinkedHashSet<>();
    Set<String> seeds = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("method".equals(symbol.kind()) || "constructor".equals(symbol.kind())) {
        String id = symbol.owner() + "#" + symbol.name();
        changedMethods.add(id);
        seeds.add(id);
        String simpleOwner = simpleName(symbol.owner());
        if (!simpleOwner.isEmpty()) {
          changedMethods.add(simpleOwner + "#" + symbol.name());
        }
      }
      if ("field".equals(symbol.kind())) {
        changedFields.add(symbol.owner() + "#" + symbol.name());
        String simpleOwner = simpleName(symbol.owner());
        if (!simpleOwner.isEmpty()) {
          changedFields.add(simpleOwner + "#" + symbol.name());
        }
      }
      if ("type".equals(symbol.kind())) {
        changedTypes.add(symbol.name());
        changedTypes.add(simpleName(symbol.name()));
        if (!symbol.name().isEmpty()) {
          seeds.add(symbol.name());
        }
        boolean nestedMemberAlsoChanged =
            changedSymbols.stream()
                .anyMatch(
                    other ->
                        !"type".equals(other.kind())
                            && other.location().path().equals(symbol.location().path())
                            && symbol.location().startLine() <= other.location().startLine()
                            && other.location().endLine() <= symbol.location().endLine());
        if (!nestedMemberAlsoChanged
            || symbol.changeType() == ChangeType.ADD
            || symbol.changeType() == ChangeType.DELETE) {
          changedTypeDecls.add(symbol.name());
          changedTypeDecls.add(simpleName(symbol.name()));
        }
      } else {
        String simple = simpleName(symbol.owner());
        if (!simple.isEmpty()) {
          changedTypes.add(simple);
        }
        if (!symbol.owner().isEmpty()) {
          changedTypes.add(symbol.owner());
          seeds.add(symbol.owner());
        }
      }
    }

    List<CallChains.Edge> edges = new ArrayList<>();
    List<ImpactFinding> findings = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (ParsedFile file : parsedHead.values()) {
      String pkg = packageName(file.cu);
      for (MethodCallExpr call : file.cu.findAll(MethodCallExpr.class)) {
        Optional<ResolvedCall> resolved = resolveMethodCall(call);
        Optional<SourceLocation> location = locationOf(file.path, call);
        Optional<String> callerId = enclosingCallableId(call, pkg);
        if (resolved.isPresent() && location.isPresent() && callerId.isPresent()) {
          ResolvedCall match = resolved.get();
          String calleeId = match.owner() + "#" + match.name();
          edges.add(new CallChains.Edge(calleeId, callerId.get(), location.get()));
          continue;
        }
        heuristicMethodCaller(file, call, changedMethods, changedSymbols, seen, findings);
      }
      for (ObjectCreationExpr create : file.cu.findAll(ObjectCreationExpr.class)) {
        Optional<String> resolvedType = resolveCreatedType(create);
        Optional<SourceLocation> location = locationOf(file.path, create);
        Optional<String> callerId = enclosingCallableId(create, pkg);
        if (resolvedType.isPresent() && location.isPresent() && callerId.isPresent()) {
          String typeName = resolvedType.get();
          edges.add(new CallChains.Edge(typeName, callerId.get(), location.get()));
          edges.add(
              new CallChains.Edge(
                  typeName + "#" + simpleName(typeName), callerId.get(), location.get()));
          continue;
        }
        heuristicConstructor(file, create, resolvedType, changedTypes, seen, findings);
      }
    }

    collectStructuralFindings(
        parsedHead, changedMethods, changedTypeDecls, changedFields, seeds, seen, findings);

    CallChains.Expansion expansion = CallChains.fromResolved(seeds, edges);
    for (ImpactFinding finding : expansion.findings()) {
      String key =
          finding.location().path()
              + ":"
              + finding.location().startLine()
              + ":"
              + finding.symbolId();
      if (seen.add(key)) {
        findings.add(finding);
      }
    }
    return new CallerScan(findings, expansion.capped());
  }

  private static void heuristicMethodCaller(
      ParsedFile file,
      MethodCallExpr call,
      Set<String> changedMethods,
      List<ChangedSymbol> changedSymbols,
      Set<String> seen,
      List<ImpactFinding> findings) {
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
        return;
      }
    }
    final Confidence confidence;
    final String matchedOwner;
    if (scopeType.isPresent() && changedMethods.contains(scopeType.get() + "#" + methodName)) {
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
      return;
    }
    String symbolId = matchedOwner + "#" + methodName;
    call.getRange()
        .ifPresent(
            range -> {
              String key = file.path + ":" + range.begin.line + ":" + symbolId;
              if (seen.add(key)) {
                findings.add(
                    new ImpactFinding(
                        "CALLER",
                        confidence,
                        enclosing.orElse(file.path)
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

  private static void heuristicConstructor(
      ParsedFile file,
      ObjectCreationExpr create,
      Optional<String> resolvedType,
      Set<String> changedTypes,
      Set<String> seen,
      List<ImpactFinding> findings) {
    String typeName = resolvedType.orElseGet(() -> create.getType().getNameAsString());
    boolean matched =
        resolvedType.isPresent()
            ? changedTypes.contains(typeName) || changedTypes.contains(simpleName(typeName))
            : changedTypes.contains(typeName);
    if (!matched) {
      return;
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

  private static void collectStructuralFindings(
      Map<String, ParsedFile> parsedHead,
      Set<String> changedMethods,
      Set<String> changedTypeDecls,
      Set<String> changedFields,
      Set<String> seeds,
      Set<String> seen,
      List<ImpactFinding> findings) {
    for (ParsedFile file : parsedHead.values()) {
      String pkg = packageName(file.cu);
      for (MethodDeclaration method : file.cu.findAll(MethodDeclaration.class)) {
        Optional<SourceLocation> location = locationOf(file.path, method);
        if (location.isEmpty()) {
          continue;
        }
        String childId =
            qualify(pkg, enclosingType(method).orElse("")) + "#" + method.getNameAsString();
        for (String parentId : ancestorMethodIds(method)) {
          boolean parentChanged = changedMethods.contains(parentId);
          boolean childChanged = changedMethods.contains(childId);
          if (!parentChanged && !childChanged) {
            continue;
          }
          if (parentChanged) {
            seeds.add(childId);
          }
          addFinding(
              seen,
              findings,
              new ImpactFinding(
                  "OVERRIDE",
                  Confidence.DIRECT,
                  childId + " overrides " + parentId,
                  parentId,
                  location.get()));
        }
      }
      for (FieldAccessExpr access : file.cu.findAll(FieldAccessExpr.class)) {
        emitFieldAccess(file, access, resolveField(access), changedFields, seen, findings);
      }
      for (NameExpr name : file.cu.findAll(NameExpr.class)) {
        emitFieldAccess(file, name, resolveField(name), changedFields, seen, findings);
      }
      for (FieldDeclaration field : file.cu.findAll(FieldDeclaration.class)) {
        boolean inject =
            field.getAnnotations().stream()
                .anyMatch(ann -> INJECT_ANNOTATIONS.contains(ann.getName().getIdentifier()));
        for (VariableDeclarator variable : field.getVariables()) {
          Optional<String> fieldType = resolveVariableType(variable);
          Optional<SourceLocation> location = locationOf(file.path, variable);
          if (fieldType.isEmpty() || location.isEmpty()) {
            continue;
          }
          String typeName = fieldType.get();
          if (!changedTypeDecls.contains(typeName)
              && !changedTypeDecls.contains(simpleName(typeName))) {
            continue;
          }
          String owner = qualify(pkg, enclosingType(field).orElse(file.path));
          String summary =
              inject
                  ? owner + "#" + variable.getNameAsString() + " injects " + typeName
                  : owner + "#" + variable.getNameAsString() + " is typed as " + typeName;
          addFinding(
              seen,
              findings,
              new ImpactFinding(
                  inject ? "INJECTION" : "FIELD_TYPE",
                  Confidence.DIRECT,
                  summary,
                  typeName,
                  location.get()));
        }
      }
      for (AnnotationExpr annotation : file.cu.findAll(AnnotationExpr.class)) {
        Optional<String> typeName = resolveAnnotationType(annotation);
        String simple = annotation.getName().getIdentifier();
        boolean matched =
            typeName
                .map(
                    name ->
                        changedTypeDecls.contains(name)
                            || changedTypeDecls.contains(simpleName(name)))
                .orElseGet(() -> changedTypeDecls.contains(simple));
        if (!matched) {
          continue;
        }
        Optional<SourceLocation> location = locationOf(file.path, annotation);
        if (location.isEmpty()) {
          continue;
        }
        String symbolId = typeName.orElse(simple);
        addFinding(
            seen,
            findings,
            new ImpactFinding(
                "ANNOTATION_USE",
                typeName.isPresent() ? Confidence.DIRECT : Confidence.HEURISTIC,
                enclosingType(annotation).orElse(file.path) + " uses @" + simple,
                symbolId,
                location.get()));
      }
    }
  }

  private static void emitFieldAccess(
      ParsedFile file,
      Node node,
      Optional<ResolvedCall> field,
      Set<String> changedFields,
      Set<String> seen,
      List<ImpactFinding> findings) {
    if (field.isEmpty()) {
      return;
    }
    ResolvedCall match = field.get();
    String symbolId = match.owner() + "#" + match.name();
    if (!changedFields.contains(symbolId)
        && !changedFields.contains(simpleName(match.owner()) + "#" + match.name())) {
      return;
    }
    Optional<SourceLocation> location = locationOf(file.path, node);
    if (location.isEmpty()) {
      return;
    }
    addFinding(
        seen,
        findings,
        new ImpactFinding(
            "FIELD_ACCESS",
            Confidence.DIRECT,
            enclosingType(node).orElse(file.path) + " reads " + symbolId,
            symbolId,
            location.get()));
  }

  private static void addFinding(
      Set<String> seen, List<ImpactFinding> findings, ImpactFinding finding) {
    String key =
        finding.location().path()
            + ":"
            + finding.location().startLine()
            + ":"
            + finding.type()
            + ":"
            + finding.symbolId();
    if (seen.add(key)) {
      findings.add(finding);
    }
  }

  private static List<ImpactFinding> publicApiFindings(List<ChangedSymbol> changedSymbols) {
    List<ImpactFinding> findings = new ArrayList<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if (!symbol.publiclyVisible()) {
        continue;
      }
      if (!"type".equals(symbol.kind())
          && !"method".equals(symbol.kind())
          && !"field".equals(symbol.kind())) {
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

  private static List<String> ancestorMethodIds(MethodDeclaration method) {
    try {
      ResolvedMethodDeclaration resolved = method.resolve();
      String childType = resolved.declaringType().getQualifiedName();
      List<String> ids = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      for (ResolvedReferenceType ancestor :
          resolved.declaringType().asReferenceType().getAllAncestors()) {
        ancestor
            .getTypeDeclaration()
            .ifPresent(
                declaration -> {
                  for (ResolvedMethodDeclaration candidate : declaration.getDeclaredMethods()) {
                    if (!candidate.getSignature().equals(resolved.getSignature())) {
                      continue;
                    }
                    String parentType = candidate.declaringType().getQualifiedName();
                    if (parentType.equals(childType) || "java.lang.Object".equals(parentType)) {
                      continue;
                    }
                    String id = parentType + "#" + candidate.getName();
                    if (seen.add(id)) {
                      ids.add(id);
                    }
                  }
                });
      }
      return ids;
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  private static Optional<ResolvedCall> resolveField(Node node) {
    try {
      final ResolvedValueDeclaration value;
      if (node instanceof FieldAccessExpr access) {
        value = access.resolve();
      } else if (node instanceof NameExpr name) {
        value = name.resolve();
      } else {
        return Optional.empty();
      }
      if (!value.isField()) {
        return Optional.empty();
      }
      ResolvedFieldDeclaration field = value.asField();
      return Optional.of(
          new ResolvedCall(field.declaringType().getQualifiedName(), field.getName()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static Optional<String> resolveVariableType(VariableDeclarator variable) {
    try {
      var resolved = variable.getType().resolve();
      if (resolved.isReferenceType()) {
        return Optional.of(resolved.asReferenceType().getQualifiedName());
      }
      return Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static Optional<String> resolveAnnotationType(AnnotationExpr annotation) {
    try {
      return Optional.of(annotation.resolve().getQualifiedName());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static void addClasspath(CombinedTypeSolver types, List<Path> classpath) {
    if (classpath == null) {
      return;
    }
    for (Path entry : classpath) {
      if (entry == null) {
        continue;
      }
      Path abs = entry.toAbsolutePath().normalize();
      try {
        if (Files.isRegularFile(abs)
            && abs.getFileName() != null
            && abs.getFileName().toString().endsWith(".jar")) {
          types.add(new JarTypeSolver(abs));
        } else if (Files.isDirectory(abs)) {
          try (var stream = Files.list(abs)) {
            List<Path> jars =
                stream
                    .filter(
                        path ->
                            path.getFileName() != null
                                && path.getFileName().toString().endsWith(".jar"))
                    .toList();
            for (Path jar : jars) {
              types.add(new JarTypeSolver(jar));
            }
          }
        }
      } catch (IOException ignored) {
        // Unreadable jars stay off the solver path; those sites remain HEURISTIC.
      }
    }
  }

  private static JavaParser parserWithSolver(
      Path solverRoot, Map<String, String> headJavaFiles, List<Path> classpath) {
    ParserConfiguration language =
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    CombinedTypeSolver types = new CombinedTypeSolver();
    types.add(new ReflectionTypeSolver(true));
    addClasspath(types, classpath);
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

  private static Optional<String> enclosingCallableId(Node node, String pkg) {
    Node current = node;
    while (current != null) {
      if (current instanceof MethodDeclaration method) {
        String owner = qualify(pkg, enclosingType(method).orElse(""));
        return Optional.of(owner + "#" + method.getNameAsString());
      }
      if (current instanceof ConstructorDeclaration ctor) {
        String owner = qualify(pkg, enclosingType(ctor).orElse(""));
        return Optional.of(owner + "#" + ctor.getNameAsString());
      }
      current = current.getParentNode().orElse(null);
    }
    return Optional.empty();
  }

  private static String packageName(CompilationUnit cu) {
    return cu.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
  }

  private static Optional<SourceLocation> locationOf(String path, Node node) {
    return node.getRange().map(range -> new SourceLocation(path, range.begin.line, range.end.line));
  }

  private record CallerScan(List<ImpactFinding> findings, boolean chainCapped) {}

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
