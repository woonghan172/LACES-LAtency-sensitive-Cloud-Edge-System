#!/usr/bin/env python3
"""
Build per-iteration metrics CSV from LACES simulation logs.

Expected folder structure (recommended):
  scripts/laces/output/weight_tuning/<app>/w1_<w1>_w2_<w2>_w3_<w3>/ite*.log

Extracted metrics per log:
  - service_time: from "average service time: <value> seconds"
  - failed_task: from "# of failed tasks (Edge/Cloud/Mobile): <value>(...)"

Output CSV columns:
  app,w1,w2,w3,iteration,service_time,failed_task,log_path
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import Dict, List, Optional

import pandas as pd

WEIGHT_DIR_PATTERN = re.compile(
    r"w1_(?P<w1>\d+(?:\.\d+)?)_w2_(?P<w2>\d+(?:\.\d+)?)_w3_(?P<w3>\d+(?:\.\d+)?)"
)
ITER_PATTERN = re.compile(r"ite(?P<iteration>\d+)\.log$")
SERVICE_TIME_PATTERN = re.compile(r"average service time:\s*([0-9]+(?:\.[0-9]+)?|NaN)\s*seconds")
FAILED_TASK_PATTERN = re.compile(r"# of failed tasks \(Edge/Cloud/Mobile\):\s*([0-9]+)\(")
DEVICE_COUNT_PATTERN = re.compile(r"Duration:.*?#devices:\s*(\d+)")


def parse_float_token(token: str) -> float:
    if token.strip().lower() == "nan":
        return float("nan")
    return float(token)


def parse_weight_dir_name(name: str) -> Optional[Dict[str, float]]:
    m = WEIGHT_DIR_PATTERN.fullmatch(name)
    if not m:
        return None
    return {
        "w1": round(float(m.group("w1")), 4),
        "w2": round(float(m.group("w2")), 4),
        "w3": round(float(m.group("w3")), 4),
    }


def parse_iteration_from_filename(name: str) -> Optional[int]:
    m = ITER_PATTERN.search(name)
    if not m:
        return None
    return int(m.group("iteration"))


def parse_metrics_from_log(log_path: Path) -> List[Dict[str, float]]:
    text = log_path.read_text(encoding="utf-8", errors="ignore")
    rows: List[Dict[str, float]] = []

    scenarios = text.split("Scenario started at ")
    for chunk in scenarios[1:]:
        dc = DEVICE_COUNT_PATTERN.search(chunk)
        s = SERVICE_TIME_PATTERN.search(chunk)
        f = FAILED_TASK_PATTERN.search(chunk)
        if dc and s and f:
            rows.append(
                {
                    "device_count": int(dc.group(1)),
                    "service_time": parse_float_token(s.group(1)),
                    "failed_task": int(f.group(1)),
                }
            )

    return rows


def collect_rows(root: Path) -> List[Dict[str, object]]:
    rows: List[Dict[str, object]] = []

    if not root.exists():
        raise FileNotFoundError(f"Input root does not exist: {root}")

    for app_dir in sorted([p for p in root.iterdir() if p.is_dir()]):
        app_name = app_dir.name

        for weight_dir in sorted([p for p in app_dir.iterdir() if p.is_dir()]):
            weights = parse_weight_dir_name(weight_dir.name)
            if weights is None:
                continue

            for log_path in sorted(weight_dir.glob("ite*.log")):
                iteration = parse_iteration_from_filename(log_path.name)
                if iteration is None:
                    continue

                metrics_rows = parse_metrics_from_log(log_path)
                if not metrics_rows:
                    continue

                for metrics in metrics_rows:
                    rows.append(
                        {
                            "app": app_name,
                            "w1": weights["w1"],
                            "w2": weights["w2"],
                            "w3": weights["w3"],
                            "iteration": iteration,
                            "device_count": metrics["device_count"],
                            "service_time": metrics["service_time"],
                            "failed_task": metrics["failed_task"],
                            "log_path": str(log_path),
                        }
                    )

    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description="Build metrics CSV from LACES ite*.log files")
    parser.add_argument("--input-root", required=True, help="Root folder, e.g. ../output/weight_tuning")
    parser.add_argument("--output-csv", required=True, help="Output CSV path")
    args = parser.parse_args()

    root = Path(args.input_root).resolve()
    rows = collect_rows(root)

    if not rows:
        print("No valid log rows found. Check folder structure and log contents.")
        return

    df = pd.DataFrame(rows).sort_values(
        by=["app", "device_count", "w1", "w2", "w3", "iteration"],
        ascending=[True, True, True, True, True, True],
    )
    df.to_csv(args.output_csv, index=False)

    print(f"Saved metrics CSV: {args.output_csv}")
    print(f"Rows: {len(df)}")
    print(df.head(10).to_string(index=False))


if __name__ == "__main__":
    main()
