package io.github.anikethts.changeradar.analyzer.java;

import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.callableAbi;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.callableKey;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.enclosingType;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.isTestName;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.isTestPath;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.qualify;
import static io.github.anikethts.changeradar.analyzer.java.JavaSupport.typeName;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.github.anikethts.changeradar.core.Limitation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavaParsing {

  static final JavaParser PARSER =
      new JavaParser(
          new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

  private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

  private JavaParsing() {}

  static void parse(
      JavaParser parser,
      String path,
      String source,
      Map<String, ParsedFile> into,
      List<Limitation> limitations) {
    parse(parser, path, source, into, limitations, null);
  }

  static void parse(
      JavaParser parser,
      String path,
      String source,
      Map<String, ParsedFile> into,
      List<Limitation> limitations,
      InMemoryJavaTypeSolver sources) {
    try {
      CompilationUnit cu;
      if (sources != null) {
        cu = sources.unit(path, source);
        if (cu == null) {
          limitations.add(new Limitation("JAVA_PARSE_FAILED", "Could not parse " + path + "."));
          return;
        }
      } else {
        ParseResult<CompilationUnit> parsed = parser.parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
          limitations.add(
              new Limitation(
                  "JAVA_PARSE_FAILED", "Could not parse " + path + ": " + parsed.getProblems()));
          return;
        }
        cu = parsed.getResult().get();
      }
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

  static ParsedFile parseBase(
      String path, Map<String, String> baseJavaFiles, List<Limitation> limitations) {
    String source = baseJavaFiles.get(path);
    if (source == null) {
      return null;
    }
    Map<String, ParsedFile> parsed = new LinkedHashMap<>();
    parse(PARSER, path, source, parsed, limitations);
    return parsed.get(path);
  }

  record SolvedParser(JavaParser parser, InMemoryJavaTypeSolver sources) {}

  /** Type-resolves HEAD sources already held in memory. Does not copy them to a temp directory. */
  static SolvedParser parserWithSolver(Map<String, String> headJavaFiles, List<Path> classpath) {
    InMemoryJavaTypeSolver sources = new InMemoryJavaTypeSolver(headJavaFiles);
    CombinedTypeSolver types = new CombinedTypeSolver();
    types.add(new ReflectionTypeSolver(true));
    addClasspath(types, classpath);
    types.add(sources);
    JavaParser parser =
        new JavaParser(
            new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(types)));
    sources.use(parser);
    return new SolvedParser(parser, sources);
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
                      callableKey(callable),
                      callableAbi(callable),
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
                          "",
                          field.isPublic(),
                          isTestPath(path) || isTestName(owner),
                          path,
                          range.begin.line,
                          range.end.line)));
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
}
