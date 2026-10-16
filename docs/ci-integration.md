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

See [`examples/github-actions/impact.yml`](../examples/github-actions/impact.yml)
(this repository also runs [`.github/workflows/impact.yml`](../.github/workflows/impact.yml)).

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

### Pull request comments

The optional `comment` job in the example workflow posts `report.md` on the
pull request conversation. It is not part of the required `CI` gate and
does not change analyze exit codes (`0` / `1` / `2`).

- Workflow default permission stays `contents: read`. Only the comment job
  sets `pull-requests: write` (plus `actions: read` to download the
  artifact).
- The job runs only when
  `github.event.pull_request.head.repo.full_name == github.repository`.
  Fork PRs skip it so an untrusted workflow cannot write comments with a
  privileged token.
- One sticky comment is created and then updated (HTML marker
  `<!-- changeradar-report -->`). Bodies over GitHub's limit are truncated
  with a link to the workflow run / artifact.
- A failed comment must not be treated as a policy violation. Do not map
  comment-job failure to CLI exit `1`.

### Commenting as ChangeRadar[bot] (with the project logo)

`secrets.GITHUB_TOKEN` always posts as **github-actions[bot]**. GitHub will
not let you rename that account or give it another avatar. The identity
people see on a comment is the GitHub App that minted the token.

Create one GitHub App named **ChangeRadar**. Comments then show as
**ChangeRadar[bot]** with whatever image you set as the app logo (use
[`.github/logo.png`](../.github/logo.png)).

1. Open [Register a new GitHub App](https://github.com/settings/apps/new)
   while signed in as the account that owns this repository.
2. **GitHub App name:** `ChangeRadar` (must be unique on GitHub; if it is
   taken, use something like `ChangeRadar-impact` — the comment author
   becomes that name plus `[bot]`).
3. **Homepage URL:** `https://github.com/AnikethTS/ChangeRadar`. Uncheck
   **Webhook → Active**.
4. Repository permissions: **Issues** Read and write (PR conversation
   comments use the issues API), **Pull requests** Read and write,
   **Metadata** Read. Nothing else.
5. Create the app. On the app settings page, click the avatar, upload
   `.github/logo.png`, then **Generate a private key**.
6. **Install App** → only this repository (and later any consumer such as
   RunnerDeck).
7. On the repository: Actions **variable** `CHANGERADAR_APP_CLIENT_ID`
   (the app **Client ID**, starts with `Iv1.`) and Actions **secret**
   `CHANGERADAR_APP_PRIVATE_KEY` (full PEM, including
   `BEGIN/END RSA PRIVATE KEY` lines). Fork PRs never receive that secret.

Until those two values exist, the job still comments as github-actions[bot].
A GitHub App cannot edit a comment it did not author, so hide or delete the
old github-actions comment after you switch.

GitLab CI, Jenkins, and generic shell examples wait until this contract is
stable and tested.

## JSON schema

Reports include `schemaVersion` (`1.0.0` today). Fields will grow
(`changedSymbols`, `recommendedTests`, `policyViolations`) without reusing
names for different meanings. See
[`schemas/report-v1.schema.json`](../schemas/report-v1.schema.json).
