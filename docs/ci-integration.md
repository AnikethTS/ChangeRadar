# CI integration

ChangeRadar is a CLI. CI jobs check out enough Git history, pass explicit
base and head SHAs, and publish the files it writes. Integrations must not
duplicate analysis logic.

## Command

```bash
changeradar analyze \
  --base "$BASE_SHA" \
  --head "$HEAD_SHA" \
  --format markdown,json \
  --output build/changeradar
```

`--repo` defaults to the current directory. `--format` is a comma-separated
list of `terminal`, `markdown`, and `json`. Markdown and JSON are written
under `--output` as `report.md` and `report.json`. If `--output` is omitted
and a file format is requested, the default directory is
`build/changeradar`.

## Exit codes

| Code | Enum | Meaning |
| --- | --- | --- |
| `0` | `SUCCESS` | Analysis completed; no enabled blocking policy was violated. |
| `1` | `POLICY_VIOLATION` | A configured blocking policy was violated (`--policy`). |
| `2` | `FAILURE` | Invalid arguments, unreadable repository, unresolved Git ref, unsupported `--format`, or an analyzer/tool error. |

CI must preserve this distinction. Do not treat “the report looks scary” as
exit `1` unless a named policy said so. Do not treat exit `2` as “the change
is risky.”

Fail-open vs fail-closed (whether exit `2` should fail the GitHub job) is a
*workflow* decision, documented in [policy configuration](policy-configuration.md).
The CLI itself still returns `2` for tool failure.

## GitHub Actions (initial provider)

This repository's own [CI workflow](../.github/workflows/ci.yml) runs
`./gradlew check` and a CLI smoke job on Ubuntu, macOS, and Windows (JDK 21
and 25). Jobs use `bash` on every OS so `./gradlew` and `./run.sh` behave
the same. A `CI` gate job fails unless every required job succeeded.

See [`examples/github-actions/impact.yml`](../examples/github-actions/impact.yml).

Requirements:

- Checkout with enough history that `$BASE_SHA` exists. `fetch-depth: 0` is
  the simple, correct default. Shallow clones that omit the base commit
  must fail with exit `2`, not a silent empty report.
- Pass `github.event.pull_request.base.sha` and
  `github.event.pull_request.head.sha` (or `github.sha` on push).
- `permissions: contents: read` is enough to analyze. Writing a pull-request
  comment needs `pull-requests: write` and must be a separate, explicit
  choice. Forked PRs from untrusted repos should not run privileged comment
  jobs.
- Pin third-party actions to immutable commit SHAs in security-conscious
  examples.
- Upload `build/changeradar/` as an artifact.

GitLab CI, Jenkins, and generic shell examples wait until this contract is
stable and tested.

## JSON schema

Reports include `schemaVersion` (`1.0.0` today). Fields will grow
(`changedSymbols`, `recommendedTests`, `policyViolations`) without reusing
names for different meanings. See
[`schemas/report-v1.schema.json`](../schemas/report-v1.schema.json).
