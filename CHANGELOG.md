# Changelog

All notable changes to this project are documented here. Numbered releases
follow [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Changed

- Consumer GitHub Actions example compiles with the project’s own Gradle
  (or Maven) step, then passes `classpath:` of those jars so Java callers
  can resolve as `DIRECT`. This repo’s impact workflow does the same with
  module `build/libs`. ChangeRadar still does not run the target build.

- `VERSION` is `0.2.2-SNAPSHOT` after the `v0.2.1` tag. README status and
  sample report match the current CLI (`0.2.2-SNAPSHOT`, limitations,
  policies). Consumer example pins wrapper SHA `60797d6`.
- Consumer Action defaults the analyzer JAR to GitHub’s latest release
  (`version` omitted or `latest`). Set `version: v0.2.1` to pin a JAR.
  Still pin `uses:` to a wrapper SHA.

### Fixed

- CodeQL `Analyze (java)` forces a real javac (`--no-parallel --no-build-cache
  --rerun-tasks`). Parallel compile under the tracer failed with no output;
  Gradle’s build cache then skipped compile so CodeQL saw no Java.

## [0.2.1] - 2026-10-20

GitHub Actions Marketplace listing (display name **ChangeRadar Impact
Analysis**). Analyzer JAR is the `v0.2.1` release asset.

### Fixed

- Marketplace `name` is `ChangeRadar Impact Analysis`. GitHub rejected
  `ChangeRadar` because it matches an existing user (`changeradar`).
  `uses: AnikethTS/ChangeRadar@…` is unchanged.

## [0.2.0] - 2026-10-20

Distributable CLI (`changeradar.jar`) and a composite GitHub Action.
Consumers pin the Action to a commit SHA and download a tagged JAR; they
do not clone this repo and run Gradle.

### Fixed

- Stop uploading ChangeRadar SARIF to GitHub Code Scanning. That setup
  banner and “more than 20 potential problems” were impact findings filed
  as security alerts. The workflow now posts Checks API `notice`
  annotations (`--format github`) on the diff instead. SARIF stays an
  artifact and uses `note`, not `warning`.

### Added

- Distributable `changeradar.jar` (`./gradlew :changeradar-cli:shadowJar`) and a
  composite GitHub Action that downloads a tagged release (or a local jar)
  and runs `java -jar … analyze`. Pin the Action to a commit SHA. A
  `Release JAR` workflow uploads the fat JAR onto published GitHub Releases.
- Compact Markdown (`--format compact` → `report.compact.md`), GitHub Actions
  job summary, and SARIF 2.1 (`report.sarif`) so PR comments stay short and
  findings land on the diff. Findings are grouped by file and carry a stable
  `id` (`type|symbolId|path:line`).
- `changeradar policies` (`list-policies`) lists named rules. `--policy-file`
  YAML plus `--baseline` / `--write-baseline` so teams can enable rules
  without failing on pre-existing public API. DELETE+ADD pairs become
  `RENAME` (new path) or signature `MODIFY` (same path).
- Optional Java `--classpath` of already-built jars (does not run the target
  build). Parsers default to changed files plus importers (and same-directory
  Java neighbors); `--full-index` parses every HEAD source file.

- TypeScript, Python, and PHP structural edges: overrides (`OVERRIDE`),
  field/property reads (`FIELD_ACCESS`), decorator/attribute uses
  (`ANNOTATION_USE`), and typed fields (`FIELD_TYPE` / `INJECTION`).
  Matched through imports against HEAD files, not tsc/CPython/php-parser.
- Java structural edges: overrides/implementations (`OVERRIDE`), reads of
  changed fields (`FIELD_ACCESS`), uses of a changed annotation type
  (`ANNOTATION_USE`), and inject-style fields (`INJECTION` /
  `FIELD_TYPE`). Method-only edits do not flag every field of that type.
- Java call chains: type-resolved callers of callers are reported as
  `INDIRECT` (max depth 3, max 40 indirect sites). Heuristic name matches
  are not chained.
- TypeScript, Python, and PHP callers use import/require/`use` specifiers
  against HEAD files (`DIRECT` when the binding maps to the changed
  symbol). Same-named functions in files that never imported the change
  are no longer reported.
- Java callers are type-resolved with JavaParser's symbol solver against
  HEAD sources and the JRE (`DIRECT`). Unresolved sites still fall back to
  name matching (`HEURISTIC`), but unscoped calls (`parseBase()`) only
  match the enclosing type so other classes with the same method name are
  not reported. Compiled Maven/Gradle dependencies are not on the type
  path unless `--classpath` of already-built jars is passed.

## [0.1.0] - 2026-10-17

First tagged release. The CLI compares two Git refs, maps changed lines
to symbols, lists likely callers and related tests, and writes terminal,
Markdown, and JSON reports. Analysis is name-based, not a full compiler
graph.

### Added

- Optional GitHub Actions job that posts or updates one pull-request
  comment with `report.md` on same-repo PRs (skipped on forks). The
  author is github-actions[bot]; the ChangeRadar logo is in the comment
  body.
- PHP impact analysis (classes, functions, name-based callers, related
  tests) and `--policy breaking-public-php`.
- Python impact analysis (classes, functions, name-based callers, related
  tests) and `--policy breaking-public-python`.
- TypeScript/JavaScript impact analysis (classes, functions, exports,
  name-based callers, related tests) and `--policy breaking-public-export`.
- Optional blocking `--policy` rules (`breaking-public-java-api`,
  `analysis-unavailable`), off by default, with exit code `1` when they
  fire. Removed public Java members are reported as `DELETE` symbols.
- GitHub Actions CI on Ubuntu, macOS, and Windows for JDK 21 and 25, with
  Wrapper validation, CLI smoke on every cell, test-report artifacts on
  failure, and a required `CI` gate job.
- Java impact analysis: changed symbols, name-based callers, related tests,
  and public API line occupancy in the terminal/Markdown/JSON reports.
- Project logo (`.github/logo.png`) on the README.
- Gradle multi-module foundation (`changeradar-core`, `changeradar-git`,
  `changeradar-analyzer-java`, `changeradar-cli`) on Java 21.
- `changeradar analyze --base <ref> --head <ref>` CLI with terminal,
  Markdown, and JSON reports, documented exit codes (`0` / `1` / `2`), and
  Git diff of changed files via JGit.
- GitHub Actions CI, issue/PR templates, and contributor docs modeled on
  the project's open-source conventions.

### Fixed

- CI `check` jobs: ktlint on `changeradar-git/build.gradle.kts`, Gradle
  Wrapper 9.1.0, and google-java-format 1.28.0 so Spotless runs on JDK 25.

[Unreleased]: https://github.com/AnikethTS/ChangeRadar/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/AnikethTS/ChangeRadar/releases/tag/v0.2.1
[0.2.0]: https://github.com/AnikethTS/ChangeRadar/releases/tag/v0.2.0
[0.1.0]: https://github.com/AnikethTS/ChangeRadar/releases/tag/v0.1.0
