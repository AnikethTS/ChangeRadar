package io.github.anikethts.changeradar.analyzer.java;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.nodeTypes.NodeWithThrownExceptions;
import com.github.javaparser.ast.type.Type;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.LineRange;
import io.github.anikethts.changeradar.core.SourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class JavaSupport {

  private JavaSupport() {}

  static boolean sameIgnoringTrivia(String baseSource, String headSource) {
    if (baseSource == null || headSource == null) {
      return false;
    }
    String baseDump = astDump(baseSource);
    String headDump = astDump(headSource);
    return baseDump != null && baseDump.equals(headDump);
  }

  private static String astDump(String source) {
    ParseResult<CompilationUnit> parsed = JavaParsing.PARSER.parse(source);
    if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
      return null;
    }
    CompilationUnit copy = parsed.getResult().get().clone();
    for (Comment comment : new ArrayList<>(copy.getAllComments())) {
      comment.remove();
    }
    return copy.toString();
  }

  static boolean overlaps(List<LineRange> hunks, int start, int end) {
    for (LineRange hunk : hunks) {
      if (hunk.overlaps(start, end)) {
        return true;
      }
    }
    return false;
  }

  static String qualify(String pkg, String nested) {
    if (nested.isEmpty()) {
      return pkg;
    }
    if (pkg.isEmpty()) {
      return nested;
    }
    return pkg + "." + nested;
  }

  static String simpleName(String owner) {
    int dot = owner.lastIndexOf('.');
    return dot >= 0 ? owner.substring(dot + 1) : owner;
  }

  static boolean isTestPath(String path) {
    return path.contains("/test/") || path.contains("\\test\\") || path.contains("/src/test");
  }

  static boolean isTestName(String name) {
    return name.endsWith("Test") || name.endsWith("Tests");
  }

  static String publicApiSummary(ChangedSymbol symbol) {
    String label =
        "type".equals(symbol.kind()) || symbol.owner().isEmpty()
            ? symbol.name()
            : symbol.methodKey();
    if (symbol.changeType() == ChangeType.DELETE) {
      return "Public " + symbol.kind() + " " + label + " was removed";
    }
    if (symbol.changeType() == ChangeType.ADD) {
      return "Public " + symbol.kind() + " " + label + " was added";
    }
    return "Public " + symbol.kind() + " " + label + " occupies changed lines";
  }

  static String parameterSignature(CallableDeclaration<?> callable) {
    List<String> types = new ArrayList<>();
    callable.getParameters().forEach(parameter -> types.add(parameter.getType().asString()));
    return String.join(",", types);
  }

  static String thrownSignature(NodeWithThrownExceptions<?> node) {
    List<String> types = new ArrayList<>();
    for (Type thrown : node.getThrownExceptions()) {
      types.add(thrown.asString());
    }
    types.sort(String::compareTo);
    return String.join(",", types);
  }

  static String callableAbi(CallableDeclaration<?> callable) {
    String thrown = thrownSignature(callable);
    if (callable instanceof MethodDeclaration method) {
      return method.getType().asString() + ":" + thrown;
    }
    return thrown;
  }

  static String callableKey(CallableDeclaration<?> callable) {
    try {
      if (callable instanceof MethodDeclaration method) {
        return method.resolve().getSignature();
      }
      if (callable instanceof ConstructorDeclaration ctor) {
        return ctor.resolve().getSignature();
      }
    } catch (RuntimeException ignored) {
      // Fall back to source-level parameter types.
    }
    return callable.getNameAsString() + "(" + parameterSignature(callable) + ")";
  }

  static boolean methodKeyMatches(String key, String owner, String methodName) {
    String prefix = owner + "#" + methodName;
    return key.equals(prefix) || key.startsWith(prefix + "(");
  }

  static boolean methodKeyNamed(String key, String methodName) {
    int hash = key.indexOf('#');
    String rest = hash < 0 ? key : key.substring(hash + 1);
    return rest.equals(methodName) || rest.startsWith(methodName + "(");
  }

  static String typeName(TypeDeclaration<?> type) {
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

  static Optional<String> enclosingType(Node node) {
    Node current = node;
    while (current != null) {
      if (current instanceof TypeDeclaration<?> declaration) {
        return Optional.of(typeName(declaration));
      }
      current = current.getParentNode().orElse(null);
    }
    return Optional.empty();
  }

  static Optional<String> enclosingCallableId(Node node, String pkg) {
    Node current = node;
    while (current != null) {
      if (current instanceof MethodDeclaration method) {
        String owner = qualify(pkg, enclosingType(method).orElse(""));
        return Optional.of(owner + "#" + callableKey(method));
      }
      if (current instanceof ConstructorDeclaration ctor) {
        String owner = qualify(pkg, enclosingType(ctor).orElse(""));
        return Optional.of(owner + "#" + callableKey(ctor));
      }
      current = current.getParentNode().orElse(null);
    }
    return Optional.empty();
  }

  static String packageName(CompilationUnit cu) {
    return cu.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
  }

  static Optional<SourceLocation> locationOf(String path, Node node) {
    return node.getRange().map(range -> new SourceLocation(path, range.begin.line, range.end.line));
  }
}
