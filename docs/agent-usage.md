# Agent usage

ChangeRadar does not call a model. An agent on this machine can read a
capped JSON brief, or start a local stdio process that returns that brief.
The analysis underneath is the same `analyze` command CI already runs.

`HEURISTIC` and `UNKNOWN` are not proof of impact. A missing finding is not
proof that nothing else is affected. Do not skip tests or deploy from the
brief. Exit code `1` means a blocking policy matched. It does not measure
how large the blast radius looks.

## Brief file

```bash
java -jar changeradar.jar analyze \
  --base "$BASE_SHA" \
  --head "$HEAD_SHA" \
  --format agent \
  --output build/changeradar
```

That writes `build/changeradar/report.agent.json`. The document version is
`agentSchemaVersion` `1.0.0`. It is separate from report schema `1.0.0`.

The brief includes refs, policy status, counts, and how many rows were cut
(`truncated`). Findings keep higher confidence first, then a stable id, up
to 40. Files are capped at 40, symbols at 20 (public symbols first), and
tests at 15. Limitations are never cut. The full report is `report.json`
when that format is also requested.

## Local MCP

```bash
java -jar changeradar.jar mcp
```

The process speaks JSON-RPC on stdin and stdout with `Content-Length`
framing (protocol `2024-11-05`). It does not open a port and does not
upload source. Logs go to stderr.

Tools:

| Tool | Arguments | Result |
| --- | --- | --- |
| `analyze` | `base`, `head`, optional `repo`, `policyFile`, `output` | Runs analyze with `--format agent` and returns the brief. Policy exit `1` is `exitCode` inside the brief. A failed run is a tool error. |
| `brief` | `report` (path to an existing `report.json`) | Projects that file. Does not run Git. |

There is no source-reading tool. The agent reads files with its own tools.

A client config points the command at `java` with `-jar` and the path to
`changeradar.jar`, then `mcp`. The working directory should be the
repository under analysis, or each `analyze` call should pass `repo`.
