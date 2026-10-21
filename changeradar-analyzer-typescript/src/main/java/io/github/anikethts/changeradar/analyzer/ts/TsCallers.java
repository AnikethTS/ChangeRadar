package io.github.anikethts.changeradar.analyzer.ts;

import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.SourceLocation;
import io.github.anikethts.changeradar.core.SourcePaths;
import io.github.anikethts.changeradar.core.StructuralScan;
import io.github.anikethts.changeradar.core.TsConfigPaths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TsCallers {

  static final Pattern DECORATOR = Pattern.compile("^@([A-Za-z_$][\\w$]*)");
  static final Pattern FIELD_ACCESS =
      Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*(?!\\()");
  static final Pattern TYPED_FIELD =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|readonly|declare)\\s+)*"
              + "[A-Za-z_$][\\w$]*\\s*\\??\\s*:\\s*([A-Za-z_$][\\w$]*)");
  static final Set<String> SUPER_KEYWORDS = Set.of("extends", "implements");
  static final Set<String> INJECT_DECORATORS =
      Set.of("Inject", "Injectable", "Autowired", "Resource");
  static final Pattern METHOD =
      Pattern.compile(
          "^(?:(?:public|private|protected|static|async|readonly|override|abstract|get|set)\\s+)*"
              + "(?:async\\s+)?([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(");
  static final Pattern CALL =
      Pattern.compile("(?:([A-Za-z_$][\\w$]*)\\s*\\.)?([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(");
  static final Pattern NEW_TYPE =
      Pattern.compile("\\bnew\\s+([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(");
  static final Pattern IMPORT_NAMED =
      Pattern.compile(
          "(?:import|export)\\s+(?:type\\s+)?\\{([^}]+)\\}\\s+from\\s+['\"]([^'\"]+)['\"]");
  static final Pattern IMPORT_DEFAULT =
      Pattern.compile("import\\s+(?:type\\s+)?([A-Za-z_$][\\w$]*)\\s+from\\s+['\"]([^'\"]+)['\"]");
  static final Pattern IMPORT_STAR =
      Pattern.compile("import\\s+\\*\\s+as\\s+([A-Za-z_$][\\w$]*)\\s+from\\s+['\"]([^'\"]+)['\"]");
  static final Pattern IMPORT_REQUIRE =
      Pattern.compile(
          "(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*require\\s*\\(\\s*['\"]([^'\"]+)['\"]");
  static final Pattern EXPORT_STAR =
      Pattern.compile("export\\s+\\*\\s+from\\s+['\"]([^'\"]+)['\"]");
  static final Set<String> NOT_METHODS =
      Set.of(
          "if",
          "for",
          "while",
          "switch",
          "catch",
          "function",
          "class",
          "return",
          "typeof",
          "await",
          "import",
          "export",
          "void",
          "super",
          "constructor");

  private TsCallers() {}

  static List<ImpactFinding> findCallers(
      Map<String, ParsedFile> parsedHead,
      List<ChangedSymbol> changedSymbols,
      TsConfigPaths.PathMap pathMap) {
    Set<String> changedFns = new LinkedHashSet<>();
    Set<String> changedTypes = new LinkedHashSet<>();
    Map<String, Set<String>> changedNamesByPath = new LinkedHashMap<>();
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
      changedNamesByPath
          .computeIfAbsent(symbol.location().path(), key -> new LinkedHashSet<>())
          .add(symbol.name());
    }
    List<ImpactFinding> findings = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (ParsedFile file : parsedHead.values()) {
      for (int i = 0; i < file.lines().size(); i++) {
        String line = file.lines().get(i);
        int lineNo = i + 1;
        Matcher neu = NEW_TYPE.matcher(line);
        while (neu.find()) {
          String typeName = neu.group(1);
          if (!changedTypes.contains(typeName)) {
            continue;
          }
          Optional<Confidence> confidence =
              importConfidence(
                  file,
                  typeName,
                  typeName,
                  parsedHead,
                  changedNamesByPath,
                  file.testFile(),
                  pathMap);
          if (confidence.isEmpty()) {
            continue;
          }
          String key = file.path() + ":" + lineNo + ":new " + typeName;
          if (seen.add(key)) {
            findings.add(
                new ImpactFinding(
                    "CALLER",
                    confidence.get(),
                    file.path() + " constructs " + typeName,
                    typeName,
                    new SourceLocation(file.path(), lineNo, lineNo)));
          }
        }
        Matcher call = CALL.matcher(line);
        while (call.find()) {
          String receiver = call.group(1);
          String name = call.group(2);
          if (NOT_METHODS.contains(name) || !changedFns.contains(name)) {
            continue;
          }
          boolean definition =
              file.decls().stream()
                  .anyMatch(decl -> decl.startLine() == lineNo && decl.name().equals(name));
          if (definition && receiver == null) {
            continue;
          }
          String binding = receiver != null ? receiver : name;
          Optional<Confidence> confidence =
              importConfidence(
                  file, binding, name, parsedHead, changedNamesByPath, file.testFile(), pathMap);
          if (confidence.isEmpty()) {
            continue;
          }
          String symbolId = receiver != null ? receiver + "#" + name : name;
          String key = file.path() + ":" + lineNo + ":" + symbolId;
          if (seen.add(key)) {
            findings.add(
                new ImpactFinding(
                    "CALLER",
                    confidence.get(),
                    file.path()
                        + " calls "
                        + symbolId
                        + " ("
                        + confidence.get().name().toLowerCase()
                        + ")",
                    symbolId,
                    new SourceLocation(file.path(), lineNo, lineNo)));
          }
        }
      }
    }
    return findings;
  }

  static List<ImpactFinding> findStructural(
      Map<String, ParsedFile> parsedHead,
      List<ChangedSymbol> changedSymbols,
      TsConfigPaths.PathMap pathMap) {
    Set<String> changedFields = new LinkedHashSet<>();
    Set<String> changedTypeDecls = StructuralScan.typeDeclsWithoutNestedMembers(changedSymbols);
    Map<String, Set<String>> changedNamesByPath = new LinkedHashMap<>();
    Set<String> changedOwnerMethods = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("field".equals(symbol.kind())) {
        changedFields.add(symbol.name());
      }
      if ("method".equals(symbol.kind()) || "constructor".equals(symbol.kind())) {
        if (!symbol.owner().isEmpty()) {
          changedOwnerMethods.add(symbol.owner() + "#" + symbol.name());
        }
      }
      changedNamesByPath
          .computeIfAbsent(symbol.location().path(), key -> new LinkedHashSet<>())
          .add(symbol.name());
    }
    List<ImpactFinding> findings = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (ParsedFile file : parsedHead.values()) {
      for (Decl decl : file.decls()) {
        if (!"method".equals(decl.kind()) && !"constructor".equals(decl.kind())) {
          continue;
        }
        for (String parent : file.supers().getOrDefault(decl.owner(), List.of())) {
          boolean parentChanged = changedOwnerMethods.contains(parent + "#" + decl.name());
          boolean childChanged = changedOwnerMethods.contains(decl.owner() + "#" + decl.name());
          if (!parentChanged && !childChanged) {
            continue;
          }
          Optional<Confidence> confidence =
              importConfidence(
                  file,
                  parent,
                  decl.name(),
                  parsedHead,
                  changedNamesByPath,
                  file.testFile(),
                  pathMap);
          if (confidence.isEmpty()
              && parentChanged
              && file.path().equals(parentFile(parsedHead, parent))) {
            confidence = Optional.of(Confidence.DIRECT);
          }
          if (confidence.isEmpty()) {
            continue;
          }
          StructuralScan.addFinding(
              seen,
              findings,
              new ImpactFinding(
                  "OVERRIDE",
                  confidence.get(),
                  decl.owner() + "#" + decl.name() + " overrides " + parent + "#" + decl.name(),
                  parent + "#" + decl.name(),
                  new SourceLocation(file.path(), decl.startLine(), decl.endLine())));
        }
      }
      for (int i = 0; i < file.lines().size(); i++) {
        String trimmed = file.lines().get(i).trim();
        int lineNo = i + 1;
        Matcher decorator = DECORATOR.matcher(trimmed);
        if (decorator.find() && changedTypeDecls.contains(decorator.group(1))) {
          String name = decorator.group(1);
          Optional<Confidence> confidence =
              importConfidence(
                  file, name, name, parsedHead, changedNamesByPath, file.testFile(), pathMap);
          if (confidence.isPresent()) {
            StructuralScan.addFinding(
                seen,
                findings,
                new ImpactFinding(
                    "ANNOTATION_USE",
                    confidence.get(),
                    file.path() + " uses @" + name,
                    name,
                    new SourceLocation(file.path(), lineNo, lineNo)));
          }
        }
        Matcher typed = TYPED_FIELD.matcher(trimmed);
        if (typed.find() && changedTypeDecls.contains(typed.group(1))) {
          String typeName = typed.group(1);
          final int lineIndex = i;
          Optional<Confidence> confidence =
              importConfidence(
                  file,
                  typeName,
                  typeName,
                  parsedHead,
                  changedNamesByPath,
                  file.testFile(),
                  pathMap);
          if (confidence.isPresent()) {
            boolean inject =
                INJECT_DECORATORS.stream()
                    .anyMatch(
                        ann ->
                            trimmed.contains("@" + ann)
                                || previousIsDecorator(file, lineIndex, ann));
            StructuralScan.addFinding(
                seen,
                findings,
                new ImpactFinding(
                    inject ? "INJECTION" : "FIELD_TYPE",
                    confidence.get(),
                    file.path() + (inject ? " injects " : " is typed as ") + typeName,
                    typeName,
                    new SourceLocation(file.path(), lineNo, lineNo)));
          }
        }
        Matcher access = FIELD_ACCESS.matcher(file.lines().get(i));
        while (access.find()) {
          String name = access.group(2);
          if (!changedFields.contains(name) || NOT_METHODS.contains(name)) {
            continue;
          }
          Optional<Confidence> confidence =
              importConfidence(
                  file, name, name, parsedHead, changedNamesByPath, file.testFile(), pathMap);
          if (confidence.isEmpty()) {
            continue;
          }
          StructuralScan.addFinding(
              seen,
              findings,
              new ImpactFinding(
                  "FIELD_ACCESS",
                  confidence.get(),
                  file.path() + " reads " + name,
                  name,
                  new SourceLocation(file.path(), lineNo, lineNo)));
        }
      }
    }
    return findings;
  }

  static boolean previousIsDecorator(ParsedFile file, int index, String name) {
    if (index == 0) {
      return false;
    }
    return file.lines().get(index - 1).trim().startsWith("@" + name);
  }

  static String parentFile(Map<String, ParsedFile> parsedHead, String typeName) {
    for (ParsedFile file : parsedHead.values()) {
      if (file.decls().stream()
          .anyMatch(decl -> "type".equals(decl.kind()) && decl.name().equals(typeName))) {
        return file.path();
      }
    }
    return "";
  }

  static Optional<Confidence> importConfidence(
      ParsedFile file,
      String binding,
      String symbolName,
      Map<String, ParsedFile> parsedHead,
      Map<String, Set<String>> changedNamesByPath,
      boolean allowTestHeuristic,
      TsConfigPaths.PathMap pathMap) {
    if (changedNamesByPath.getOrDefault(file.path(), Set.of()).contains(symbolName)
        && file.decls().stream()
            .anyMatch(decl -> decl.name().equals(binding) || decl.name().equals(symbolName))) {
      return Optional.of(Confidence.DIRECT);
    }
    String spec = file.imports().bindings().get(binding);
    if (spec != null) {
      Optional<Boolean> hit =
          moduleHoldsChangedName(
              file.path(),
              spec,
              symbolName,
              parsedHead,
              changedNamesByPath,
              new LinkedHashSet<>(),
              pathMap);
      if (hit.isEmpty()) {
        return Optional.of(Confidence.HEURISTIC);
      }
      if (hit.get()) {
        return Optional.of(Confidence.DIRECT);
      }
    }
    for (String module : file.imports().modules()) {
      if (moduleHoldsChangedName(
              file.path(),
              module,
              symbolName,
              parsedHead,
              changedNamesByPath,
              new LinkedHashSet<>(),
              pathMap)
          .orElse(false)) {
        return Optional.of(Confidence.DIRECT);
      }
    }
    if (allowTestHeuristic) {
      return Optional.of(Confidence.HEURISTIC);
    }
    return Optional.empty();
  }

  /**
   * True when {@code specifier} from {@code fromFile} is (or re-exports) a HEAD module whose
   * changed names include {@code symbolName}. Empty when the first specifier does not resolve.
   */
  static Optional<Boolean> moduleHoldsChangedName(
      String fromFile,
      String specifier,
      String symbolName,
      Map<String, ParsedFile> parsedHead,
      Map<String, Set<String>> changedNamesByPath,
      Set<String> visited,
      TsConfigPaths.PathMap pathMap) {
    Optional<String> resolved =
        SourcePaths.resolveExisting(
            fromFile, specifier, parsedHead.keySet(), TsSupport.MODULE_SUFFIXES, pathMap);
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    String path = resolved.get();
    if (!visited.add(path)) {
      return Optional.of(false);
    }
    if (changedNamesByPath.getOrDefault(path, Set.of()).contains(symbolName)) {
      return Optional.of(true);
    }
    ParsedFile target = parsedHead.get(path);
    if (target == null) {
      return Optional.of(false);
    }
    String namedSpec = target.imports().bindings().get(symbolName);
    if (namedSpec != null
        && moduleHoldsChangedName(
                path, namedSpec, symbolName, parsedHead, changedNamesByPath, visited, pathMap)
            .orElse(false)) {
      return Optional.of(true);
    }
    for (String star : target.imports().starExports()) {
      if (moduleHoldsChangedName(
              path, star, symbolName, parsedHead, changedNamesByPath, visited, pathMap)
          .orElse(false)) {
        return Optional.of(true);
      }
    }
    return Optional.of(false);
  }
}
