from __future__ import annotations

import csv
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


ROOT = Path(r"d:\软件\eclipse\workspace\TransportationProcurement")
OUT09 = ROOT / r"analysis\巴西数据分析\新版_purchase时间\输出\09_lambda超大值延伸分析_k=1"
RAW_DIR = OUT09 / "01_原始求解目录"
TEST_DIR = OUT09 / "04_DRO活跃样本数检查"
RESULTS_DIR = TEST_DIR / "01_整理结果"
FIG_DIR = TEST_DIR / "02_图"

W = 50
K1 = 1
MIN_ACTIVE_WEIGHT = 1e-12
B_VALUES = [0.1, 0.5, 1.0, 100.0]
REPRESENTATIVE_LAMBDA = 10000.0


@dataclass
class TrialDiag:
    sum_w: float
    sum_w2: float
    ess: float
    top1_w: float
    top5_wsum: float
    max_over_mean: float


def ensure_dirs() -> None:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    FIG_DIR.mkdir(parents=True, exist_ok=True)


def write_text(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def read_weekly_csv(path: Path) -> tuple[list[str], list[np.ndarray], list[int]]:
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f)
        header = next(reader)
        if not header or header[0].strip() != "weekIndex":
            raise ValueError(f"Bad weekly header: {header!r}")
        lane_names = [c.strip() for c in header[1:]]
        week_index: list[int] = []
        periods: list[np.ndarray] = []
        for row in reader:
            if not row:
                continue
            week_index.append(int(float(row[0].strip())))
            periods.append(np.array([float(x.strip() or 0.0) for x in row[1:]], dtype=float))
    return lane_names, periods, week_index


def build_samples_from_periods(periods: list[np.ndarray]) -> list[np.ndarray]:
    # k1=1, raw theta mode, only lag-demand features are active in the formal 09 runs.
    return [periods[t - 1].copy() for t in range(K1, len(periods))]


def standardize_train_and_now(train: np.ndarray, theta_now: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    mean = train.mean(axis=0)
    std = train.std(axis=0)
    std = np.where(std < 1e-12, 1.0, std)
    return (train - mean) / std, (theta_now - mean) / std


def compute_weights(train_theta: np.ndarray, theta_now: np.ndarray, b_value: float) -> tuple[np.ndarray, float]:
    dim = train_theta.shape[1]
    bandwidth_h = b_value / math.pow(train_theta.shape[0], 1.0 / (4.0 + dim))
    dist = np.linalg.norm(train_theta - theta_now[None, :], axis=1)
    log_numer = -np.abs(dist / bandwidth_h)  # EXPONENTIAL kernel
    max_log = float(np.max(log_numer))
    numer = np.exp(log_numer - max_log)
    denom = float(np.sum(numer))
    weights = numer / denom
    return weights, bandwidth_h


def compute_diag(weights: np.ndarray) -> TrialDiag:
    sum_w = float(np.sum(weights))
    sum_w2 = float(np.sum(np.square(weights)))
    ess = float(1.0 / sum_w2) if sum_w2 > 1e-12 else float("nan")
    sorted_w = np.sort(weights)[::-1]
    top1 = float(sorted_w[0]) if sorted_w.size else float("nan")
    top5 = float(np.sum(sorted_w[:5])) if sorted_w.size else float("nan")
    mean_w = sum_w / len(weights) if len(weights) else float("nan")
    max_over_mean = float(top1 / mean_w) if mean_w > 0 else float("nan")
    return TrialDiag(sum_w, sum_w2, ess, top1, top5, max_over_mean)


def representative_global_trials_path(b_value: float) -> Path:
    dir_name = f"RCSAA_W50_k1=1_raw_C{b_value:.2f}_lambda{REPRESENTATIVE_LAMBDA:.2f}"
    return RAW_DIR / dir_name / "global_trials.csv"


def load_representative_validation(b_value: float) -> pd.DataFrame:
    path = representative_global_trials_path(b_value)
    df = pd.read_csv(path)
    df["C_h"] = df["C_h"].astype(float)
    return df[["trialId", "testIdx", "ESS", "top1W", "top5Wsum", "maxW_over_meanW"]].copy()


def plot_active_counts(per_trial: pd.DataFrame) -> None:
    colors = {
        0.1: "#8E2C8A",
        0.5: "#D26A1C",
        1.0: "#2E7F9E",
        100.0: "#7A9E2E",
    }
    markers = {
        0.1: "o",
        0.5: "s",
        1.0: "^",
        100.0: "D",
    }
    linestyles = {
        0.1: "-",
        0.5: "--",
        1.0: "-.",
        100.0: ":",
    }

    plt.rcParams.update(
        {
            "font.family": "Times New Roman",
            "mathtext.fontset": "stix",
            "font.size": 11,
            "axes.labelsize": 11,
            "legend.fontsize": 10,
            "xtick.labelsize": 10,
            "ytick.labelsize": 10,
            "axes.spines.top": False,
            "axes.spines.right": False,
            "axes.linewidth": 0.8,
            "grid.linewidth": 0.5,
            "pdf.fonttype": 42,
            "ps.fonttype": 42,
        }
    )

    fig, ax = plt.subplots(figsize=(6.8, 3.9))
    for b in B_VALUES:
        sub = per_trial[per_trial["B"] == b].sort_values("actual_test_period")
        ax.plot(
            sub["actual_test_period"],
            sub["active_sample_count"],
            color=colors[b],
            linestyle=linestyles[b],
            marker=markers[b],
            linewidth=1.9,
            markersize=5.8,
            markerfacecolor="white",
            markeredgewidth=1.2,
            label=f"B = {int(b) if float(b).is_integer() else b}",
        )
    ax.set_xlabel("Test period")
    ax.set_ylabel("Active samples")
    ax.grid(True, linestyle="--", alpha=0.35)
    ax.legend(loc="upper right", frameon=False, handlelength=2.8)
    fig.tight_layout()
    fig.savefig(FIG_DIR / "各B_每个trial活跃样本数.png", dpi=600, bbox_inches="tight")
    fig.savefig(FIG_DIR / "各B_每个trial活跃样本数.pdf", bbox_inches="tight")
    plt.close(fig)

    fig, ax = plt.subplots(figsize=(6.6, 3.8))
    sub = per_trial[per_trial["B"] == 0.1].sort_values("actual_test_period")
    ax.plot(
        sub["actual_test_period"],
        sub["active_sample_count"],
        color=colors[0.1],
        linestyle="-",
        marker=markers[0.1],
        linewidth=2.0,
        markersize=5.8,
        markerfacecolor="white",
        markeredgewidth=1.2,
    )
    ax.set_xlabel("Test period")
    ax.set_ylabel("Active samples")
    ax.grid(True, linestyle="--", alpha=0.35)
    fig.tight_layout()
    fig.savefig(FIG_DIR / "B=0.1_每个trial活跃样本数.png", dpi=600, bbox_inches="tight")
    fig.savefig(FIG_DIR / "B=0.1_每个trial活跃样本数.pdf", bbox_inches="tight")
    plt.close(fig)


def build_active_sample_tables() -> tuple[pd.DataFrame, pd.DataFrame]:
    weekly_path = RAW_DIR / "RCSAA_W50_k1=1_raw_C0.10_lambda10000.00" / "巴西五大区23OD_周度宽表.csv"
    _, periods, week_index = read_weekly_csv(weekly_path)
    samples = build_samples_from_periods(periods)

    records: list[dict[str, float | int]] = []
    validation_rows: list[dict[str, float | int | str]] = []

    for b in B_VALUES:
        representative = load_representative_validation(b)
        for test_idx in range(W, len(samples)):
            trial_id = test_idx - W
            train = np.stack(samples[test_idx - W : test_idx], axis=0)
            theta_now = samples[test_idx].copy()
            train_std, theta_now_std = standardize_train_and_now(train, theta_now)
            weights, bandwidth_h = compute_weights(train_std, theta_now_std, b)
            diag = compute_diag(weights)
            active_count = int(np.sum(weights > MIN_ACTIVE_WEIGHT))
            actual_test_period = week_index[test_idx + K1]

            records.append(
                {
                    "B": b,
                    "trialId": trial_id,
                    "testIdx": test_idx,
                    "actual_test_period": actual_test_period,
                    "trainSize": W,
                    "bandwidthH": bandwidth_h,
                    "active_sample_count": active_count,
                    "inactive_sample_count": W - active_count,
                    "active_ratio": active_count / W,
                    "ESS_recomputed": diag.ess,
                    "top1W_recomputed": diag.top1_w,
                    "top5Wsum_recomputed": diag.top5_wsum,
                    "maxW_over_meanW_recomputed": diag.max_over_mean,
                }
            )

        merged = pd.DataFrame([r for r in records if r["B"] == b]).merge(
            representative,
            on=["trialId", "testIdx"],
            how="left",
            validate="one_to_one",
        )
        merged["ESS_abs_diff"] = (merged["ESS_recomputed"] - merged["ESS"]).abs()
        merged["top1W_abs_diff"] = (merged["top1W_recomputed"] - merged["top1W"]).abs()
        merged["top5Wsum_abs_diff"] = (merged["top5Wsum_recomputed"] - merged["top5Wsum"]).abs()
        merged["maxW_over_meanW_abs_diff"] = (
            merged["maxW_over_meanW_recomputed"] - merged["maxW_over_meanW"]
        ).abs()
        validation_rows.extend(merged.to_dict("records"))

    per_trial = pd.DataFrame(records).sort_values(["B", "trialId"]).reset_index(drop=True)
    validation = pd.DataFrame(validation_rows).sort_values(["B", "trialId"]).reset_index(drop=True)
    return per_trial, validation


def summarize(per_trial: pd.DataFrame, validation: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    summary = (
        per_trial.groupby("B", as_index=False)
        .agg(
            n_trials=("trialId", "count"),
            mean_active_sample_count=("active_sample_count", "mean"),
            median_active_sample_count=("active_sample_count", "median"),
            min_active_sample_count=("active_sample_count", "min"),
            max_active_sample_count=("active_sample_count", "max"),
            p25_active_sample_count=("active_sample_count", lambda s: float(s.quantile(0.25))),
            p75_active_sample_count=("active_sample_count", lambda s: float(s.quantile(0.75))),
            mean_active_ratio=("active_ratio", "mean"),
            mean_ESS=("ESS_recomputed", "mean"),
            mean_top1W=("top1W_recomputed", "mean"),
        )
        .sort_values("B")
        .reset_index(drop=True)
    )

    validation_summary = (
        validation.groupby("B", as_index=False)
        .agg(
            n_trials=("trialId", "count"),
            max_ESS_abs_diff=("ESS_abs_diff", "max"),
            max_top1W_abs_diff=("top1W_abs_diff", "max"),
            max_top5Wsum_abs_diff=("top5Wsum_abs_diff", "max"),
            max_maxW_over_meanW_abs_diff=("maxW_over_meanW_abs_diff", "max"),
        )
        .sort_values("B")
        .reset_index(drop=True)
    )
    return summary, validation_summary


def main() -> None:
    ensure_dirs()

    write_text(
        TEST_DIR / "说明.txt",
        "\n".join(
            [
                "这个目录用于检查 09 中各个 B 值下，DRO 在每个 trial 里实际有多少个训练 sample 被纳入模型。",
                "正式口径：活跃 sample 定义为 weight > 1e-12，这与当前 DROModel / DROBenders 的正式逻辑一致。",
                "这里不复用 trials_*.csv 里四舍五入后的 sampleWeights，而是按 Java 正式流程重新计算权重，以免低估活跃样本数。",
                "lambda 不影响权重，所以活跃 sample 数只由 trial 和 B 决定；这里重点检查 B=0.1，同时给出 B=0.5, 1, 100 的对照。",
            ]
        ),
    )

    per_trial, validation = build_active_sample_tables()
    summary, validation_summary = summarize(per_trial, validation)

    per_trial.to_csv(RESULTS_DIR / "每个trial活跃样本数_按B.csv", index=False, encoding="utf-8-sig")
    per_trial[per_trial["B"] == 0.1].to_csv(
        RESULTS_DIR / "B=0.1_每个trial活跃样本数.csv", index=False, encoding="utf-8-sig"
    )
    summary.to_csv(RESULTS_DIR / "活跃样本数汇总_按B.csv", index=False, encoding="utf-8-sig")
    validation.to_csv(RESULTS_DIR / "与09原始ESS_top1W核对_逐trial.csv", index=False, encoding="utf-8-sig")
    validation_summary.to_csv(
        RESULTS_DIR / "与09原始ESS_top1W核对_汇总.csv", index=False, encoding="utf-8-sig"
    )

    write_text(
        RESULTS_DIR / "说明.txt",
        "\n".join(
            [
                "每个trial活跃样本数_按B.csv：每个 trial 在不同 B 下，满足 weight > 1e-12 的活跃样本数量。",
                "B=0.1_每个trial活跃样本数.csv：单独抽出 B=0.1，便于直接看它是否只剩极少样本。",
                "活跃样本数汇总_按B.csv：按 B 汇总均值、中位数、四分位数、最小值、最大值。",
                "与09原始ESS_top1W核对_*.csv：把重算得到的 ESS / top1W / top5Wsum / maxW_over_meanW 与 09 原始结果对齐核对，确保重算逻辑一致。",
            ]
        ),
    )

    plot_active_counts(per_trial)
    write_text(
        FIG_DIR / "说明.txt",
        "\n".join(
            [
                "各B_每个trial活跃样本数：四个 B 值在每个 test period 下的活跃样本数对比图。",
                "B=0.1_每个trial活跃样本数：只看 B=0.1，便于判断它在不同 trial 下是否退化为极少数样本。",
            ]
        ),
    )


if __name__ == "__main__":
    main()
