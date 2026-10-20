#!/usr/bin/env python3
"""Validate ChangeRadar report.json against schemas/report-v1.schema.json."""

import json
import pathlib
import sys

try:
    import jsonschema
except ImportError:
    sys.stderr.write("jsonschema is required (python3 -m pip install jsonschema)\n")
    sys.exit(2)

root = pathlib.Path.cwd()
schema_path = root / "schemas" / "report-v1.schema.json"
report_path = root / "build" / "changeradar" / "report.json"
schema = json.loads(schema_path.read_text(encoding="utf-8"))
instance = json.loads(report_path.read_text(encoding="utf-8"))
jsonschema.Draft202012Validator(schema).validate(instance)
print("report.json matches schemas/report-v1.schema.json")
