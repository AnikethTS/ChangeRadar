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
java -jar changeradar-cli/build/libs/changeradar.jar analyze --base HEAD --head HEAD
```

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
changeradar-analyzer-typescript/ TypeScript/JavaScript adapter (same split)
changeradar-analyzer-python/     Python adapter (same split)
changeradar-analyzer-php/        PHP adapter (same split)
changeradar-cli/              picocli, reporters
docs/                         architecture and CI docs
examples/github-actions/      consumer workflow templates to copy
action.yml                    composite Action: java -jar changeradar.jar
fixtures/                     later: tiny demo repos + expected reports
schemas/                      JSON report schema
.github/                      CI, issue and PR templates
```

## CI

GitHub Actions runs `./gradlew check` and a CLI smoke job on current
`ubuntu-latest`, `macos-latest`, and `windows-latest` (JDK 21 and 25),
plus previous hosted images `ubuntu-24.04`, `ubuntu-22.04`,
`windows-2022`, and `macos-15` (JDK 21). GitHub does not host Windows 10;
`windows-2022` covers that NT 10.0 family. The Gradle Wrapper is
validated, workflows and `action.yml` are linted with actionlint, Action
scripts are linted with shellcheck, CLI smoke runs `java -jar
changeradar.jar` and checks `report.json` against
`schemas/report-v1.schema.json`, and a `CI` gate job fails unless every
required job succeeded. Third-party actions in this repo's workflows are
pinned to commit SHAs.

## Releasing

Ship from protected `main`. Tags are the release record (`v0.2.4`), not
a long-lived `release` branch.

1. Open a PR that sets `VERSION`, folds the Unreleased notes into
   `CHANGELOG.md` (`## [x.y.z] - YYYY-MM-DD`), and updates pin comments.
2. Merge after required checks. Do not push tags by hand.
3. **Publish release** compares `VERSION` to the previous `main` tip. If
   it changed and `vX.Y.Z` does not exist, it creates an annotated tag
   and a GitHub Release from the changelog section. The job uses the
   `production` environment so reviewers or a wait timer can be added
   later.
4. **Release JAR** builds `changeradar.jar` at that tag and uploads it.

Feature merges that do not change `VERSION` do not cut a JAR. If a tag
must be created without a `VERSION` bump (recovery), run **Publish
release** with `workflow_dispatch` on `main`. The changelog section for
that version is required.
