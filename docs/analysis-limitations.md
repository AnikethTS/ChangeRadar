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

Today the tool lists changed files and records:

- `JAVA_SYMBOLS_NOT_IMPLEMENTED` when `.java` files changed.
- `NO_POLICY_ENGINE` on every run until policies exist.
- `STATIC_ANALYSIS_LIMITS` as a standing reminder.

The number of changed files is not a risk score. A one-line public API
break can matter more than dozens of internal edits.

## What we will not claim later either

- Complete impact discovery through generated sources or bytecode we did
  not parse.
- That skipping the rest of the suite is safe because ChangeRadar picked
  three tests.
- That an unresolved symbol means the symbol does not exist.
