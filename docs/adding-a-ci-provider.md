# Adding a CI provider

GitHub Actions is the first provider. GitLab CI, Jenkins, and generic
shell/runner examples come after the CLI contract is stable.

A provider integration is:

1. A documented snippet that checks out enough Git history.
2. A call to `changeradar analyze --base … --head …` with explicit SHAs.
3. Artifact upload of Markdown and JSON.
4. Optional comment/summary **using extra permissions the user opts into.**
5. Preservation of exit codes `0` / `1` / `2`.

It is not a second impact engine.

## Checklist

- [ ] Base/head refs are explicit; merge-base behavior is written down.
- [ ] Shallow clones that lack the base commit fail with exit `2`.
- [ ] Least-privilege defaults (`contents: read` or equivalent).
- [ ] Forked / untrusted events cannot write comments with a privileged token.
- [ ] Third-party actions/plugins pinned to immutable versions where the
      platform allows it.
- [ ] No automatic deploy or rollback from ChangeRadar output.

Put examples under `examples/<provider>/` and link them from
[CI integration](ci-integration.md).
