#!/usr/bin/env python3
"""
Run full weight sweep experiments for LACES with meaningful folder semantics.

What this script does:
1) Generates all valid (w1, w2, w3) combinations for a given step on simplex.
2) Updates default_config.properties for each combination.
3) Runs multiple iterations per weight by invoking LacesMainApp directly.
4) Saves logs/metrics into a semantic folder structure.
5) Restores original config file when finished (or on interruption).

Designed for:
- 3-factor weights in default_config.properties:
  laces_weight_latency
  laces_weight_computation
  laces_weight_data
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import re
import subprocess
from pathlib import Path
from typing import Dict, Iterable, List, Tuple


SERVICE_TIME_PATTERN = re.compile(r"average service time:\s*([0-9]+(?:\.[0-9]+)?|NaN)\s*seconds")
FAILED_TASK_PATTERN = re.compile(r"# of failed tasks \(Edge/Cloud/Mobile\):\s*([0-9]+)\(")
DEVICE_COUNT_PATTERN = re.compile(r"Duration:.*?#devices:\s*(\d+)")


def parse_float_token(token: str) -> float:
    if token.strip().lower() == "nan":
        return float("nan")
    return float(token)


def generate_weight_combinations(step: float) -> List[Tuple[float, float, float]]:
    """Generate all valid simplex combinations with step spacing."""
    if step <= 0 or step > 1:
        raise ValueError("step must be in (0, 1].")

    scale_float = 1.0 / step
    scale = int(round(scale_float))
    if abs(scale_float - scale) > 1e-10:
        raise ValueError("step must divide 1 exactly (e.g., 0.1, 0.05, 0.02).")

    combos: List[Tuple[float, float, float]] = []
    for i in range(scale + 1):
        for j in range(scale - i + 1):
            k = scale - i - j
            combos.append((round(i * step, 4), round(j * step, 4), round(k * step, 4)))
    return combos


def replace_or_append_property(text: str, key: str, value: str) -> str:
    """Replace key=value in properties text, append key if not present."""
    pattern = re.compile(rf"^{re.escape(key)}\s*=\s*.*$", re.MULTILINE)
    line = f"{key}={value}"
    if pattern.search(text):
        return pattern.sub(line, text)
    return text.rstrip() + "\n" + line + "\n"


def set_weights_in_config(config_path: Path, w1: float, w2: float, w3: float) -> None:
    """Write the three LACES weights into default_config.properties."""
    text = config_path.read_text(encoding="utf-8")
    text = replace_or_append_property(text, "laces_weight_latency", f"{w1:.2f}")
    text = replace_or_append_property(text, "laces_weight_computation", f"{w2:.2f}")
    text = replace_or_append_property(text, "laces_weight_data", f"{w3:.2f}")
    config_path.write_text(text, encoding="utf-8")


def parse_metrics_from_log(log_path: Path) -> List[Dict[str, float]]:
    """Extract per-device-count service_time and failed_task from one iteration log."""
    text = log_path.read_text(encoding="utf-8", errors="ignore")
    scenario_rows: List[Dict[str, float]] = []

    scenarios = text.split("Scenario started at ")
    for chunk in scenarios[1:]:
        dc = DEVICE_COUNT_PATTERN.search(chunk)
        s = SERVICE_TIME_PATTERN.search(chunk)
        f = FAILED_TASK_PATTERN.search(chunk)
        if dc and s and f:
            scenario_rows.append(
                {
                    "device_count": int(dc.group(1)),
                    "service_time": parse_float_token(s.group(1)),
                    "failed_task": int(f.group(1)),
                }
            )

    return scenario_rows


def app_key_to_file(app: str) -> str:
    mapping = {
        "ar": "applications_only_ar.xml",
        "health": "applications_only_health.xml",
        "info": "applications_only_info.xml",
    }
    if app not in mapping:
        raise ValueError(f"Unknown app key: {app}")
    return mapping[app]


def run_java_iteration(
    project_root: Path,
    scenario_name: str,
    edge_devices_file: str,
    applications_file: str,
    scenario_out_folder: Path,
    iteration: int,
) -> int:
    """Run one simulation iteration by invoking LacesMainApp directly."""
    config_file = project_root / "scripts" / "laces" / "config" / f"{scenario_name}.properties"
    edge_file = project_root / "scripts" / "laces" / "config" / edge_devices_file
    app_file = project_root / "scripts" / "laces" / "config" / applications_file

    classpath = ":".join(
        [
            str(project_root / "bin"),
            str(project_root / "lib" / "cloudsim-7.0.0-alpha.jar"),
            str(project_root / "lib" / "commons-math3-3.6.1.jar"),
            str(project_root / "lib" / "colt.jar"),
        ]
    )

    cmd = [
        "java",
        "-classpath",
        classpath,
        "edu.boun.edgecloudsim.applications.laces.LacesMainApp",
        str(config_file),
        str(edge_file),
        str(app_file),
        str(scenario_out_folder),
        str(iteration),
    ]

    log_path = scenario_out_folder.with_suffix(".log")
    with log_path.open("w", encoding="utf-8") as f:
        proc = subprocess.run(cmd, cwd=project_root, stdout=f, stderr=subprocess.STDOUT)
    return proc.returncode


def write_csv(path: Path, rows: Iterable[Dict[str, object]], fieldnames: List[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def find_best_weight_combinations(metric_rows: List[Dict[str, object]]) -> Dict[str, object]:
    """
    Analyze metrics to find the best weight combinations.
    Criteria: sum of mean(service_time) + mean(failed_task) is lowest.
    
    Returns a dict containing the best combination and analysis details.
    """
    from collections import defaultdict
    
    # Group metrics by weight combination
    weight_groups: Dict[Tuple[str, str, str], List[Dict[str, float]]] = defaultdict(list)
    
    for row in metric_rows:
        w1 = row["w1"]
        w2 = row["w2"]
        w3 = row["w3"]
        
        # Parse numeric values, handling empty strings
        service_time = row.get("service_time", "")
        failed_task = row.get("failed_task", "")
        
        try:
            st_val = float(service_time) if service_time and str(service_time).strip() else float("nan")
            ft_val = float(failed_task) if failed_task and str(failed_task).strip() else float("nan")
        except (ValueError, TypeError):
            continue
        
        weight_groups[(w1, w2, w3)].append({
            "service_time": st_val,
            "failed_task": ft_val,
        })
    
    # Calculate statistics for each weight combination
    best_results = []
    
    for (w1, w2, w3), metrics in weight_groups.items():
        service_times = [m["service_time"] for m in metrics if not (isinstance(m["service_time"], float) and m["service_time"] != m["service_time"])]
        failed_tasks = [m["failed_task"] for m in metrics if not (isinstance(m["failed_task"], float) and m["failed_task"] != m["failed_task"])]
        
        if service_times and failed_tasks:
            mean_service_time = sum(service_times) / len(service_times)
            mean_failed_task = sum(failed_tasks) / len(failed_tasks)
            combined_score = mean_service_time + mean_failed_task
            
            best_results.append({
                "w1": float(w1),
                "w2": float(w2),
                "w3": float(w3),
                "mean_service_time": round(mean_service_time, 4),
                "mean_failed_task": round(mean_failed_task, 4),
                "combined_score": round(combined_score, 4),
                "sample_count": len(metrics),
            })
    
    # Sort by combined score (ascending) to get best at top
    best_results.sort(key=lambda x: x["combined_score"])
    
    result = {
        "total_weight_combinations": len(weight_groups),
        "best_combination": best_results[0] if best_results else None,
        "top_5_combinations": best_results[:5],
        "analysis_notes": "Lower combined_score is better. Score = mean(service_time) + mean(failed_task)",
    }
    
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Run LACES weight sweep with semantic output folders")
    parser.add_argument("--project-root", default="../../..", help="Path from python/ to EdgeCloudSim root")
    parser.add_argument("--app", choices=["ar", "health", "info"], required=True, help="Single-app experiment target")
    parser.add_argument("--step", type=float, default=0.05, help="Weight grid step (default: 0.05)")
    parser.add_argument("--iterations", type=int, default=8, help="Iterations per weight (default: 8)")
    parser.add_argument("--scenario-name", default="default_config", help="Config properties basename")
    parser.add_argument("--edge-devices", default="edge_devices.xml", help="Edge devices config file")
    parser.add_argument(
        "--output-root",
        default="../output/weight_tuning",
        help="Output root folder for experiment artifacts",
    )
    parser.add_argument("--compile", action="store_true", help="Compile before sweep")
    parser.add_argument("--limit", type=int, default=0, help="Optional limit for quick test (0=all combos)")
    parser.add_argument("--dry-run", action="store_true", help="Print plan without running simulations")

    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    project_root = (script_dir / args.project_root).resolve()
    config_path = project_root / "scripts" / "laces" / "config" / f"{args.scenario_name}.properties"

    if not config_path.exists():
        raise FileNotFoundError(f"Config file not found: {config_path}")

    app_file = app_key_to_file(args.app)
    combinations = generate_weight_combinations(args.step)

    if args.limit > 0:
        combinations = combinations[: args.limit]

    run_id = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    app_root = (script_dir / args.output_root / args.app / run_id).resolve()

    run_manifest = {
        "run_id": run_id,
        "app": args.app,
        "app_file": app_file,
        "scenario_name": args.scenario_name,
        "edge_devices": args.edge_devices,
        "step": args.step,
        "iterations_per_weight": args.iterations,
        "num_combinations": len(combinations),
        "objective": "minimize failed_task mean, then service_time mean",
        "created_at": dt.datetime.now().isoformat(),
    }

    app_root.mkdir(parents=True, exist_ok=True)
    (app_root / "run_manifest.json").write_text(json.dumps(run_manifest, indent=2), encoding="utf-8")

    if args.compile and not args.dry_run:
        compile_cwd = project_root / "scripts" / "laces"
        subprocess.run(["bash", "./compile.sh"], cwd=compile_cwd, check=True)

    print(f"App: {args.app}")
    print(f"Combinations: {len(combinations)}")
    print(f"Iterations per weight: {args.iterations}")
    print(f"Output: {app_root}")

    if args.dry_run:
        print("Dry run only. No simulation executed.")
        return

    original_config = config_path.read_text(encoding="utf-8")
    metric_rows: List[Dict[str, object]] = []

    try:
        for idx, (w1, w2, w3) in enumerate(combinations, start=1):
            weight_tag = f"w1_{w1:.2f}_w2_{w2:.2f}_w3_{w3:.2f}"
            weight_dir = app_root / weight_tag
            weight_dir.mkdir(parents=True, exist_ok=True)

            metadata = {
                "index": idx,
                "weight_tag": weight_tag,
                "weights": {"latency": w1, "computation": w2, "data": w3},
                "semantic": {
                    "latency_importance": round(w1 * 100, 1),
                    "computation_importance": round(w2 * 100, 1),
                    "data_importance": round(w3 * 100, 1),
                },
            }
            (weight_dir / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")

            set_weights_in_config(config_path, w1, w2, w3)

            for ite in range(1, args.iterations + 1):
                scenario_out_folder = weight_dir / f"ite{ite}"
                scenario_out_folder.mkdir(parents=True, exist_ok=True)

                rc = run_java_iteration(
                    project_root=project_root,
                    scenario_name=args.scenario_name,
                    edge_devices_file=args.edge_devices,
                    applications_file=app_file,
                    scenario_out_folder=scenario_out_folder,
                    iteration=ite,
                )

                log_path = scenario_out_folder.with_suffix(".log")
                parsed_rows = parse_metrics_from_log(log_path) if log_path.exists() else []
                if not parsed_rows:
                    metric_rows.append(
                        {
                            "app": args.app,
                            "run_id": run_id,
                            "w1": f"{w1:.4f}",
                            "w2": f"{w2:.4f}",
                            "w3": f"{w3:.4f}",
                            "iteration": ite,
                            "device_count": "",
                            "return_code": rc,
                            "service_time": "",
                            "failed_task": "",
                            "log_path": str(log_path),
                        }
                    )
                else:
                    for parsed in parsed_rows:
                        metric_rows.append(
                            {
                                "app": args.app,
                                "run_id": run_id,
                                "w1": f"{w1:.4f}",
                                "w2": f"{w2:.4f}",
                                "w3": f"{w3:.4f}",
                                "iteration": ite,
                                "device_count": parsed["device_count"],
                                "return_code": rc,
                                "service_time": parsed["service_time"],
                                "failed_task": parsed["failed_task"],
                                "log_path": str(log_path),
                            }
                        )

            print(f"[{idx}/{len(combinations)}] done: {weight_tag}")

    finally:
        # Always restore user's original config values.
        config_path.write_text(original_config, encoding="utf-8")

    metrics_csv = app_root / "metrics_per_iteration.csv"
    write_csv(
        metrics_csv,
        metric_rows,
        fieldnames=[
            "app",
            "run_id",
            "w1",
            "w2",
            "w3",
            "iteration",
            "device_count",
            "return_code",
            "service_time",
            "failed_task",
            "log_path",
        ],
    )

    print(f"Saved metrics: {metrics_csv}")

    # Analyze and save best weight combinations
    best_combos = find_best_weight_combinations(metric_rows)
    best_combos_json = app_root / "best_combinations.json"
    best_combos_json.write_text(json.dumps(best_combos, indent=2), encoding="utf-8")
    print(f"Saved best combinations: {best_combos_json}")

    print("Done.")


if __name__ == "__main__":
    main()
