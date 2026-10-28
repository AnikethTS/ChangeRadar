# Agents working in this repository

Analysis stays deterministic. Do not put a model on the analyze path, and
do not add a service that uploads source.

Consumers read the impact of a diff through the agent brief. See
[docs/agent-usage.md](docs/agent-usage.md). `HEURISTIC` and `UNKNOWN` are
not proof, and a brief must not be used to skip tests or deploy.
