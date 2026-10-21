package io.github.anikethts.changeradar.analyzer.java;

import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.callableKey;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.enclosingCallableId;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.enclosingType;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.locationOf;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.methodKeyMatches;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.methodKeyNamed;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.packageName;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.qualify;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.simpleName;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
import io.github.anikethts.changeradar.core.CallChains;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class JavaCallers {

  private static final Set<String> INJECT_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");

  private JavaCallers() {}

  static CallerScan findCallers(
      Map<String, ParsedFile> parsedHead, List<ChangedSymbol> changedSymbols) {
    Set<String> changedMethods = new LinkedHashSet<>();
    Set<String> changedTypes = new LinkedHashSet<>();
    Set<String> changedTypeDecls = new LinkedHashSet<>();
    Set<String> changedFields = new LinkedHashSet<>();
    Set<String> seeds = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("method".equals(symbol.kind()) || "constructor".equals(symbol.kind())) {
        String id = symbol.methodKey();
        changedMethods.add(id);
        seeds.add(id);
        String simpleOwner = simpleName(symbol.owner());
        if (!simpleOwner.isEmpty()) {
          String rest = id.substring(id.indexOf('#') + 1);
          changedMethods.add(simpleOwner + "#" + rest);
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
      String pkg = packageName(file.cu());
      for (MethodCallExpr call : file.cu().findAll(MethodCallExpr.class)) {
        Optional<ResolvedCall> resolved = resolveMethodCall(call);
        Optional<SourceLocation> location = locationOf(file.path(), call);
        Optional<String> callerId = enclosingCallableId(call, pkg);
        if (resolved.isPresent() && location.isPresent() && callerId.isPresent()) {
          ResolvedCall match = resolved.get();
          String calleeId = match.owner() + "#" + match.name();
          edges.add(new CallChains.Edge(calleeId, callerId.get(), location.get()));
          continue;
        }
        heuristicMethodCaller(file, call, changedMethods, changedSymbols, seen, findings);
      }
      for (ObjectCreationExpr create : file.cu().findAll(ObjectCreationExpr.class)) {
        Optional<String> resolvedType = resolveCreatedType(create);
        Optional<SourceLocation> location = locationOf(file.path(), create);
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
    if (enclosing.isPresent()
        && changedMethods.stream()
            .anyMatch(key -> methodKeyMatches(key, enclosing.get(), methodName))) {
      boolean insideChanged =
          call.getRange()
              .map(
                  range ->
                      changedSymbols.stream()
                          .anyMatch(
                              symbol ->
                                  symbol.location().path().equals(file.path())
                                      && symbol.location().startLine() <= range.begin.line
                                      && range.end.line <= symbol.location().endLine()
                                      && methodName.equals(symbol.name())))
              .orElse(false);
      if (insideChanged && scopeType.isEmpty()) {
        return;
      }
    }
    final Confidence confidence = Confidence.HEURISTIC;
    final String matchedOwner;
    if (scopeType.isPresent()
        && changedMethods.stream()
            .anyMatch(key -> methodKeyMatches(key, scopeType.get(), methodName))) {
      matchedOwner = scopeType.get();
    } else if (scopeType.isEmpty()
        && enclosing.isPresent()
        && changedMethods.stream()
            .anyMatch(key -> methodKeyMatches(key, enclosing.get(), methodName))) {
      // Unresolved this/static call: only the enclosing type, not every parseBase() in the
      // repo.
      matchedOwner = enclosing.get();
    } else if (scopeType.isPresent()
        && changedMethods.stream().anyMatch(id -> methodKeyNamed(id, methodName))) {
      matchedOwner =
          changedMethods.stream()
              .filter(id -> methodKeyNamed(id, methodName))
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
              String key = file.path() + ":" + range.begin.line + ":" + symbolId;
              if (seen.add(key)) {
                findings.add(
                    new ImpactFinding(
                        "CALLER",
                        confidence,
                        enclosing.orElse(file.path())
                            + " calls "
                            + symbolId
                            + " ("
                            + confidence.name().toLowerCase()
                            + ")",
                        symbolId,
                        new SourceLocation(file.path(), range.begin.line, range.end.line)));
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
              String key = file.path() + ":" + range.begin.line + ":new " + typeName;
              if (seen.add(key)) {
                findings.add(
                    new ImpactFinding(
                        "CALLER",
                        confidence,
                        enclosingType(create).orElse(file.path()) + " constructs " + typeName,
                        typeName,
                        new SourceLocation(file.path(), range.begin.line, range.end.line)));
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
      String pkg = packageName(file.cu());
      for (MethodDeclaration method : file.cu().findAll(MethodDeclaration.class)) {
        Optional<SourceLocation> location = locationOf(file.path(), method);
        if (location.isEmpty()) {
          continue;
        }
        String childId = qualify(pkg, enclosingType(method).orElse("")) + "#" + callableKey(method);
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
      for (FieldAccessExpr access : file.cu().findAll(FieldAccessExpr.class)) {
        emitFieldAccess(file, access, resolveField(access), changedFields, seen, findings);
      }
      for (NameExpr name : file.cu().findAll(NameExpr.class)) {
        emitFieldAccess(file, name, resolveField(name), changedFields, seen, findings);
      }
      for (FieldDeclaration field : file.cu().findAll(FieldDeclaration.class)) {
        boolean inject =
            field.getAnnotations().stream()
                .anyMatch(ann -> INJECT_ANNOTATIONS.contains(ann.getName().getIdentifier()));
        for (VariableDeclarator variable : field.getVariables()) {
          Optional<String> fieldType = resolveVariableType(variable);
          Optional<SourceLocation> location = locationOf(file.path(), variable);
          if (fieldType.isEmpty() || location.isEmpty()) {
            continue;
          }
          String typeName = fieldType.get();
          if (!changedTypeDecls.contains(typeName)
              && !changedTypeDecls.contains(simpleName(typeName))) {
            continue;
          }
          String owner = qualify(pkg, enclosingType(field).orElse(file.path()));
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
      for (AnnotationExpr annotation : file.cu().findAll(AnnotationExpr.class)) {
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
        Optional<SourceLocation> location = locationOf(file.path(), annotation);
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
                enclosingType(annotation).orElse(file.path()) + " uses @" + simple,
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
    Optional<SourceLocation> location = locationOf(file.path(), node);
    if (location.isEmpty()) {
      return;
    }
    addFinding(
        seen,
        findings,
        new ImpactFinding(
            "FIELD_ACCESS",
            Confidence.DIRECT,
            enclosingType(node).orElse(file.path()) + " reads " + symbolId,
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

  private static Optional<ResolvedCall> resolveMethodCall(MethodCallExpr call) {
    try {
      ResolvedMethodDeclaration resolved = call.resolve();
      return Optional.of(
          new ResolvedCall(resolved.declaringType().getQualifiedName(), resolved.getSignature()));
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
                    String id = parentType + "#" + candidate.getSignature();
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
}
