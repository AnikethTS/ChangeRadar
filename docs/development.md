# Development

## Prerequisites

- JDK 21 or newer
- Git
- No system Gradle install required (Wrapper is committed)

```bash
./gradlew check
./run.sh --help
./run.sh analyze --base HEAD --head HEAD
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
changeradar-analyzer-java/    Java language adapter
changeradar-cli/              picocli, reporters
docs/                         architecture and CI docs
examples/github-actions/      copy-paste workflow
fixtures/                     later: tiny demo repos + expected reports
schemas/                      JSON report schema
.github/                      CI, issue and PR templates
```

## CI

GitHub Actions runs `./gradlew check` on Ubuntu and macOS, plus a CLI
smoke job. Third-party actions in this repo's workflows are pinned to
commit SHAs.
