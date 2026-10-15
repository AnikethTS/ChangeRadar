# Policy configuration

Policies are optional and must stay independent of analysis. A finding is
evidence; a policy is a team decision about whether that evidence should
fail the job.

**Status:** not implemented. The CLI always returns `0` after a successful
analysis and `2` on tool/input failure. Exit `1` is reserved.

## Principles (locked in before the engine exists)

- Nothing blocks a merge solely because a heuristic score is “high.”
- Only an *explicitly enabled* rule can produce exit `1`.
- Analyzer/tool failure is always exit `2`, never `1`.
- Default is fail-open for uncertain findings: report them, do not fail.
- Fail-closed for *tool* failure is a CI-workflow choice (for example
  `set -e` vs `|| true`), not a hidden CLI default.

## Likely first rules

These will be named, documented, and off by default:

- `breaking-public-java-api` — a public Java signature was removed or
  changed incompatibly, with direct evidence.
- `analysis-unavailable` — required analyzer output is missing (distinct
  from “we found risk”).

Repository-specific policy packs come after those two are tested.
