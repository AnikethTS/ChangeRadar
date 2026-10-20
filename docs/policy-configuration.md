# Policy configuration

Policies are optional and independent of analysis. A finding is evidence; a
policy is a team decision about whether that evidence should fail the job.

List names:

```bash
java -jar changeradar.jar policies
# alias: list-policies
```

Enable them only when you mean to block. Prefer a committed YAML file in
the consuming repo over a pile of CLI flags:

```yaml
# changeradar.yml — see examples/changeradar.yml
policies:
  - breaking-public-java-api
  - analysis-unavailable
baseline: .changeradar/baseline.txt
```

```bash
java -jar changeradar.jar analyze \
  --base "$BASE_SHA" \
  --head "$HEAD_SHA" \
  --policy-file changeradar.yml
```

`--policy` is still repeatable or comma-separated and is merged with the
file. Unknown names are invalid input (exit `2`). With no `--policy` flags
and no `--policy-file` policies, analysis never returns `1`.

## Baseline

The first time a rule is turned on, existing public API would otherwise
fail the job. Seed a baseline (one violation key per line), commit it, and
point `--baseline` or the YAML `baseline:` field at that file:

```bash
java -jar changeradar.jar analyze \
  --base "$BASE_SHA" \
  --head "$HEAD_SHA" \
  --policy-file changeradar.yml \
  --write-baseline .changeradar/baseline.txt
```

`--write-baseline` records current violation keys and does **not** fail
that run. Later runs fail only on keys that are not in the baseline.

## Renames and signatures

DELETE+ADD of the same kind/owner/name is coalesced before policy:

- Same path → `MODIFY` plus a signature-change violation (parameter/type
  edits of a public member).
- Different path → `RENAME`, which is **not** treated as a removal.

True removals (no matching ADD) still trip the breaking-public-* rules.

## Principles

- Nothing blocks a merge solely because a heuristic score is “high.”
- Only an *explicitly enabled* rule can produce exit `1`.
- Analyzer/tool failure is always exit `2`, never `1` — except when you
  enable `analysis-unavailable`, which treats a failed parse of a *changed*
  Java, TypeScript/JavaScript, Python, or PHP file as a policy violation
  (`1`), not a tool crash.
- Default is fail-open for uncertain findings: report them, do not fail.
- Fail-closed for *tool* failure is a CI-workflow choice (for example
  `set -e` vs `|| true`), not a hidden CLI default.

## Built-in rules (off by default)

- `breaking-public-java-api` — a public Java type or method/constructor was
  **removed** or its **signature changed**. File moves of the same member
  are `RENAME`, not removals. Body edits of public methods do not trip this
  rule. Test sources are ignored. Overloads are distinguished by pairing
  the same simple name; this is not a full binary-compatibility checker.
- `breaking-public-export` — an exported TypeScript/JavaScript function,
  class, or type was **removed** or its signature changed. Body edits do
  not trip this rule. Test sources are ignored. This is not a full semver /
  `.d.ts` checker.
- `breaking-public-python` — a public Python class or function was
  **removed** or its signature changed (name does not start with `_`,
  except dunders). Body edits do not trip this rule. Test modules are
  ignored. This is not a full ABI checker.
- `breaking-public-php` — a public PHP class or function was **removed**
  or its signature changed. `private`/`protected` members and names
  starting with `_` (except dunders) do not count. Body edits do not trip
  this rule. This is not a full reflection / Composer ABI checker.
- `analysis-unavailable` — a changed `.java`, `.ts`/`.tsx`, `.js`/`.jsx`,
  `.py`, or `.php` file could not be parsed (`JAVA_PARSE_FAILED`,
  `TS_PARSE_FAILED`, `PY_PARSE_FAILED`, or `PHP_PARSE_FAILED`). Distinct
  from “we found risk.”
