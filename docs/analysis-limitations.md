# Analysis limitations

Static analysis cannot fully resolve reflection, dynamic dispatch, generated
code, dependency injection, runtime configuration, or external-service
behavior. ChangeRadar must say so in the report rather than implying that
silence means safety.

## Evidence classes

| Class | Example | How to report it |
| --- | --- | --- |
| Direct | Resolved method call from a changed method to a caller | Source location and relationship. |
| Indirect | Caller chain reaches a known endpoint | Full path and assumptions. |
| Heuristic | Test selected by naming or directory convention | Label as a suggestion, not a proven dependency. |
| Unknown | Reflection or unresolved dispatch | State the limitation; never imply no impact. |

“Potentially affected” is not “will break.” A suggested test is not proof
that the test passed or failed. Optional `--coverage` / `--junit` ingest
reports the consumer already wrote; ChangeRadar still does not run tests.

## Current implementation

Today the tool records:

- `JAVA_SYMBOL_SOLVER` when Java callers are type-resolved against HEAD
  sources and the JRE (and `--classpath` jars when provided). Resolved
  sites match one overload signature; unresolved sites stay HEURISTIC.
- `JAVA_CLASSPATH` when `--classpath` of already-built jars was passed
  (ChangeRadar does not run the target build).
- `SCOPED_SOURCES` when parsers skipped HEAD files that were neither
  changed, same-directory Java neighbors, nor import/require/`use`
  importers (`--full-index` disables this).
- `POLICY_BASELINE` when `--baseline` / `--policy-file` baseline keys are
  ignored.
- `FINDING_SUPPRESSIONS` when `--suppress-file` / YAML `suppressions:`
  (or `--write-suppressions`) omit matching finding ids from the report.
  Ids include `path:line`; edits that shift lines require a refresh.
- `JAVA_CALL_CHAINS` when indirect Java callers walk resolved method and
  constructor edges (depth 1 `DIRECT`, deeper `INDIRECT`, max depth 3).
  Heuristic name matches are not chained.
- `JAVA_CALL_CHAIN_CAP` when indirect findings hit the cap of 40.
- `PY_CALL_CHAINS` when indirect Python callers walk import-resolved
  function and method edges (depth 1 `DIRECT`, deeper `INDIRECT`, max
  depth 3). Heuristic name matches are not chained.
- `PY_CALL_CHAIN_CAP` when those indirect findings hit the cap of 40.
- `PHP_CALL_CHAINS` when indirect PHP callers walk use/require-resolved
  function and method edges (depth 1 `DIRECT`, deeper `INDIRECT`, max
  depth 3). Heuristic name matches are not chained.
- `PHP_CALL_CHAIN_CAP` when those indirect findings hit the cap of 40.
- `JAVA_STRUCTURAL_EDGES` when Java overrides, field access, annotation
  uses, and inject-style fields are resolved against HEAD sources (not a
  Spring/Jakarta container).
- `NAME_BASED_DISPATCH` when a Java call site could not be resolved and
  fell back to method-name matching (`HEURISTIC`).
- `TS_IMPORTS` / `PY_IMPORTS` / `PHP_IMPORTS` when callers are matched
  using import/require/`use` specifiers against HEAD files (not tsc,
  CPython, php-parser, or Composer autoload). TypeScript follows
  `export * from`, named `export { … } from`, and nearest
  `tsconfig.json` / `jsconfig.json` `paths` (not `extends`; `export * as`
  namespaces are still missed).
- `TS_TSCONFIG_PATHS` when at least one of those configs declared
  `compilerOptions.paths`.
- `TS_STRUCTURAL_EDGES` / `PY_STRUCTURAL_EDGES` / `PHP_STRUCTURAL_EDGES`
  when overrides, field reads, and decorator/attribute uses are matched
  the same way (not a compiler or autoloader).
- `TS_NAME_BASED_DISPATCH` / `PY_NAME_BASED_DISPATCH` /
  `PHP_NAME_BASED_DISPATCH` when a call site could not be tied to an
  import of the changed symbol (`HEURISTIC`, including many tests).
- `POLICIES_DISABLED` when no `--policy` flags were passed.
- `JAVA_PARSE_FAILED` when a source file could not be parsed.
- `COVERAGE_INGEST` when `--coverage` JaCoCo XML or LCOV was read
  (suite-wide hits, not per-test). `COVERAGE_PARSE_FAILED` when a file
  was not coverage data.
- `JUNIT_INGEST` when `--junit` XML annotated pass/fail.
  `JUNIT_PARSE_FAILED` when a file was not JUnit XML.
- Public Java ABI checks compare source-level parameter types, return
  type, declared `throws`, field type, and `public` vs not. They are not
  japicmp (`final`/`static`, constant inlining, and erasure are omitted).
- `STATIC_ANALYSIS_LIMITS` as a standing reminder.

The number of changed files is not a risk score. A one-line public API
break can matter more than dozens of internal edits.

## What we will not claim later either

- Complete impact discovery through generated sources or bytecode we did
  not parse.
- That skipping the rest of the suite is safe because ChangeRadar picked
  three tests.
- That an unresolved symbol means the symbol does not exist.
