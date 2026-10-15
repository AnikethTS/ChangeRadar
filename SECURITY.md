# Security Policy

ChangeRadar reads local Git history and source to produce an impact report. It
is a static-analysis CLI, not a deployment system. If you find a security
issue — path traversal when reading a repository, secret leakage in reports
or CI logs, unsafe handling of untrusted pull-request content, or anything
that would let analysis execute untrusted build scripts — please report it
privately rather than opening a public issue.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repo:

1. Go to the [Security tab](https://github.com/AnikethTS/ChangeRadar/security).
2. Click **Report a vulnerability**.
3. Include what you found, how to reproduce it, and the impact you'd
   expect (what an attacker could actually do with it).

This opens a private advisory visible only to you and the maintainer —
nothing is public until a fix is out and you're both ready to disclose it.

## What's in scope

- Reading repository files and Git objects from a path the user (or CI)
  supplied.
- Report content written to stdout, Markdown, JSON, or CI logs — especially
  secrets, tokens, or large source fragments.
- GitHub Actions examples: permissions, forked pull requests, and pinned
  third-party actions.
- Anything that would cause ChangeRadar to execute the analyzed project's
  build, tests, or other untrusted code as part of analysis.

## What's out of scope

- Issues that require write access to the machine ChangeRadar already runs
  on — local/CI execution is the trust boundary for the CLI itself.
- Findings that a change *might* be risky. ChangeRadar reports evidence; it
  does not claim a change will break production.
- Denial of service against your own analysis job (pathological repos may
  be slow; that is an analyzer-quality issue unless it is attacker-controlled
  in a shared CI context — report those).

## Response

This is a small, actively-maintained open source project with one
maintainer — there's no formal SLA, but reports are read promptly and a fix
or mitigation is prioritized once confirmed.
