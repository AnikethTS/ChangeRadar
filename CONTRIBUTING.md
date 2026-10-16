# Contributing to ChangeRadar

Thanks for taking the time to contribute. This document covers how to get set
up, what a good PR looks like here, and — since it comes up for every project
now — the project's policy on AI-assisted contributions.

## Getting started

1. Fork the repo and clone your fork.
2. Install **JDK 21** or newer (the build uses a Java 21 toolchain). Gradle
   Wrapper is committed; you do not need a system Gradle install.
3. Confirm your setup:

   ```bash
   ./gradlew check
   ```

   That runs unit tests and Spotless (google-java-format). All of it should
   pass on a clean checkout.

Optional: point Git at `.githooks` so `check` runs before each commit:

```bash
git config core.hooksPath .githooks
```

## Making a change

- Create a branch off `main`: `git checkout -b your-change-name`.
- Keep PRs focused. One logical change per PR is much easier to review than
  a bundle of unrelated fixes — see [Scope](#scope) below.
- Add or update tests next to the code you change. Git behavior goes in
  `changeradar-git`, analyzers in `changeradar-analyzer-java`, CLI/exit
  codes in `changeradar-cli`, shared model in `changeradar-core`.
- Prefer fixture repositories under `fixtures/` (or `@TempDir` Git repos in
  tests) over mocking JGit unless the mock is the thing under test.
- Run `./gradlew check` locally before opening a PR. CI runs the same
  checks on Linux, macOS, and Windows (JDK 21 and 25).
- Write your commit messages and PR description around *why*, not just
  *what* — the diff already shows what changed.

## Scope

Some parts of this codebase are load-bearing for honesty, not just
functionality: the [exit-code contract](docs/ci-integration.md#exit-codes),
report schema, Git adapter, and anything that could execute repository
build scripts. Changes there get more scrutiny, and are a rockier place to
land a first PR. If you're new here, look for issues labeled
[`up-for-grabs`](https://github.com/AnikethTS/ChangeRadar/issues?q=is%3Aissue+is%3Aopen+label%3Aup-for-grabs)
— they're picked specifically to be self-contained and not on that critical
path. If an issue isn't labeled and you're not sure whether it's a good
first task, ask on the issue before starting.

Don't add dependencies, language analyzers, CI vendors, or config options
beyond what the issue actually calls for. The core graph, policy engine,
and report model stay independent of JavaParser and of any CI provider —
that is a design decision, not an oversight.

## Pull requests

- Reference the issue you're addressing, if there is one.
- Add a bullet under `## [Unreleased]` in `CHANGELOG.md` describing your
  change, in the same voice as the existing entries. This is part of the
  PR, not something that gets backfilled after merge.
- Fill in the test plan: what you ran, not just that CI is green. For
  analyzer work, say which fixture (or real repo) you used as `--base` /
  `--head`.
- Be responsive to review. If a change sits with unaddressed feedback for a
  while, it may get closed to keep the queue honest; you're welcome to
  reopen when you're ready to continue.

## AI-assisted contributions

Using AI tools (Copilot, ChatGPT, Claude, Cursor, etc.) to help write a
contribution is fine. The bar is the same either way: **you are the author,
and you're accountable for what you submit.** Specifically:

- **Disclose it.** Note in the PR description which tool(s) you used and
  roughly how — e.g. "drafted with Cursor, I wrote the tests by hand"
  or "used Copilot for autocomplete throughout." This isn't about gatekeeping
  AI use, it's context that helps review.
- **Understand every line before you submit it.** If a reviewer asks why the
  code does something a particular way, "the AI wrote it that way" isn't an
  answer. Read the diff as if you'd typed it yourself, because as far as
  this project is concerned, you did.
- **Actually run it.** CI passing is necessary, not sufficient. Don't submit
  generated code you haven't executed and checked against the behavior
  described in the issue.
- **No bulk or speculative PRs.** Mass-generating PRs against open issues
  without engaging with the specific problem wastes maintainer time and
  will be closed without review, repeatedly if needed.
- **Keep it in scope.** AI tools are especially prone to "helpfully"
  expanding a change — extra abstractions, unrelated refactors, new
  dependencies. See [Scope](#scope) above; out-of-scope changes get sent
  back regardless of how they were written.

## Code of conduct

Be respectful, assume good faith, and keep disagreements about the code, not
the person. Maintainers may close issues or PRs, or ask a contributor to
step back from a thread, if this isn't holding.

## License

By contributing, you agree your contribution is licensed under this
project's [MIT license](LICENSE).
