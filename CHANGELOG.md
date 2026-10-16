# Changelog

All notable changes to this project are documented here. Numbered releases
follow [Keep a Changelog](https://keepachangelog.com/) once the first tag
exists.

## [Unreleased]

### Fixed

- CI `check` jobs: ktlint on `changeradar-git/build.gradle.kts`, Gradle
  Wrapper 9.1.0, and google-java-format 1.28.0 so Spotless runs on JDK 25.

### Added

- Optional GitHub Actions job that posts or updates one pull-request
  comment with `report.md` on same-repo PRs (skipped on forks).
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
