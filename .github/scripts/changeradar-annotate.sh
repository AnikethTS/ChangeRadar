#!/usr/bin/env bash
set -euo pipefail
# Posts Checks API notice annotations from report.github.json. Skips forks
# and missing reports so analysis can succeed without checks: write.
OUTPUT="${1:?output directory}"
HEAD_SHA="${2:?head sha}"
REPORT="${OUTPUT}/report.github.json"
if [ ! -s "${REPORT}" ]; then
  echo "changeradar: no report.github.json; skip annotations"
  exit 0
fi
python3 - "${HEAD_SHA}" "${REPORT}" <<'PY'
import json
import pathlib
import sys

head, report = sys.argv[1], pathlib.Path(sys.argv[2])
payload = json.loads(report.read_text())
body = {
    "name": "ChangeRadar annotations",
    "head_sha": head,
    "status": "completed",
    "conclusion": "success",
    "output": {
        "title": payload.get("title") or "ChangeRadar impact",
        "summary": (payload.get("summary") or "")[:65000],
        "annotations": (payload.get("annotations") or [])[:50],
    },
}
pathlib.Path("/tmp/changeradar-check.json").write_text(json.dumps(body))
PY
gh api --method POST "repos/${GITHUB_REPOSITORY}/check-runs" --input /tmp/changeradar-check.json
