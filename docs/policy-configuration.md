# Policy configuration

Policies are optional and independent of analysis. A finding is evidence; a
policy is a team decision about whether that evidence should fail the job.

Enable them only when you mean to block:

```bash
./run.sh analyze \
  --base "$BASE_SHA" \
  --head "$HEAD_SHA" \
  --policy breaking-public-java-api \
  --policy analysis-unavailable
```

`--policy` is repeatable or comma-separated. Unknown names are invalid input
(exit `2`). With no `--policy` flags, analysis never returns `1`.

## Principles

- Nothing blocks a merge solely because a heuristic score is “high.”
- Only an *explicitly enabled* rule can produce exit `1`.
- Analyzer/tool failure is always exit `2`, never `1` — except when you
  enable `analysis-unavailable`, which treats a failed parse of a *changed*
  Java file as a policy violation (`1`), not a tool crash.
- Default is fail-open for uncertain findings: report them, do not fail.
- Fail-closed for *tool* failure is a CI-workflow choice (for example
  `set -e` vs `|| true`), not a hidden CLI default.

## Built-in rules (off by default)

- `breaking-public-java-api` — a public Java type or method/constructor was
  **removed** (deleted file or deleted member), with a `DELETE` changed
  symbol. Body edits of public methods do not trip this rule. Test sources
  are ignored. Overloads are distinguished by parameter types, not by a
  full binary-compatibility checker.
- `analysis-unavailable` — a changed `.java` file could not be parsed
  (`JAVA_PARSE_FAILED`). Distinct from “we found risk.”

Repository-specific policy packs come after those two are tested.
