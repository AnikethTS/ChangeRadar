# Development

## Prerequisites

- JDK 21 or newer (CI runs `./gradlew check` on JDK 21 and 25)
- Git
- No system Gradle install required (Wrapper is committed)

```bash
./gradlew check
./gradlew :changeradar-cli:shadowJar
./run.sh --help
./run.sh analyze --base HEAD --head HEAD
./run.sh analyze --base HEAD~1 --head HEAD --format html --output build/changeradar
./run.sh review --output build/changeradar
java -jar changeradar-cli/build/libs/changeradar.jar analyze --base HEAD --head HEAD
```

`review` serves `report.html` on `127.0.0.1`. With `--base` and `--head` it
runs analyze first. Ctrl+C stops the server.

## Tests and formatting

| Command | What it does |
| --- | --- |
| `./gradlew test` | JUnit 5 across all modules |
| `./gradlew spotlessCheck` | google-java-format + ktlint on Gradle Kotlin DSL |
| `./gradlew spotlessApply` | Rewrite formatting |
| `./gradlew check` | tests + Spotless |

Install the repo hooks so `check` runs on commit:

```bash
git config core.hooksPath .githooks
```

## Directory layout

```text
changeradar-core/             shared model, exit codes, analyzer API
changeradar-git/              JGit adapter
changeradar-analyzer-java/    Java adapter (parse, callers, recommendations)
changeradar-analyzer-kotlin/     Kotlin adapter (same split)
changeradar-analyzer-typescript/ TypeScript/JavaScript adapter (same split)
changeradar-analyzer-python/     Python adapter (same split)
changeradar-analyzer-php/        PHP adapter (same split)
changeradar-analyzer-sql/        SQL adapter (same split)
changeradar-analyzer-workflow/  GitHub Actions workflow adapter (same split)
changeradar-analyzer-shell/     Shell adapter (same split)
changeradar-cli/              picocli, reporters
docs/                         architecture and CI docs
site/                         project site published to GitHub Pages
examples/github-actions/      consumer workflow templates to copy
action.yml                    composite Action: java -jar changeradar.jar
fixtures/                     later: tiny demo repos + expected reports
schemas/                      JSON report schema
.github/                      CI, issue and PR templates
```

## CI

Workflows live in [`.github/workflows`](../.github/workflows). Third-party
actions are pinned to commit SHAs. Checkouts set `persist-credentials:
false` except the job that pushes the release tag. Dependabot waits
seven days before opening an update.

### Pull requests

Every pull request runs the fast plan:

| Job | What it checks |
| --- | --- |
| actionlint | Workflow YAML, `action.yml`, and the Action shell scripts (shellcheck) |
| Gradle Wrapper | The committed wrapper checksum |
| Security · Secret scan | gitleaks over the full history |
| Check · Ubuntu latest · Java 21 | `./gradlew check` |
| CLI smoke · Ubuntu latest · Java 21 | `java -jar changeradar.jar`, then `report.json` against `schemas/report-v1.schema.json` |

[Semgrep](../.github/workflows/semgrep.yml) (`p/security-audit` and
`p/java`), [zizmor](../.github/workflows/zizmor.yml), CodeQL, and
Dependency Review run on the same pull request, in their own workflows.
The Impact workflow builds a local JAR (so this repo does not download
the published release) and passes JaCoCo XML and JUnit XML into the Action.

The `CI` job stays red until the pull request has the **`ci:full`**
label. Add that label when the change is ready for review. Removing it
cancels nothing that is already running; the next run uses the fast plan
again.

### Full matrix

`ci:full`, a push to `main`, the nightly schedule (`17 3 * * *`), and
**Run workflow** on the CI workflow all run:

| Runner | JDK |
| --- | --- |
| `ubuntu-latest`, `macos-latest`, `windows-latest` | 21 and 25 |
| `ubuntu-24.04`, `ubuntu-22.04`, `windows-2022`, `macos-15` | 21 |

GitHub does not host Windows 10. `windows-2022` covers that NT 10.0
family. Jobs use `bash` on every OS. On a full run the Ubuntu JDK 21
fast jobs are skipped, because that cell is already in the matrix. The
stable check names `Check` and `CLI smoke` stay green only when every
cell in the current plan succeeded.

Protect `main` requires `CI`, `Check`, `CLI smoke`, `actionlint`,
`Gradle Wrapper`, CodeQL `Analyze (java)`, Dependency Review,
`Impact report`, `Security · Secret scan`, `Security · Semgrep`, and
`Security · Workflow audit`.

## Releasing

Ship from protected `main`. Tags are the release record (`v0.2.8`), not
a long-lived `release` branch.

1. Open a PR that sets `VERSION`, folds the Unreleased notes into
   `CHANGELOG.md` (`## [x.y.z] - YYYY-MM-DD`), and updates pin comments.
2. Merge after required checks. Do not push tags by hand.
3. **Publish release** compares `VERSION` to the previous `main` tip. If
   it changed and `vX.Y.Z` does not exist, it creates an annotated tag
   and a GitHub Release from the changelog section, then builds and
   uploads `changeradar.jar`, `changeradar.jar.sha256`, and
   `changeradar.jar.sigstore.json` in the same workflow. `GITHUB_TOKEN` cannot
   start **Release JAR** (`release: published` from another workflow is
   skipped). The tag job uses the `production` environment so reviewers
   or a wait timer can be added later.
4. **Release JAR** uploads the same three files when a human publishes a
   GitHub Release from the UI, or via `workflow_dispatch` with a tag.

Verify a published JAR:

```bash
mkdir -p /tmp/changeradar
gh release download v0.2.8 --pattern 'changeradar.jar*' --dir /tmp/changeradar
cd /tmp/changeradar
sha256sum -c changeradar.jar.sha256
gh attestation verify changeradar.jar -o AnikethTS
```

Feature merges that do not change `VERSION` do not cut a JAR. If a tag
must be created without a `VERSION` bump (recovery), run **Publish
release** with `workflow_dispatch` on `main`. The changelog section for
that version is required.
