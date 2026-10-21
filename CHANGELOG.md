# Changelog

All notable changes to this project are documented here. Numbered releases
follow [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added

- `breaking-public-java-api` treats return-type, declared `throws`, field
  type, overload removal, and public-to-non-public as breaking (still not
  japicmp). Parameter-list edits and true removals were already covered.
- This repository's Impact workflow runs tests, copies JaCoCo XML and
  JUnit XML into `build/test-evidence/`, and passes them to the Action.

### Changed

- Consumer examples pin the Action wrapper to a SHA that includes
  `coverage` / `junit` inputs.

### Fixed

- Publish release uploads `changeradar.jar` in the same workflow. A
  `GITHUB_TOKEN` `gh release create` does not start Release JAR, so
  `releases/latest/download/changeradar.jar` 404ed on v0.2.4 until the
  asset was attached.

## [0.2.4] - 2026-10-21

Analyzer JAR is the `v0.2.4` release asset. Omit `version` for latest;
set `version: v0.2.4` to pin this JAR.

### Added

- Optional `--coverage` (JaCoCo XML or LCOV) intersects suite coverage with
  changed lines. Optional `--junit` annotates recommended tests with
  pass/fail from XML the consumer already produced. ChangeRadar still does
  not run the target build or tests.
- Push to protected `main` that changes `VERSION` publishes GitHub
  Release `v` plus that version (annotated tag, changelog notes) when
  the tag is new. `Release JAR` still uploads `changeradar.jar`. Manual
  retry is `workflow_dispatch` on Publish release.

### Changed

- Language analyzers are split into parse, callers, recommendations, and
  a small facade. Behavior is unchanged; no file is 800+ lines.
- Report writers are split by format (text, JSON, SARIF, GitHub). CLI and
  Java analyzer tests are split by scenario, with shared Git fixtures.

## [0.2.3] - 2026-10-21

Analyzer JAR is the `v0.2.3` release asset. Omit `version` for latest;
set `version: v0.2.3` to pin this JAR.

### Added

- TypeScript/JavaScript callers through `export * from` and named
  `export { … } from` barrels are DIRECT when the specifier chain lands
  on the changed HEAD module (cycles are ignored; not tsc).
- TypeScript/JavaScript `compilerOptions.paths` / `baseUrl` from the
  nearest HEAD `tsconfig.json` or `jsconfig.json` resolve import aliases
  (JSONC allowed; `extends` and multi-star mappings are ignored).
- Java resolved callers and call chains match a specific overload
  signature (`n(int)` vs `n(String)`), not every method of the same name.
- Python callers of callers walk import-resolved function edges (depth 1
  DIRECT, deeper INDIRECT, max depth 3). Heuristic name matches are not
  chained.
- PHP callers of callers walk use/require-resolved function edges (depth
  1 DIRECT, deeper INDIRECT, max depth 3). Heuristic name matches are not
  chained.
- CI `check` and CLI smoke also run on the previous GitHub-hosted images
  (`ubuntu-24.04`, `ubuntu-22.04`, `windows-2022`, `macos-15`) with JDK 21.
  GitHub does not offer a Windows 10 runner; `windows-2022` is the prior
  Windows Server image (NT 10.0).
- CLI smoke analyzes with `java -jar changeradar.jar` (the consumer path)
  and validates `report.json` against `schemas/report-v1.schema.json`.
  actionlint covers `action.yml`; shellcheck covers the Action scripts.

### Changed

- PR comments use a cropped 280×97 lockup (radar + name) instead of the
  full-bleed README banner or a 36px icon, plus GitHub alert callouts for
  policy and a one-row counts table.

## [0.2.2] - 2026-10-20

Analyzer JAR is the `v0.2.2` release asset. Consumer CI is checkout plus
one Action step; copy
[`examples/github-actions/changeradar.yml`](examples/github-actions/changeradar.yml).

### Added

- Finding-id suppressions: `--suppress-file` / YAML `suppressions:` (one
  `type|symbolId|path:line` per line) omit those findings from compact,
  Markdown, JSON, SARIF, and GitHub reports. `--write-suppressions` seeds
  the file. Policies still evaluate the unfiltered analysis.
- Copy-paste GitHub Actions templates:
  `examples/github-actions/changeradar.yml` (any repo) and
  `changeradar-java.yml` (compile then `classpath`). Destination in the
  consumer repo is `.github/workflows/changeradar.yml`.

### Changed

- Artifact upload, sticky PR comment, and Checks notices run inside the
  Action (skipped on fork PRs). Compile then `classpath:` remains optional
  for Java DIRECT callers; ChangeRadar still does not run the target build.
- Consumer Action defaults the analyzer JAR to GitHub’s latest release
  (`version` omitted or `latest`). Set `version: v0.2.2` to pin a JAR.
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

[Unreleased]: https://github.com/AnikethTS/ChangeRadar/compare/v0.2.4...HEAD
[0.2.4]: https://github.com/AnikethTS/ChangeRadar/releases/tag/v0.2.4
[0.2.3]: https://github.com/AnikethTS/ChangeRadar/releases/tag/v0.2.3
[0.2.2]: https://github.com/AnikethTS/ChangeRadar/releases/tag/v0.2.2
[0.2.1]: https://github.com/AnikethTS/ChangeRadar/releases/tag/v0.2.1
[0.2.0]: https://github.com/AnikethTS/ChangeRadar/releases/tag/v0.2.0
[0.1.0]: https://github.com/AnikethTS/ChangeRadar/releases/tag/v0.1.0
