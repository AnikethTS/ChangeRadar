package io.github.anikethts.changeradar.analyzer.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves in-repo Java types from the HEAD sources already loaded in memory. Each file is parsed
 * once and reused by the analyzer. This is not a second copy of the tree on disk.
 */
final class InMemoryJavaTypeSolver implements TypeSolver {

  private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

  private final Map<String, String> sources;
  private final Map<String, String> pathByType;
  private final Map<String, CompilationUnit> units = new HashMap<>();
  private final Map<String, SymbolReference<ResolvedReferenceTypeDeclaration>> solved =
      new HashMap<>();
  private final Set<String> parsing = new HashSet<>();
  private final Set<String> resolving = new HashSet<>();
  private JavaParser parser;
  private TypeSolver parent;

  InMemoryJavaTypeSolver(Map<String, String> sources) {
    this.sources = sources;
    this.pathByType = index(sources);
  }

  void use(JavaParser parser) {
    this.parser = parser;
  }

  /**
   * @return the compilation unit for {@code path}, parsed at most once, or {@code null} when the
   *     file does not parse
   */
  CompilationUnit unit(String path, String source) {
    CompilationUnit existing = units.get(path);
    if (existing != null) {
      return existing;
    }
    if (parser == null || !parsing.add(path)) {
      return null;
    }
    try {
      ParseResult<CompilationUnit> result = parser.parse(source);
      if (!result.isSuccessful() || result.getResult().isEmpty()) {
        return null;
      }
      CompilationUnit unit = result.getResult().get();
      units.put(path, unit);
      return unit;
    } finally {
      parsing.remove(path);
    }
  }

  @Override
  public TypeSolver getParent() {
    return parent;
  }

  @Override
  public void setParent(TypeSolver parent) {
    this.parent = parent;
  }

  @Override
  public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
    if (name == null || name.isBlank()) {
      return SymbolReference.unsolved();
    }
    SymbolReference<ResolvedReferenceTypeDeclaration> cached = solved.get(name);
    if (cached != null) {
      return cached;
    }
    if (!resolving.add(name)) {
      return SymbolReference.unsolved();
    }
    try {
      SymbolReference<ResolvedReferenceTypeDeclaration> result = solve(name);
      solved.put(name, result);
      return result;
    } finally {
      resolving.remove(name);
    }
  }

  @Override
  public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(
      String name, String moduleName) {
    return SymbolReference.unsolved();
  }

  private SymbolReference<ResolvedReferenceTypeDeclaration> solve(String name) {
    String prefix = name;
    while (prefix != null) {
      String path = pathByType.get(prefix);
      if (path != null) {
        CompilationUnit unit = unit(path, sources.get(path));
        if (unit == null) {
          return SymbolReference.unsolved();
        }
        Optional<TypeDeclaration<?>> type = find(unit, name);
        if (type.isEmpty()) {
          return SymbolReference.unsolved();
        }
        try {
          return SymbolReference.solved(JavaParserFacade.get(this).getTypeDeclaration(type.get()));
        } catch (RuntimeException e) {
          return SymbolReference.unsolved();
        }
      }
      int dot = prefix.lastIndexOf('.');
      prefix = dot < 0 ? null : prefix.substring(0, dot);
    }
    return SymbolReference.unsolved();
  }

  private static Optional<TypeDeclaration<?>> find(CompilationUnit unit, String qualified) {
    String pkg =
        unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
    for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
      String nested = JavaSupport.typeName(type);
      String name = pkg.isEmpty() ? nested : pkg + "." + nested;
      if (name.equals(qualified)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  private static Map<String, String> index(Map<String, String> sources) {
    Map<String, String> byType = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      String path = entry.getKey().replace('\\', '/');
      int slash = path.lastIndexOf('/');
      String file = slash < 0 ? path : path.substring(slash + 1);
      if (!file.endsWith(".java")
          || "package-info.java".equals(file)
          || "module-info.java".equals(file)) {
        continue;
      }
      String simple = file.substring(0, file.length() - ".java".length());
      Matcher matcher = PACKAGE_DECL.matcher(entry.getValue());
      String pkg = matcher.find() ? matcher.group(1) : "";
      String qualified = pkg.isEmpty() ? simple : pkg + "." + simple;
      byType.putIfAbsent(qualified, entry.getKey());
    }
    return byType;
  }
}
