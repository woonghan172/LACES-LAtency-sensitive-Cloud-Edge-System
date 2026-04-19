#!/usr/bin/env python3
"""Utilities for comparing LACES-family policy logs in notebooks."""

from __future__ import annotations

from pathlib import Path
import re
from typing import Dict, Iterable, List

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


DEVICE_COUNT_PATTERN = re.compile(r"Duration:.*?#devices:\s*(\d+)")
FAILED_TASK_PATTERN = re.compile(r"# of failed tasks \(Edge/Cloud/Mobile\):\s*(\d+)\(")
COMPLETED_TASK_PATTERN = re.compile(r"# of completed tasks \(Edge/Cloud/Mobile\):\s*(\d+)\(")
THROUGHPUT_PATTERN = re.compile(r"throughput:\s*([0-9]+(?:\.[0-9]+)?|NaN)\s*tasks/sec")
SERVICE_TIME_PATTERN = re.compile(r"average service time:\s*([0-9]+(?:\.[0-9]+)?|NaN)\s*seconds")
PROCESSING_TIME_PATTERN = re.compile(r"average processing time:\s*([0-9]+(?:\.[0-9]+)?|NaN)\s*seconds")
NETWORK_DELAY_PATTERN = re.compile(r"average network delay:\s*([0-9]+(?:\.[0-9]+)?|NaN)\s*seconds")
UTILIZATION_PATTERN = re.compile(
    r"average server utilization Edge/Cloud/Mobile:\s*"
    r"([0-9]+(?:\.[0-9]+)?|NaN)/([0-9]+(?:\.[0-9]+)?|NaN)/([0-9]+(?:\.[0-9]+)?|NaN)"
)
OVERHEAD_PATTERN = re.compile(r"average overhead:\s*([0-9]+(?:\.[0-9]+)?)\s*ns")


DEFAULT_METRICS = [
    "failed_percentage",
    "throughput",
    "service_time",
    "processing_time",
    "network_delay",
    "overhead_ns",
]


def _parse_float_token(token: str) -> float:
    if token.strip().lower() == "nan":
        return float("nan")
    return float(token)


def _policy_from_path(log_path: Path) -> str:
    parts = [p.lower() for p in log_path.parts]
    if "scripts" in parts:
        idx = parts.index("scripts")
        if idx + 1 < len(parts):
            candidate = log_path.parts[idx + 1]
            return candidate.upper()
    return log_path.parent.name.upper()


def _parse_scenario_chunk(chunk: str) -> Dict[str, float] | None:
    dc_match = DEVICE_COUNT_PATTERN.search(chunk)
    throughput_match = THROUGHPUT_PATTERN.search(chunk)
    service_time_match = SERVICE_TIME_PATTERN.search(chunk)
    processing_time_match = PROCESSING_TIME_PATTERN.search(chunk)
    network_delay_match = NETWORK_DELAY_PATTERN.search(chunk)

    failed_matches = FAILED_TASK_PATTERN.findall(chunk)
    completed_matches = COMPLETED_TASK_PATTERN.findall(chunk)

    if not (dc_match and throughput_match and service_time_match and processing_time_match and network_delay_match):
        return None
    if not failed_matches or not completed_matches:
        return None

    # The final pair corresponds to the aggregate task summary for that scenario.
    failed_tasks = int(failed_matches[-1])
    completed_tasks = int(completed_matches[-1])
    total_tasks = failed_tasks + completed_tasks

    util_match = UTILIZATION_PATTERN.search(chunk)
    edge_utilization = float("nan")
    cloud_utilization = float("nan")
    if util_match:
        edge_utilization = _parse_float_token(util_match.group(1))
        cloud_utilization = _parse_float_token(util_match.group(2))

    overhead_match = OVERHEAD_PATTERN.search(chunk)
    overhead_ns = float("nan")
    if overhead_match:
        overhead_ns = _parse_float_token(overhead_match.group(1))

    failed_percentage = (100.0 * failed_tasks / total_tasks) if total_tasks > 0 else 0.0

    return {
        "device_count": int(dc_match.group(1)),
        "failed_percentage": failed_percentage,
        "throughput": _parse_float_token(throughput_match.group(1)),
        "service_time": _parse_float_token(service_time_match.group(1)),
        "processing_time": _parse_float_token(processing_time_match.group(1)),
        "network_delay": _parse_float_token(network_delay_match.group(1)),
        "edge_utilization": edge_utilization,
        "cloud_utilization": cloud_utilization,
        "overhead_ns": overhead_ns,
        "completed_tasks": completed_tasks,
        "failed_tasks": failed_tasks,
        "total_tasks": total_tasks,
    }


def _rows_from_log(log_path: Path) -> List[Dict[str, float]]:
    text = log_path.read_text(encoding="utf-8", errors="ignore")
    rows: List[Dict[str, float]] = []

    policy = _policy_from_path(log_path)
    chunks = text.split("Scenario started at ")[1:]

    for chunk in chunks:
        metrics = _parse_scenario_chunk(chunk)
        if metrics is None:
            continue

        row: Dict[str, float] = {
            "policy": policy,
            "log_path": str(log_path),
        }
        row.update(metrics)
        rows.append(row)

    return rows


def collect_rows(inputs: Iterable[str | Path]) -> pd.DataFrame:
    rows: List[Dict[str, float]] = []

    for p in inputs:
        log_path = Path(p).expanduser().resolve()
        if not log_path.exists():
            raise FileNotFoundError(f"Log file does not exist: {log_path}")
        rows.extend(_rows_from_log(log_path))

    if not rows:
        raise ValueError("No valid scenario rows were parsed from the provided logs.")

    df = pd.DataFrame(rows)
    return df.sort_values(by=["policy", "device_count"]).reset_index(drop=True)


def aggregate_rows(raw_df: pd.DataFrame) -> pd.DataFrame:
    if raw_df.empty:
        raise ValueError("raw_df is empty")

    metric_cols = [
        "failed_percentage",
        "throughput",
        "service_time",
        "processing_time",
        "network_delay",
        "edge_utilization",
        "cloud_utilization",
        "overhead_ns",
        "completed_tasks",
        "failed_tasks",
        "total_tasks",
    ]

    grouped = (
        raw_df.groupby(["policy", "device_count"], as_index=False)[metric_cols]
        .mean(numeric_only=True)
        .sort_values(["policy", "device_count"])
        .reset_index(drop=True)
    )

    return grouped


def _plot_metric_subplot(ax: plt.Axes, summary_df: pd.DataFrame, metric: str, ylabel: str) -> None:
    for policy, policy_df in summary_df.groupby("policy"):
        policy_df = policy_df.sort_values("device_count")
        ax.plot(
            policy_df["device_count"],
            policy_df[metric],
            marker="o",
            linewidth=2,
            markersize=5,
            label=policy,
        )

    ax.set_title(metric.replace("_", " ").title())
    ax.set_xlabel("Device Count")
    ax.set_ylabel(ylabel)
    ax.grid(True, alpha=0.3)


def plot_metric_grid(summary_df: pd.DataFrame, output_path: str | Path, metrics: List[str] | None = None) -> None:
    metrics = metrics or DEFAULT_METRICS

    n = len(metrics)
    cols = 3
    rows = int(np.ceil(n / cols))

    fig, axes = plt.subplots(rows, cols, figsize=(6 * cols, 4 * rows), squeeze=False)
    flat_axes = axes.flatten()

    for i, metric in enumerate(metrics):
        _plot_metric_subplot(flat_axes[i], summary_df, metric, metric.replace("_", " ").title())

    for j in range(len(metrics), len(flat_axes)):
        flat_axes[j].axis("off")

    handles, labels = flat_axes[0].get_legend_handles_labels()
    if handles:
        fig.legend(handles, labels, loc="upper center", ncol=max(1, len(labels)))

    fig.tight_layout(rect=(0, 0, 1, 0.95))

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(output_path, dpi=180)
    plt.show()


def plot_utilization_grid(summary_df: pd.DataFrame, output_path: str | Path) -> None:
    fig, ax = plt.subplots(1, 1, figsize=(12, 6))

    for policy, policy_df in summary_df.groupby("policy"):
        policy_df = policy_df.sort_values("device_count")
        ax.plot(
            policy_df["device_count"],
            policy_df["edge_utilization"],
            marker="o",
            linestyle="-",
            linewidth=2,
            markersize=5,
            label=f"{policy} (Edge)",
        )
        ax.plot(
            policy_df["device_count"],
            policy_df["cloud_utilization"],
            marker="s",
            linestyle="--",
            linewidth=2,
            markersize=5,
            label=f"{policy} (Cloud)",
        )

    ax.set_title("Server Utilization (Edge / Cloud)")
    ax.set_xlabel("Device Count")
    ax.set_ylabel("Utilization")
    ax.grid(True, alpha=0.3)
    ax.legend(loc="best")

    fig.tight_layout()

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(output_path, dpi=180)
    plt.show()
