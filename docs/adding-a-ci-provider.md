# Adding a CI provider

GitHub Actions is the first provider. GitLab CI, Jenkins, and generic
shell/runner examples come after the CLI contract is stable.

A provider integration is:

1. A documented snippet that checks out enough Git history.
2. A call to `java -jar changeradar.jar analyze --base … --head …` with
   explicit SHAs (do not clone ChangeRadar and run Gradle in consumer CI).
3. Artifact upload of compact Markdown, full Markdown, JSON, and optional SARIF.
4. Optional compact comment / `$GITHUB_STEP_SUMMARY` / Checks API
   annotations **using extra permissions the user opts into.** Do not
   upload ChangeRadar SARIF to GitHub Code Scanning.
5. Preservation of exit codes `0` / `1` / `2`.
6. Pin the wrapper (Action, orb, plugin) to an immutable SHA or digest.

It is not a second impact engine.

## Checklist

- [ ] Base/head refs are explicit; merge-base behavior is written down.
- [ ] Shallow clones that lack the base commit fail with exit `2`.
- [ ] Least-privilege defaults (`contents: read` or equivalent).
- [ ] Forked / untrusted events cannot write comments with a privileged token.
- [ ] Third-party actions/plugins pinned to immutable versions where the
      platform allows it.
- [ ] No automatic deploy or rollback from ChangeRadar output.
- [ ] Java: the **workflow** compiles; `--classpath` points at those jars.
      The CLI never invokes the target build.

Put examples under `examples/<provider>/` and link them from
[CI integration](ci-integration.md).
