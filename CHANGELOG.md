# Changelog

All notable changes to this project are documented here. Numbered releases
follow [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added

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
  path.

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

[Unreleased]: https://github.com/AnikethTS/ChangeRadar/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/AnikethTS/ChangeRadar/releases/tag/v0.1.0
