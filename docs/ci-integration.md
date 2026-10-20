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

Consumers copy a template into **their** repo as
`.github/workflows/changeradar.yml`:

| File | Use when |
| --- | --- |
| [`examples/github-actions/changeradar.yml`](../examples/github-actions/changeradar.yml) | Default. Checkout + one `uses:` step. |
| [`examples/github-actions/changeradar-java.yml`](../examples/github-actions/changeradar-java.yml) | JVM repo: compile, then `classpath:`. |

The Action downloads the JAR, analyzes, uploads `changeradar-report`, posts
a sticky PR comment, and posts Checks notices. Set `comment: false`,
`annotate: false`, or `upload: false` to skip those. Optional keys in the
template (`version`, `policy-file`, `suppress-file`) are commented in.

There are two version knobs:

| Knob | What it is | Default for other repos |
| --- | --- | --- |
| `uses: AnikethTS/ChangeRadar@<sha>` | The Action wrapper (`action.yml`: Java 21, download, report). | Pin a SHA, same as `actions/checkout`. |
| `version` | Which `changeradar.jar` to download. | **`latest`** — GitHub’s latest published release. Omit the input. |

Pin a specific analyzer with `version: v0.2.2`. Pass `jar:` only when the fat
JAR is already on disk (this repo’s dogfood workflow).

```yaml
jobs:
  impact:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      checks: write
      pull-requests: write
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          fetch-depth: 0
      - uses: AnikethTS/ChangeRadar@5c888d9c6f8a1e2bc08228b56cdff04891f8f8b6
        with:
          base: ${{ github.event.pull_request.base.sha }}
          head: ${{ github.event.pull_request.head.sha }}
          # version: v0.2.2
```

This repository builds the JAR and runs [`.github/workflows/impact.yml`](../.github/workflows/impact.yml).
Publishing a GitHub Release triggers [`.github/workflows/release.yml`](../.github/workflows/release.yml)
which uploads `changeradar.jar`.

This repository's [CI workflow](../.github/workflows/ci.yml) still runs
`./gradlew check` and a CLI smoke job on current Ubuntu, macOS, and Windows
images (JDK 21 and 25) and on the previous hosted images `ubuntu-24.04`,
`ubuntu-22.04`, `windows-2022`, and `macos-15` (JDK 21). GitHub-hosted
runners have no Windows 10 label; `windows-2022` is the prior Server image.
Jobs use `bash` on every OS so `./gradlew` and `./run.sh` behave the same.
A `CI` gate job fails unless every required job succeeded.

Requirements:

- Checkout with enough history that `$BASE_SHA` exists. `fetch-depth: 0` is
  the simple, correct default. Shallow clones that omit the base commit
  must fail with exit `2`, not a silent empty report.
- Pass `github.event.pull_request.base.sha` and
  `github.event.pull_request.head.sha` (or `github.sha` on push).
- `permissions: contents: read` is enough to analyze. Sticky comments need
  `pull-requests: write` on the job; Files changed notices need
  `checks: write`. Forked PRs skip those steps even when the permissions
  are present.
- Pin third-party actions **and** the ChangeRadar **wrapper**
  (`uses: AnikethTS/ChangeRadar@<sha>`) to immutable commit SHAs. The
  analyzer JAR still defaults to latest unless `version` is set.
- The Action uploads `build/changeradar/` as artifact `changeradar-report`.

Java type resolution: compile **in this workflow**, then pass `--classpath`
(or the Action `classpath` input) at those jars. ChangeRadar does not run
the target Maven/Gradle build.

```yaml
- run: ./gradlew assemble --no-daemon
- uses: AnikethTS/ChangeRadar@5c888d9c6f8a1e2bc08228b56cdff04891f8f8b6
  with:
    base: ${{ github.event.pull_request.base.sha }}
    head: ${{ github.event.pull_request.head.sha }}
    classpath: build/libs
```

Maven: `mvn -B -DskipTests package` and `classpath: target`. Skip compile
and `classpath` when the repo has no JVM build. The Action already runs
`setup-java`.

Optional `suppress-file` (or YAML `suppressions:`) is one finding id per
line. Seed it with `--write-suppressions`. Policies still run on the
unfiltered analysis.

### Pull request comments and check summary

The Action posts `report.compact.md` as a sticky PR comment (counts, policy
result, top DIRECT findings, tests, link to the run). Full Markdown and
JSON stay on the artifact. The compact body is also written to
`$GITHUB_STEP_SUMMARY`.

- Workflow default permission stays `contents: read`. The impact job adds
  `pull-requests: write` and `checks: write` when you want comments and
  notices.
- Comment and annotate steps run only when
  `github.event.pull_request.head.repo.full_name == github.repository`.
  Fork PRs skip them so an untrusted workflow cannot write with a
  privileged token.
- One sticky comment is created and then updated (HTML marker
  `<!-- changeradar-report -->`).
- The author is **github-actions[bot]** (`GITHUB_TOKEN`). GitHub does not
  allow a custom name or avatar for that account. The comment body starts
  with the ChangeRadar logo so the report is still recognizable.
- Comment and annotate use `continue-on-error`. A missing token must not
  be treated as a policy violation (CLI exit `1`).

### Diff annotations (not Code Scanning)

Do **not** upload ChangeRadar SARIF with `github/codeql-action/upload-sarif`.
That turns on GitHub Code Scanning, files impact findings as security
alerts, and GitHub’s advanced-security bot reports “more than 20 potential
problems.” Impact is not a vulnerability scan.

`--format github` writes `report.github.json`. The Action posts that as a
Checks API run (`annotation_level: notice`, DIRECT then INDIRECT, capped at
50) so GitHub can show findings on the Files changed tab without opening
the Security tab.

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
