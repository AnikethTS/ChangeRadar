package io.github.anikethts.changeradar.analyzer.py;

import io.github.anikethts.changeradar.core.CallChains;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.SourceLocation;
import io.github.anikethts.changeradar.core.SourcePaths;
import io.github.anikethts.changeradar.core.StructuralScan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PyCallers {

  static final Pattern TYPED_ATTR = Pattern.compile("^([A-Za-z_][\\w]*)\\s*:\\s*([A-Za-z_][\\w]*)");
  static final Pattern DECORATOR = Pattern.compile("^@([A-Za-z_][\\w]*)");
  static final Pattern FIELD_ACCESS =
      Pattern.compile("([A-Za-z_][\\w]*)\\s*\\.\\s*([A-Za-z_][\\w]*)\\s*(?!\\()");
  static final Set<String> INJECT_DECORATORS = Set.of("inject", "Inject", "autowired");
  static final Pattern CALL =
      Pattern.compile("(?:([A-Za-z_][\\w]*)\\s*\\.)?([A-Za-z_][\\w]*)\\s*\\(");
  static final Set<String> NOT_CALLS =
      Set.of(
          "if", "elif", "for", "while", "def", "class", "return", "with", "except", "assert",
          "lambda", "not", "and", "or", "in", "is");

  private PyCallers() {}

  static CallerScan findCallers(
      Map<String, ParsedFile> parsedHead, List<ChangedSymbol> changedSymbols) {
    Set<String> changedFns = new LinkedHashSet<>();
    Set<String> changedTypes = new LinkedHashSet<>();
    Map<String, Set<String>> changedNamesByPath = new LinkedHashMap<>();
    Set<String> seeds = new LinkedHashSet<>();
    for (ChangedSymbol symbol : changedSymbols) {
      if ("function".equals(symbol.kind())
          || "method".equals(symbol.kind())
          || "constructor".equals(symbol.kind())) {
        changedFns.add(symbol.name());
        seeds.add(symbolSeed(symbol));
      }
      if ("type".equals(symbol.kind())) {
        changedTypes.add(symbol.name());
        seeds.add(symbol.location().path() + "#" + symbol.name());
      } else if (!symbol.owner().isEmpty()) {
        changedTypes.add(symbol.owner());
      }
      changedNamesByPath
          .computeIfAbsent(symbol.location().path(), key -> new LinkedHashSet<>())
          .add(symbol.name());
    }
    List<CallChains.Edge> edges = new ArrayList<>();
    List<ImpactFinding> findings = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (ParsedFile file : parsedHead.values()) {
      for (int i = 0; i < file.lines().size(); i++) {
        String line = file.lines().get(i);
        int lineNo = i + 1;
        Matcher call = CALL.matcher(line);
        while (call.find()) {
          String receiver = call.group(1);
          String name = call.group(2);
          if (NOT_CALLS.contains(name)) {
            continue;
          }
          boolean definition =
              file.decls().stream()
                  .anyMatch(decl -> decl.startLine() == lineNo && decl.name().equals(name));
          if (definition && receiver == null) {
            continue;
          }
          Optional<String> enclosing = enclosingCallableId(file, lineNo);
          SourceLocation location = new SourceLocation(file.path(), lineNo, lineNo);
          if (receiver == null) {
            Optional<String> constructed = resolveTypeId(file, name, parsedHead);
            if (constructed.isPresent()) {
              if (enclosing.isPresent()) {
                edges.add(new CallChains.Edge(constructed.get(), enclosing.get(), location));
              } else if (changedTypes.contains(name)) {
                Optional<Confidence> confidence =
                    importConfidence(
                        file, name, name, parsedHead, changedNamesByPath, file.testFile());
                if (confidence.isPresent()
                    && seen.add(file.path() + ":" + lineNo + ":new " + name)) {
                  findings.add(
                      new ImpactFinding(
                          "CALLER",
                          confidence.get(),
                          file.path() + " constructs " + name,
                          name,
                          location));
                }
              }
              continue;
            }
          }
          Optional<String> callee = resolveCallableId(file, receiver, name, parsedHead);
          if (callee.isPresent()) {
            if (enclosing.isPresent()) {
              edges.add(new CallChains.Edge(callee.get(), enclosing.get(), location));
            } else if (changedFns.contains(name)) {
              Optional<Confidence> confidence =
                  importConfidence(
                      file,
                      receiver != null ? receiver : name,
                      name,
                      parsedHead,
                      changedNamesByPath,
                      file.testFile());
              if (confidence.isPresent()) {
                String symbolId = receiver != null ? receiver + "#" + name : name;
                if (seen.add(file.path() + ":" + lineNo + ":" + symbolId)) {
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
                          location));
                }
              }
            }
            continue;
          }
          if (!changedFns.contains(name)) {
            continue;
          }
          String binding = receiver != null ? receiver : name;
          Optional<Confidence> confidence =
              importConfidence(
                  file, binding, name, parsedHead, changedNamesByPath, file.testFile());
          if (confidence.isEmpty() || confidence.get() == Confidence.DIRECT) {
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
                    location));
          }
        }
      }
    }
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

  static String symbolSeed(ChangedSymbol symbol) {
    if (symbol.owner().isEmpty()) {
      return symbol.location().path() + "#" + symbol.name();
    }
    return symbol.location().path() + "#" + symbol.owner() + "#" + symbol.name();
  }

  static String declId(Decl decl) {
    if ("type".equals(decl.kind()) || decl.owner().isEmpty()) {
      return decl.path() + "#" + decl.name();
    }
    return decl.path() + "#" + decl.owner() + "#" + decl.name();
  }

  static boolean isCallableKind(String kind) {
    return "function".equals(kind) || "method".equals(kind) || "constructor".equals(kind);
  }

  static Optional<String> enclosingCallableId(ParsedFile file, int lineNo) {
    Decl best = null;
    for (Decl decl : file.decls()) {
      if (!isCallableKind(decl.kind())) {
        continue;
      }
      if (decl.startLine() <= lineNo && lineNo <= decl.endLine()) {
        if (best == null || decl.startLine() >= best.startLine()) {
          best = decl;
        }
      }
    }
    return best == null ? Optional.empty() : Optional.of(declId(best));
  }

  static Optional<ParsedFile> resolveImportedFile(
      ParsedFile file, String binding, Map<String, ParsedFile> parsedHead) {
    String spec = file.imports().bindings().get(binding);
    if (spec != null) {
      return SourcePaths.resolveExisting(
              file.path(), spec, parsedHead.keySet(), PySupport.MODULE_SUFFIXES)
          .map(parsedHead::get);
    }
    for (String module : file.imports().modules()) {
      Optional<String> resolved =
          SourcePaths.resolveExisting(
              file.path(), module, parsedHead.keySet(), PySupport.MODULE_SUFFIXES);
      if (resolved.isPresent()) {
        return Optional.ofNullable(parsedHead.get(resolved.get()));
      }
    }
    return Optional.empty();
  }

  static Optional<String> resolveCallableId(
      ParsedFile file, String receiver, String name, Map<String, ParsedFile> parsedHead) {
    if (receiver == null) {
      for (Decl decl : file.decls()) {
        if (isCallableKind(decl.kind()) && decl.name().equals(name)) {
          return Optional.of(declId(decl));
        }
      }
    } else {
      for (Decl decl : file.decls()) {
        if (isCallableKind(decl.kind())
            && decl.name().equals(name)
            && decl.owner().equals(receiver)) {
          return Optional.of(declId(decl));
        }
      }
    }
    String binding = receiver != null ? receiver : name;
    Optional<ParsedFile> imported = resolveImportedFile(file, binding, parsedHead);
    if (imported.isEmpty()) {
      return Optional.empty();
    }
    for (Decl decl : imported.get().decls()) {
      if (isCallableKind(decl.kind()) && decl.name().equals(name)) {
        return Optional.of(declId(decl));
      }
    }
    return Optional.empty();
  }

  static Optional<String> resolveTypeId(
      ParsedFile file, String name, Map<String, ParsedFile> parsedHead) {
    for (Decl decl : file.decls()) {
      if ("type".equals(decl.kind()) && decl.name().equals(name)) {
        return Optional.of(declId(decl));
      }
    }
    Optional<ParsedFile> imported = resolveImportedFile(file, name, parsedHead);
    if (imported.isEmpty()) {
      return Optional.empty();
    }
    for (Decl decl : imported.get().decls()) {
      if ("type".equals(decl.kind()) && decl.name().equals(name)) {
        return Optional.of(declId(decl));
      }
    }
    return Optional.empty();
  }

  static List<ImpactFinding> findStructural(
      Map<String, ParsedFile> parsedHead, List<ChangedSymbol> changedSymbols) {
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
                  file, parent, decl.name(), parsedHead, changedNamesByPath, file.testFile());
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
              importConfidence(file, name, name, parsedHead, changedNamesByPath, file.testFile());
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
        Matcher typed = TYPED_ATTR.matcher(trimmed);
        if (typed.find() && changedTypeDecls.contains(typed.group(2))) {
          String typeName = typed.group(2);
          final int lineIndex = i;
          Optional<Confidence> confidence =
              importConfidence(
                  file, typeName, typeName, parsedHead, changedNamesByPath, file.testFile());
          if (confidence.isPresent()) {
            boolean inject =
                INJECT_DECORATORS.stream()
                    .anyMatch(ann -> previousIsDecorator(file, lineIndex, ann));
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
          if (!changedFields.contains(name) || NOT_CALLS.contains(name)) {
            continue;
          }
          Optional<Confidence> confidence =
              importConfidence(file, name, name, parsedHead, changedNamesByPath, file.testFile());
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

  static Optional<Confidence> importConfidence(
      ParsedFile file,
      String binding,
      String symbolName,
      Map<String, ParsedFile> parsedHead,
      Map<String, Set<String>> changedNamesByPath,
      boolean allowTestHeuristic) {
    if (changedNamesByPath.getOrDefault(file.path(), Set.of()).contains(symbolName)
        && file.decls().stream()
            .anyMatch(decl -> decl.name().equals(binding) || decl.name().equals(symbolName))) {
      return Optional.of(Confidence.DIRECT);
    }
    String spec = file.imports().bindings().get(binding);
    if (spec != null) {
      Optional<String> resolved =
          SourcePaths.resolveExisting(
              file.path(), spec, parsedHead.keySet(), PySupport.MODULE_SUFFIXES);
      if (resolved.isPresent()
          && changedNamesByPath.getOrDefault(resolved.get(), Set.of()).contains(symbolName)) {
        return Optional.of(Confidence.DIRECT);
      }
      if (resolved.isEmpty()) {
        return Optional.of(Confidence.HEURISTIC);
      }
    }
    for (String module : file.imports().modules()) {
      Optional<String> resolved =
          SourcePaths.resolveExisting(
              file.path(), module, parsedHead.keySet(), PySupport.MODULE_SUFFIXES);
      if (resolved.isPresent()
          && changedNamesByPath.getOrDefault(resolved.get(), Set.of()).contains(symbolName)) {
        return Optional.of(Confidence.DIRECT);
      }
    }
    if (allowTestHeuristic) {
      return Optional.of(Confidence.HEURISTIC);
    }
    return Optional.empty();
  }
}
