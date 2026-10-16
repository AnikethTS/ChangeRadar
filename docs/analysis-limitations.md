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
that the test passed or failed unless ChangeRadar actually ran it (the MVP
does not run tests).

## Current implementation

Today the tool records:

- `JAVA_SYMBOL_SOLVER` when Java callers are type-resolved against HEAD
  sources and the JRE (not compiled dependencies).
- `NAME_BASED_DISPATCH` when a Java call site could not be resolved and
  fell back to method-name matching (`HEURISTIC`).
- `TS_NAME_BASED_DISPATCH` when TypeScript/JavaScript callers are matched
  by name without tsc.
- `PY_NAME_BASED_DISPATCH` when Python callers are matched by name without
  CPython.
- `PHP_NAME_BASED_DISPATCH` when PHP callers are matched by name without
  php-parser.
- `POLICIES_DISABLED` when no `--policy` flags were passed.
- `JAVA_PARSE_FAILED` when a source file could not be parsed.
- `STATIC_ANALYSIS_LIMITS` as a standing reminder.

The number of changed files is not a risk score. A one-line public API
break can matter more than dozens of internal edits.

## What we will not claim later either

- Complete impact discovery through generated sources or bytecode we did
  not parse.
- That skipping the rest of the suite is safe because ChangeRadar picked
  three tests.
- That an unresolved symbol means the symbol does not exist.
