#!/usr/bin/env python3
"""
Grid-search tuning for a 3-factor weighted scoring model.

Core model:
    score = w1*x1 + w2*x2 + w3*x3

Constraints:
    w1 + w2 + w3 = 1
    w1, w2, w3 in [0, 1]

This script supports two workflows:
1) MSE workflow (default):
   - Input CSV columns: x1, x2, x3, target
   - Evaluates each weight combination by MSE(predicted_score, target)
2) Iteration-metrics workflow (optional):
   - Input CSV columns: w1, w2, w3, iteration, service_time, failed_task
   - Aggregates 8 iterations (configurable) per weight and ranks by:
       failed_task_mean (asc), then service_time_mean (asc)

Optional category mapping is included for future extension:
    0.0-0.2 -> Device
    0.2-0.6 -> Edge
    0.6-1.0 -> Cloud
"""

from __future__ import annotations

import argparse
import math
from typing import List, Tuple

import pandas as pd


def generate_weight_combinations(step: float) -> List[Tuple[float, float, float]]:
    """Generate all valid (w1, w2, w3) combinations on a simplex grid.

    Uses integer grid indexing to avoid floating-point equality issues.
    Example: step=0.05 -> scale=20 and i+j+k=20.
    """
    if step <= 0 or step > 1:
        raise ValueError("step must be in (0, 1].")

    scale_float = 1.0 / step
    scale = int(round(scale_float))
    if not math.isclose(scale_float, scale, abs_tol=1e-10):
        raise ValueError(
            "step must divide 1 exactly on the grid (e.g. 0.1, 0.05, 0.02)."
        )

    combinations: List[Tuple[float, float, float]] = []
    for i in range(scale + 1):
        for j in range(scale - i + 1):
            k = scale - i - j
            w1 = round(i * step, 4)
            w2 = round(j * step, 4)
            w3 = round(k * step, 4)
            combinations.append((w1, w2, w3))

    return combinations


def evaluate_weights(df: pd.DataFrame, w1: float, w2: float, w3: float) -> float:
    """Compute MSE for one (w1, w2, w3) combination."""
    predicted_score = w1 * df["x1"] + w2 * df["x2"] + w3 * df["x3"]
    mse = ((predicted_score - df["target"]) ** 2).mean()
    return float(mse)


def grid_search(df: pd.DataFrame, step: float) -> Tuple[Tuple[float, float, float], float, pd.DataFrame]:
    """Run grid search over all valid weight combinations and return sorted results."""
    required_cols = {"x1", "x2", "x3", "target"}
    missing = required_cols - set(df.columns)
    if missing:
        raise ValueError(f"Missing required columns for MSE mode: {sorted(missing)}")

    combinations = generate_weight_combinations(step)
    rows = []

    for w1, w2, w3 in combinations:
        mse = evaluate_weights(df, w1, w2, w3)
        rows.append(
            {
                "w1": round(w1, 4),
                "w2": round(w2, 4),
                "w3": round(w3, 4),
                "mse": mse,
            }
        )

    results = pd.DataFrame(rows).sort_values(by="mse", ascending=True).reset_index(drop=True)

    best_row = results.iloc[0]
    best_weights = (float(best_row["w1"]), float(best_row["w2"]), float(best_row["w3"]))
    best_mse = float(best_row["mse"])

    return best_weights, best_mse, results


def score_to_category(score: float) -> str:
    """Map score into Device/Edge/Cloud categories for future extension."""
    if score <= 0.2:
        return "Device"
    if score <= 0.6:
        return "Edge"
    return "Cloud"


def add_score_and_category(
    df: pd.DataFrame,
    w1: float,
    w2: float,
    w3: float,
    add_category: bool = False,
) -> pd.DataFrame:
    """Add predicted_score (and optional category) to a copy of dataframe."""
    out = df.copy()
    out["predicted_score"] = w1 * out["x1"] + w2 * out["x2"] + w3 * out["x3"]
    out["predicted_score"] = out["predicted_score"].clip(0.0, 1.0)

    if add_category:
        out["predicted_category"] = out["predicted_score"].map(score_to_category)

    return out


def rank_simulation_iterations(
    sim_df: pd.DataFrame,
    expected_iterations: int = 8,
    group_cols: List[str] | None = None,
) -> pd.DataFrame:
    """Optional workflow: rank weights from simulation outcomes.

    Expected columns:
        w1, w2, w3, iteration, service_time, failed_task

    Ranking policy:
        1) failed_task_mean ascending
        2) service_time_mean ascending
    """
    required = {"w1", "w2", "w3", "iteration", "service_time", "failed_task"}
    missing = required - set(sim_df.columns)
    if missing:
        raise ValueError(f"Missing required columns for sim mode: {sorted(missing)}")

    if group_cols is None:
        group_cols = ["w1", "w2", "w3"]

    grouped = (
        sim_df.groupby(group_cols, as_index=False)
        .agg(
            iterations=("iteration", "nunique"),
            service_time_mean=("service_time", "mean"),
            service_time_std=("service_time", "std"),
            failed_task_mean=("failed_task", "mean"),
            failed_task_std=("failed_task", "std"),
        )
    )

    for col in ["w1", "w2", "w3"]:
        if col in grouped.columns:
            grouped[col] = grouped[col].round(4)
    grouped["iterations_ok"] = grouped["iterations"] >= expected_iterations

    ranked = grouped.sort_values(
        by=["failed_task_mean", "service_time_mean"],
        ascending=[True, True],
    ).reset_index(drop=True)

    return ranked


def main() -> None:
    parser = argparse.ArgumentParser(description="Tune 3-factor weighted scoring model.")
    parser.add_argument("--input-csv", required=True, help="Input CSV path")
    parser.add_argument(
        "--output-csv",
        default="grid_search_results.csv",
        help="Output CSV path for full sorted results",
    )
    parser.add_argument("--step", type=float, default=0.05, help="Grid step size (default: 0.05)")
    parser.add_argument("--top-k", type=int, default=10, help="How many top rows to print")
    parser.add_argument(
        "--mode",
        choices=["mse", "sim"],
        default="mse",
        help="mse: grid search by MSE; sim: rank per-weight simulation metrics",
    )
    parser.add_argument(
        "--iterations",
        type=int,
        default=8,
        help="Expected iterations per weight in sim mode (default: 8)",
    )
    parser.add_argument(
        "--group-by",
        default="",
        help="Comma-separated extra group columns for sim mode (e.g. app,device_count)",
    )
    parser.add_argument(
        "--save-best-predictions",
        default="",
        help="Optional CSV path to save row-level predictions for best weights (mse mode)",
    )
    parser.add_argument(
        "--add-category",
        action="store_true",
        help="If set with --save-best-predictions, add Device/Edge/Cloud category",
    )

    args = parser.parse_args()

    df = pd.read_csv(args.input_csv)

    if args.mode == "mse":
        best_weights, best_mse, results = grid_search(df, args.step)

        print("=== Best Result (MSE mode) ===")
        print(f"Best weights: w1={best_weights[0]:.4f}, w2={best_weights[1]:.4f}, w3={best_weights[2]:.4f}")
        print(f"Best MSE: {best_mse:.8f}")

        print(f"\n=== Top {args.top_k} Weight Combinations ===")
        print(results.head(args.top_k).to_string(index=False))

        results.to_csv(args.output_csv, index=False)
        print(f"\nSaved full results to: {args.output_csv}")

        if args.save_best_predictions:
            pred_df = add_score_and_category(
                df,
                best_weights[0],
                best_weights[1],
                best_weights[2],
                add_category=args.add_category,
            )
            pred_df.to_csv(args.save_best_predictions, index=False)
            print(f"Saved best-weight predictions to: {args.save_best_predictions}")

    else:
        extra_group_cols: List[str] = []
        if args.group_by.strip():
            extra_group_cols = [c.strip() for c in args.group_by.split(",") if c.strip()]

        group_cols = ["w1", "w2", "w3"] + extra_group_cols
        ranked = rank_simulation_iterations(
            df,
            expected_iterations=args.iterations,
            group_cols=group_cols,
        )

        best = ranked.iloc[0]
        print("=== Best Result (SIM mode) ===")
        print(
            "Best weights: "
            f"w1={best['w1']:.4f}, w2={best['w2']:.4f}, w3={best['w3']:.4f} | "
            f"failed_task_mean={best['failed_task_mean']:.6f}, "
            f"service_time_mean={best['service_time_mean']:.6f}, "
            f"iterations={int(best['iterations'])}"
        )

        print(f"\n=== Top {args.top_k} Weight Combinations ===")
        print(ranked.head(args.top_k).to_string(index=False))

        ranked.to_csv(args.output_csv, index=False)
        print(f"\nSaved full results to: {args.output_csv}")


if __name__ == "__main__":
    main()
