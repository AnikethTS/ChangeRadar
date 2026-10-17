# Architecture

ChangeRadar is a local CLI with a language-neutral core. Java,
TypeScript/JavaScript, Python, and PHP are the deep analyzers. CI systems
invoke the same CLI.

## Layers

| Layer | Responsibility |
| --- | --- |
| CLI (`changeradar-cli`) | Parse arguments, validate refs, choose output formats, map exceptions to exit codes. |
| Git adapter (`changeradar-git`) | Repository state, base/head resolution, changed files. JGit, hidden behind `GitRepository`. |
| Language analyzer API (`changeradar-core`) | Parsing and relationship extraction behind `LanguageAnalyzer`. |
| Java analyzer (`changeradar-analyzer-java`) | JavaParser: changed types/methods, name-based callers, related tests, public API occupancy and removals. |
| TypeScript analyzer (`changeradar-analyzer-typescript`) | TS/JS classes, functions, exports, name-based callers, related tests. Not tsc. |
| Python analyzer (`changeradar-analyzer-python`) | Classes and functions, name-based callers, related tests. Not CPython. |
| PHP analyzer (`changeradar-analyzer-php`) | Classes and functions, name-based callers, related tests. Not php-parser. |
| Impact / policy / report (`changeradar-core`) | Typed findings, confidence, evidence, optional policies. Policies stay independent of severity guesses. |
| Reporters | Terminal, Markdown, JSON. GitHub Actions (and later GitLab/Jenkins) are thin wrappers around the CLI. |

Flow:

```text
Git diff → changed files → language analyzer → (later) symbol/dependency index
  → impact traversal → findings → policy evaluation → reports → CI
```

JavaParser, JGit, and GitHub must not leak into the report schema or exit-code
contract. If a new language or CI vendor appears, it should be a new adapter
plus tests against the shared model.

## Tradeoffs (Phase 0)

- **JGit instead of shelling out to `git`.** Predictable on Windows/macOS/Linux
  CI images, and we never invoke the analyzed project's Git hooks by accident.
  Cost: we reimplement a small amount of Git behavior; merge-base nuances get
  tests, not folklore.
- **Gradle modules now, few classes inside them.** Boundaries are core /
  git / java / typescript / python / php / CLI. No extra modules for policy
  packs or a web UI until those are real adapters.
- **Java 21 toolchain, not “whatever the developer has.”** Matches the LTS
  the brief asked for. Foojay resolver can provision 21 if the outer JDK is
  older.
- **No LLM on the analysis path.** Deterministic, local, free to run in CI.
  An LLM can never be required to produce a report.
- **Do not execute the project's build.** Source is untrusted input. Analysis
  reads files; it does not run Maven/Gradle/npm from the target repo.

## Roadmap

| Phase | Deliverable | Acceptance |
| --- | --- | --- |
| 0 Foundation | Gradle, CLI skeleton, docs, CI | Clean checkout builds and tests. **Current.** |
| 1 Git diff | Base/head, changed files | Additions, edits, deletions, invalid refs. **Current.** |
| 2 Java symbols | Changed lines → classes/methods | Fixture tests. **Current.** |
| 3 Graph | Call edges, callers, evidence paths | Java: in-repo + JRE symbol solver. TS/Python/PHP: import/require/`use` against HEAD files. **Partial.** |
| 4 Tests | Related tests with reasons | Every suggestion has confidence. **Current.** |
| 5 Reports | Richer terminal/Markdown/JSON | Stable ordering, limitations. **Current.** |
| 6 CI contract | Output dir, base/head on a clean runner | Documented and tested. **Current.** |
| 7 GitHub Actions | Example + artifacts / optional PR comment | Example, artifacts, and a fork-safe PR comment job. **Current.** |
| 8 Policies | Optional blocking rules | Only explicit rules block; tool failure stays `2`. **Current.** |
| 9 More analyzers | TypeScript, then Python | TS/JS, Python, and PHP current. |

## MVP non-goals

No web dashboard, no paid API, no claiming complete impact for reflection or
generated code, no skipping tests or deploying from a heuristic, no blocking
the pipeline by default for uncertain findings, no uploading source to a
remote service by default.
