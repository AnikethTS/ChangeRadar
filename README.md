<div align="center">

<img src=".github/logo.png" alt="ChangeRadar logo" width="560" />

# ChangeRadar

**Explainable code-change impact analysis for CI/CD.**

[![CI](https://github.com/AnikethTS/ChangeRadar/actions/workflows/ci.yml/badge.svg)](https://github.com/AnikethTS/ChangeRadar/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?logo=openjdk&logoColor=white)](build.gradle.kts)
[![Gradle](https://img.shields.io/badge/Gradle-wrapper-02303A?logo=gradle&logoColor=white)](gradle/wrapper)
[![GitHub Actions](https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?logo=githubactions&logoColor=white)](docs/ci-integration.md)
[![Site](https://img.shields.io/badge/site-ChangeRadar-0b1224)](https://anikethts.github.io/ChangeRadar/)

</div>

If we merge this change, what else might it affect — and what should we
verify before release?

ChangeRadar reads a Git diff, traces what the change may touch, recommends
relevant tests, and writes the evidence as a report you can run locally or
as a step in the pipeline you already have. It does not replace GitHub
Actions, GitLab CI, Jenkins, your test runner, or your deploy tooling. It
adds impact intelligence to those systems.

**Status:** `0.2.6` — Java, Kotlin, TypeScript/JavaScript, Python, PHP, SQL, shell,
and GitHub Actions workflow
impact reports, a published fat JAR / GitHub Action (Marketplace:
ChangeRadar Impact Analysis), compact PR comments, Checks annotations,
finding-id suppressions, and optional policies (`changeradar policies`,
`--policy-file`, baseline).
Limitations stay in the report instead of implying a complete graph.

## Quick start

From a **consuming** repository (JDK 21+), after a ChangeRadar release that
ships `changeradar.jar`:

```bash
java -jar changeradar.jar analyze \
  --base "$BASE_SHA" \
  --head "$HEAD_SHA" \
  --format compact,markdown,json,sarif \
  --output build/changeradar
```

GitHub Actions: pin the composite Action to a **commit SHA** (the wrapper).
Omit `version` to download GitHub’s **latest** `changeradar.jar`. Set
`version: v0.2.6` only when you want that exact analyzer. Copy
[`examples/github-actions/changeradar.yml`](examples/github-actions/changeradar.yml)
into `.github/workflows/changeradar.yml` (checkout + one step; comment,
Checks notices, and the artifact are inside the Action). JVM repos can
copy [`changeradar-java.yml`](examples/github-actions/changeradar-java.yml)
instead.

```yaml
- uses: AnikethTS/ChangeRadar@8795047ab4617ca241d5a69a8bb7c3e2aece1832 # wrapper on main
  with:
    base: ${{ github.event.pull_request.base.sha }}
    head: ${{ github.event.pull_request.head.sha }}
    # version: v0.2.6   # pin the JAR; omit for latest
```

Developing ChangeRadar itself:

```bash
git clone https://github.com/AnikethTS/ChangeRadar.git && cd ChangeRadar
./run.sh analyze --base HEAD~1 --head HEAD --format terminal,markdown,json --output build/changeradar
```

That builds the CLI with the Gradle Wrapper (JDK 21+). `./gradlew
:changeradar-cli:shadowJar` writes `changeradar-cli/build/libs/changeradar.jar`.
See [Development](docs/development.md).

## Sample report

```text
ChangeRadar 0.2.6
schema 1.0.0
base   HEAD~1
head   HEAD
repo   /path/to/repo

Changed files (6)
  MODIFY  .github/workflows/codeql.yml
  MODIFY  README.md
  …

Changed symbols (0)
  (none)

Potentially affected (0)
  (none)

Tests to consider (0)
  (none)

Limitations
  [SCOPED_SOURCES] Parsers ran on 0 of 52 HEAD source files …
  [POLICIES_DISABLED] No blocking policies were enabled. …
  [STATIC_ANALYSIS_LIMITS] Reflection, dynamic dispatch, …

Policy  none blocking

Exit 0 means analysis completed, not that the change is safe.
```

Findings, when present, are grouped by file and include a stable `id`.
`changeradar policies` lists blocking rule names (`list-policies`).

A suggested test will never be phrased as “this test failed” unless
ChangeRadar actually ran it — and the MVP does not run tests.

## Why this exists

Pipelines already build and test according to whatever steps you configured.
They rarely explain the blast radius of *this* diff: which callers move,
which tests are related, whether a public signature changed, or why the
tool is unsure. ChangeRadar exists to make those questions answerable in
the same place you already review the change.

## What it does

- Compares explicit `--base` and `--head` Git refs (SHAs or branch names).
- Lists changed files, including additions, edits, deletions, and renames.
- Maps changed Java lines to types and methods, including overloads
  (JavaParser). Comment and whitespace-only Java, Kotlin, TypeScript,
  Python, PHP, SQL, shell, and GitHub Actions workflow diffs are `FORMAT_ONLY_CHANGES`,
  not symbol occupancy.
- Maps changed Kotlin lines to types and functions (not kotlinc).
- Maps changed TypeScript/JavaScript lines to classes, functions, and
  exports (not tsc).
- Maps changed Python lines to classes and functions (not CPython).
- Maps changed PHP lines to classes and functions (not php-parser).
- Maps changed SQL lines to tables, views, functions, and procedures.
  Other `.sql` files that name the object are callers. Embedded SQL in
  application code is not scanned. This is not a database.
- Maps changed GitHub Actions workflows to jobs, reusable workflows, and
  local actions. Callers are same-file `needs` and local `uses: ./...`
  paths. Remote actions are not resolved. This does not run the workflow.
- Maps changed shell lines to functions in `.sh` files. Other scripts that
  call the function, or `source` the file, are callers. The script is not
  executed.
- Finds likely callers and related tests, labeled DIRECT, INDIRECT, or
  HEURISTIC. Java, Kotlin, Python, and PHP callers of callers are INDIRECT (capped). Java also
  reports overrides, field reads, annotation uses, and inject-style
  fields. TypeScript, Python, and PHP report the same when an import
  maps to the change. TypeScript also follows `export * from` barrels and
  `tsconfig` `paths` aliases (still not tsc).
- Flags public Java members and exported TS/JS symbols on changed lines.
- Writes **terminal**, **Markdown**, **JSON**, **compact**, **SARIF**, and
  **github** (Checks annotations) reports with a stable schema version.
- Lists named policies with `changeradar policies` (alias `list-policies`).
- Distinguishes a completed analysis (`0`), a configured policy violation
  (`1`, only with `--policy`), and a tool/input failure (`2`).
- Records limitations in the report instead of pretending the graph is
  complete.

Public Java binary breaks (return type, `throws`, field type, overload
removal, narrowed visibility) trip `breaking-public-java-api` when that
policy is enabled. See the [roadmap](docs/architecture.md#roadmap).

Curious how the pieces fit? [Architecture](docs/architecture.md) and
[How analysis is limited](docs/analysis-limitations.md).

## What it is not

- Not a diff viewer, generic dashboard, linter, formatter, or
  vulnerability scanner.
- Not a replacement for running tests, human review, or other static
  analysis.
- Not a guarantee that a change will break something — or that it will not.
- Not a deployment platform. It never deploys, rolls back, or skips the
  full test suite based on a heuristic score.

## CI/CD

The CLI is the interface. Provider integrations wrap it; they should not
reimplement analysis.

```bash
java -jar changeradar.jar analyze \
  --base "$BASE_SHA" \
  --head "$HEAD_SHA" \
  --format compact,markdown,json,sarif \
  --output build/changeradar
```

| Exit code | Meaning |
| --- | --- |
| `0` | Analysis completed; no enabled blocking policy was violated. |
| `1` | A configured blocking policy was violated. |
| `2` | Analysis failed, or input/configuration was invalid. |

A high heuristic risk score must not fail the job unless a team explicitly
enables that policy. A failed analysis is not the same as a detected risk.

A least-privilege GitHub Actions example lives in
[`examples/github-actions/changeradar.yml`](examples/github-actions/changeradar.yml)
(copy to `.github/workflows/changeradar.yml`).
Pin `uses: AnikethTS/ChangeRadar@<sha>`. This repository dogfoods the
Action with a locally built fat JAR. Same-repo PRs get a **compact**
sticky comment, a job summary, and notice annotations on the Files changed
tab from inside the Action; the full Markdown / JSON / SARIF stay on the
artifact. Fork PRs skip the comment and annotations. Do not upload
ChangeRadar SARIF to Code Scanning. Java repos may compile in the workflow
and pass `classpath:` of those jars so typed callers can be `DIRECT`.
After your own test job, pass `coverage:` (JaCoCo/LCOV) and `junit:`
(JUnit XML); ChangeRadar does not run those tests.
Details: [CI integration](docs/ci-integration.md).

## Docs

- [Project site](https://anikethts.github.io/ChangeRadar/) — overview and how to contribute
- [Architecture](docs/architecture.md) — layers, tradeoffs, roadmap
- [CI integration](docs/ci-integration.md) — exit codes, artifacts, GitHub Actions
- [Analysis limitations](docs/analysis-limitations.md) — what static analysis cannot claim
- [Policy configuration](docs/policy-configuration.md) — `--policy`, fail-open vs fail-closed
- [Development](docs/development.md) — tests, modules, formatting
- [Adding a language analyzer](docs/adding-a-language-analyzer.md)
- [Adding a CI provider](docs/adding-a-ci-provider.md)
- [CONTRIBUTING.md](CONTRIBUTING.md) — how to send a PR
- [SECURITY.md](SECURITY.md) — how to report a vulnerability

## License

MIT — see [LICENSE](LICENSE).
