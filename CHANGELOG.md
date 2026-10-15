# Changelog

All notable changes to this project are documented here. Numbered releases
follow [Keep a Changelog](https://keepachangelog.com/) once the first tag
exists.

## [Unreleased]

### Fixed

- CI `check` jobs: ktlint on `changeradar-git/build.gradle.kts`, Gradle
  Wrapper 9.1.0, and google-java-format 1.28.0 so Spotless runs on JDK 25.

### Added

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
