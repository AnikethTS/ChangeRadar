# CI integration

ChangeRadar is a CLI packaged as `changeradar.jar`. CI jobs check out enough
Git history, pass explicit base and head SHAs, and publish the files it
writes. Integrations must not duplicate analysis logic or clone this
source tree to run Gradle.

## Command

```bash
java -jar changeradar.jar analyze \
  --base "$BASE_SHA" \
  --head "$HEAD_SHA" \
  --format compact,markdown,json,sarif \
  --output build/changeradar
```

`--repo` defaults to the current directory. `--format` is a comma-separated
list of `terminal`, `markdown`, `json`, `compact`, `sarif`, and `github`.
File formats are written under `--output` as `report.md`, `report.json`,
`report.compact.md`, `report.sarif`, and `report.github.json`. If
`--output` is omitted and a file format is requested, the default
directory is `build/changeradar`.

When `GITHUB_STEP_SUMMARY` is set (GitHub Actions), the compact report is
appended there as well.

## Exit codes

| Code | Enum | Meaning |
| --- | --- | --- |
| `0` | `SUCCESS` | Analysis completed; no enabled blocking policy was violated. |
| `1` | `POLICY_VIOLATION` | A configured blocking policy was violated (`--policy` / `--policy-file`). |
| `2` | `FAILURE` | Invalid arguments, unreadable repository, unresolved Git ref, unsupported `--format`, or an analyzer/tool error. |

CI must preserve this distinction. Do not treat “the report looks scary” as
exit `1` unless a named policy said so. Do not treat exit `2` as “the change
is risky.”

Fail-open vs fail-closed (whether exit `2` should fail the GitHub job) is a
*workflow* decision, documented in [policy configuration](policy-configuration.md).
The CLI itself still returns `2` for tool failure.

## GitHub Actions

Consumers copy
[`examples/github-actions/impact.yml`](../examples/github-actions/impact.yml)
and pin **this repository's composite Action to a commit SHA**, the same
way they pin `actions/checkout`. The `version` input is a ChangeRadar
*release tag* whose assets include `changeradar.jar` (or `latest`). Pass
`jar:` only when the fat JAR is already on disk (this repo's dogfood
workflow).

This repository builds the JAR and runs [`.github/workflows/impact.yml`](../.github/workflows/impact.yml).
Publishing a GitHub Release triggers [`.github/workflows/release.yml`](../.github/workflows/release.yml)
which uploads `changeradar.jar`.

This repository's [CI workflow](../.github/workflows/ci.yml) still runs
`./gradlew check` and a CLI smoke job on Ubuntu, macOS, and Windows (JDK 21
and 25). Jobs use `bash` on every OS so `./gradlew` and `./run.sh` behave
the same. A `CI` gate job fails unless every required job succeeded.

Requirements:

- Checkout with enough history that `$BASE_SHA` exists. `fetch-depth: 0` is
  the simple, correct default. Shallow clones that omit the base commit
  must fail with exit `2`, not a silent empty report.
- Pass `github.event.pull_request.base.sha` and
  `github.event.pull_request.head.sha` (or `github.sha` on push).
- `permissions: contents: read` is enough to analyze. Writing a pull-request
  comment needs `pull-requests: write`. Annotating the Files changed tab
  needs `checks: write`. Forked PRs from untrusted repos should not run
  privileged comment or check-run jobs.
- Pin third-party actions **and** `AnikethTS/ChangeRadar` to immutable
  commit SHAs.
- Upload `build/changeradar/` as an artifact.

Optional Java type resolution: pass `--classpath` (or the Action `classpath`
input) pointing at **already-built** jars from a previous compile step.
ChangeRadar does not run the target Maven/Gradle build.

### Pull request comments and check summary

The optional `comment` job posts `report.compact.md` (counts, policy
result, top DIRECT findings, tests, link to the artifact). Full Markdown
and JSON stay on the artifact. The compact body is also written to
`$GITHUB_STEP_SUMMARY`.

- Workflow default permission stays `contents: read`. Only the comment job
  sets `pull-requests: write` (plus `actions: read` to download the
  artifact).
- The job runs only when
  `github.event.pull_request.head.repo.full_name == github.repository`.
  Fork PRs skip it so an untrusted workflow cannot write comments with a
  privileged token.
- One sticky comment is created and then updated (HTML marker
  `<!-- changeradar-report -->`).
- The author is **github-actions[bot]** (`GITHUB_TOKEN`). GitHub does not
  allow a custom name or avatar for that account. The comment body starts
  with the ChangeRadar logo so the report is still recognizable.
- A failed comment must not be treated as a policy violation. Do not map
  comment-job failure to CLI exit `1`.

### Diff annotations (not Code Scanning)

Do **not** upload ChangeRadar SARIF with `github/codeql-action/upload-sarif`.
That turns on GitHub Code Scanning, files impact findings as security
alerts, and GitHub’s advanced-security bot reports “more than 20 potential
problems.” Impact is not a vulnerability scan.

`--format github` writes `report.github.json`. The example workflow posts
that as a Checks API run (`annotation_level: notice`, DIRECT then INDIRECT,
capped at 50) so GitHub can show findings on the Files changed tab without
opening the Security tab.

`--format sarif` still writes SARIF 2.1 as an artifact for tools that
consume SARIF. Results use SARIF `note` (not `warning`/`error`). Leave it
on the artifact unless you intentionally want Code Scanning.

GitLab CI, Jenkins, and generic shell examples wait until this contract is
stable and tested.

## JSON schema

Reports include `schemaVersion` (`1.0.0` today). Fields will grow
(`changedSymbols`, `recommendedTests`, `policyViolations`) without reusing
names for different meanings. Findings include `id`. See
[`schemas/report-v1.schema.json`](../schemas/report-v1.schema.json).
