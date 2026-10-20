#!/usr/bin/env bash
set -euo pipefail
# Sticky PR comment from report.compact.md. Skips missing reports so a
# failed analyze does not become a comment-job failure.
OUTPUT="${1:?output directory}"
RUN_URL="${2:?actions run url}"
LOGO_URL="${3:?logo url}"
PR_NUMBER="${4:?pull request number}"
COMPACT="${OUTPUT}/report.compact.md"
if [ ! -s "${COMPACT}" ]; then
  echo "changeradar: no report.compact.md; skip comment"
  exit 0
fi
python3 - "${RUN_URL}" "${LOGO_URL}" "${COMPACT}" <<'PY'
import json
import pathlib
import sys

run_url, logo_url, compact = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])
marker = "<!-- changeradar-report -->"
body = compact.read_text()
if body.startswith("## ChangeRadar"):
    body = body.split("\n", 1)[-1].lstrip()
header = (
    marker
    + "\n\n"
    + '<img src="'
    + logo_url
    + '" width="280" height="97" alt="ChangeRadar" />\n\n'
    + "[Full Markdown, JSON, and SARIF]("
    + run_url
    + ")\n\n"
)
pathlib.Path("/tmp/changeradar-comment.json").write_text(
    json.dumps({"body": header + body})
)
PY
COMMENT_ID="$(
  gh api --paginate "repos/${GITHUB_REPOSITORY}/issues/${PR_NUMBER}/comments" \
    --jq '.[] | select(.user.login=="github-actions[bot]" and (.body | contains("<!-- changeradar-report -->"))) | .id' \
    | awk 'NF { print; exit }'
)"
if [ -n "${COMMENT_ID}" ]; then
  gh api --method PATCH "repos/${GITHUB_REPOSITORY}/issues/comments/${COMMENT_ID}" \
    --input /tmp/changeradar-comment.json
else
  gh api --method POST "repos/${GITHUB_REPOSITORY}/issues/${PR_NUMBER}/comments" \
    --input /tmp/changeradar-comment.json
fi
