# Agent usage

ChangeRadar does not call a model. An agent on this machine can read a
capped JSON brief, or start a local stdio process that returns that brief.
The analysis underneath is the same `analyze` command CI already runs.

`HEURISTIC` and `UNKNOWN` are not proof of impact. A missing finding is not
proof that nothing else is affected. Do not skip tests or deploy from the
brief. Exit code `1` means a blocking policy matched. It does not measure
how large the blast radius looks. `changeradar run` can also exit `1` when
the command you supplied exits non-zero. The brief then includes an
`EXECUTION` limitation. That limitation is the command's result, not proof
from the diff. `changeradar mcp` does not run a command.

## Brief file

```bash
java -jar changeradar.jar analyze \
  --base "$BASE_SHA" \
  --head "$HEAD_SHA" \
  --format agent \
  --output build/changeradar
```

That writes `build/changeradar/report.agent.json`. The document version is
`agentSchemaVersion` `1.1.0`. It is separate from report schema `1.0.0`.

The brief includes refs, policy status, counts, and how many rows were cut
(`truncated`). Findings keep higher confidence first, then a stable id, up
to 40. Files are capped at 40, symbols at 20 (public symbols first), and
tests at 15. Each changed file lists up to 8 inclusive HEAD line ranges
(`lines`). A deleted file has an empty `lines` array.

`review` is the order to open files, capped at 20. A row is a failed or
error test from provided JUnit XML, a direct finding, a changed public
symbol, or a test that covers changed lines. Line `0` means the row names
a file rather than a line. One row per path and line; a direct finding
keeps the line when a public symbol sits on it too. Heuristic findings
stay in `findings` and are not a review row. Limitations are never cut.
The full report is `report.json` when that format is also requested.

## Local MCP

```bash
java -jar changeradar.jar mcp
```

The process speaks JSON-RPC on stdin and stdout, one JSON object per line.
That is the stdio transport Claude Code, Cursor, and the official MCP SDK
use. A client that sends `Content-Length` headers is answered in that
framing instead. The server echoes the protocol version the client
requests. It does not open a port and does not upload source. Logs go to
stderr.

Start it with the working directory set to the repository under analysis.
`analyze` then defaults `repo` to `.`.

Tools:

| Tool | Arguments | Result |
| --- | --- | --- |
| `analyze` | `base`, `head`, optional `repo`, `policies`, `policyFile`, `baseline`, `suppressFile`, `classpath`, `coverage`, `junit`, `fullIndex`, `output` | Runs analyze with `--format agent` and returns the brief. Pass JUnit XML, coverage, and classpath when the build already wrote them. The server does not run the build or tests, and it does not write a baseline or suppressions file. Policy exit `1` is `exitCode` inside the brief. A failed run is a tool error. |
| `brief` | `report` (path to an existing `report.json`) | Projects that file. Does not run Git. |

There is no source-reading tool. The agent reads files with its own tools.

### Claude Code

From the repository you want analyzed, with `changeradar.jar` on disk:

```bash
claude mcp add --transport stdio --scope project changeradar -- \
  java -jar /absolute/path/changeradar.jar mcp
```

The same entry belongs in `.mcp.json` at the repository root. Cursor uses
the same document at `.cursor/mcp.json`. A copy with a placeholder path is
[`examples/mcp.json`](../examples/mcp.json).

```json
{
  "mcpServers": {
    "changeradar": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/absolute/path/changeradar.jar", "mcp"]
    }
  }
}
```

Restart the client after saving the file. Ask it to analyze `base` against
`head`. The server instructions tell the client that heuristic findings are
not proof and that the brief must not skip tests.
