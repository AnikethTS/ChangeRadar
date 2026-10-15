# Fixtures

Tiny repositories used as expected-input / expected-report pairs.

| Directory | Intent |
| --- | --- |
| `simple-call-chain/` | Direct Java caller of a changed method (Phase 2–3). |
| `public-api-change/` | Public signature break (Phase 5–8). |
| `related-tests/` | Test class linked to production code (Phase 4). |
| `unresolved-symbols/` | Calls the analyzer cannot resolve (limitations). |

These directories start as placeholders so later milestones have a home.
Do not commit nested `.git` directories; tests build temporary Git repos
until a fixture-packaging script exists.
