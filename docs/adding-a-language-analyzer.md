# Adding a language analyzer

The core is language-neutral. Java, Kotlin, TypeScript/JavaScript, Python,
and PHP are wired today. A new language is an adapter that maps that language's
symbols and relationships into the shared model — not a fork of the CLI or
report schema.

## Contract

Keep the adapter as a small facade. Put parse/index, callers/structural
edges, and test recommendations in package-private types next to it so
the public class stays under a few hundred lines.

Implement `io.github.anikethts.changeradar.core.LanguageAnalyzer`:

- `languageId()` — stable id (`java`, `typescript`, `python`).
- `supports(ChangedFile)` — typically file extension / path convention.
- Limitations when the adapter cannot complete work the CLI would otherwise
  imply succeeded.

`analyze(..., AnalysisOptions)` is optional; the default delegates to the
three-argument form. Java uses `options.classpath()` for already-built
jars. Keep Git, policies, and reporters out of the adapter.

## Suggested steps

1. New Gradle module, same pattern as `changeradar-analyzer-java`.
2. Depend on `changeradar-core` only (`api`), not on JGit or picocli.
3. Fixture repo under `fixtures/` with a known diff and expected symbols.
4. Wire the adapter from the CLI the same way Java is wired — one extra
   implementation, not a parallel pipeline.
5. Document what the adapter cannot see (dynamic imports, generated code)
   as `Limitation`s.

Do not add an analyzer “for completeness” without tests against the shared
report fields.
