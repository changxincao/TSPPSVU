from __future__ import annotations

import csv
import math
from pathlib import Path
from statistics import mean, pstdev

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.lines import Line2D


ROOT = Path(__file__).resolve().parents[1]
OUT_ROOT = ROOT / "analysis" / "巴西数据分析" / "新版_purchase时间" / "输出"

ITER3_ROOT = OUT_ROOT / "07_lambda灵敏度分析_k=1_代表性C_h" / "迭代3"
ITER3_EXTRACT = ITER3_ROOT / "02_整理结果"
ITER3_FIG = ITER3_ROOT / "03_图"
ITER3_RAW = ITER3_ROOT / "01_原始求解目录"

ITER1_SUMMARY = (
    OUT_ROOT
    / "07_lambda灵敏度分析_k=1_代表性C_h"
    / "迭代1"
    / "02_k=1_代表性C_h提取结果"
    / "k=1_代表性C_h下_CSAA与RCSAA_lambda灵敏度_summary.csv"
)

ITER2_RAW = (
    OUT_ROOT
    / "07_lambda灵敏度分析_k=1_代表性C_h"
    / "迭代2"
    / "02_保持当前代码_扩展lambda到300_500_1000"
    / "01_原始求解目录"
)

ITER4_V4_SUMMARY = (
    OUT_ROOT
    / "07_lambda灵敏度分析_k=1_代表性C_h"
    / "迭代4"
    / "05_B=0.1_三种权重处理版本对照"
    / "04_版本4_最终版"
    / "01_整理结果"
    / "k=1_最终版_扩展lambda到100000_summary.csv"
)

ITER4_B01_RAW = (
    OUT_ROOT
    / "07_lambda灵敏度分析_k=1_代表性C_h"
    / "迭代4"
    / "11_B=0.1_权重下限1e-8重跑_lambda全扫描"
    / "01_raw_outputs"
)

BASELINE_SUMMARY = (
    OUT_ROOT
    / "03_10供应商基础网格与提取"
    / "按purchase时间_raw网格实验_10供应商"
    / "全部实验配置_4方法_对齐51次样本外结果"
    / "全部实验配置_对齐后51次样本外_summary.csv"
)

LAMBDA_ORDER = [
    "0",
    "0.01",
    "0.05",
    "0.1",
    "1",
    "5",
    "10",
    "50",
    "100",
    "300",
    "500",
    "1000",
    "10000",
    "50000",
    "100000",
]
CH_ORDER = ["0.1", "0.5", "1", "100"]
COLORS = {"0.1": "#7A1E6C", "0.5": "#C75B12", "1": "#1F6E8C", "100": "#6B8E23"}
LINESTYLES = {"0.1": "-", "0.5": "--", "1": "-.", "100": ":"}
MARKERS = {"0.1": "o", "0.5": "s", "1": "^", "100": "D"}
MIN_PERIOD = 53
MAX_PERIOD = 103


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def write_csv(path: Path, rows: list[dict[str, object]], fieldnames: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def parse_float(value: object) -> float:
    text = "" if value is None else str(value).strip()
    if text == "" or text.lower() == "nan":
        return math.nan
    return float(text)


def format_lambda(value: object) -> str:
    num = float(value)
    if num.is_integer():
        return str(int(num))
    return str(num)


def format_b(value: object) -> str:
    num = float(value)
    return str(num).rstrip("0").rstrip(".")


def percentile(sorted_vals: list[float], p: float) -> float:
    if not sorted_vals:
        return math.nan
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    pos = (len(sorted_vals) - 1) * p
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return sorted_vals[lo]
    frac = pos - lo
    return sorted_vals[lo] * (1 - frac) + sorted_vals[hi] * frac


def build_baseline() -> dict[str, float]:
    rows = read_csv(BASELINE_SUMMARY)
    baseline_row = next(r for r in rows if r["method_name"] == "Mean" and r["k1"] == "1")
    return {
        "mean_cost": parse_float(baseline_row["mean_实现目标值"]),
        "std_cost": parse_float(baseline_row["std_实现目标值"]),
        "q95_cost": parse_float(baseline_row["p95_实现目标值"]),
        "max_cost": parse_float(baseline_row["max_实现目标值"]),
    }


def summarize_global_trials(dir_path: Path, method_name: str = "RCSAA") -> dict[str, object]:
    rows = read_csv(dir_path / "global_trials.csv")
    dedup: dict[tuple[str, str], dict[str, str]] = {}
    for row in rows:
        dedup[(row["trialId"], row["testIdx"])] = row

    aligned = []
    for row in dedup.values():
        actual_period = int(row["testIdx"]) + int(row["k1Lag"])
        if MIN_PERIOD <= actual_period <= MAX_PERIOD:
            aligned.append(row)

    if len(aligned) != 51:
        raise RuntimeError(f"{dir_path.name} aligned row count expected 51, got {len(aligned)}")

    realized = sorted(parse_float(r["realizedObj"]) for r in aligned)
    std_value = pstdev(realized) if len(realized) > 1 else 0.0
    parts = dir_path.name.split("_")
    b = parts[4].replace("C", "")
    lam = dir_path.name.split("lambda", 1)[1]
    return {
        "method_name": method_name,
        "C_h": format_b(b),
        "lambda": format_lambda(lam),
        "trial_count": len(aligned),
        "mean_realized_obj": mean(realized),
        "std_realized_obj": std_value,
        "q95_realized_obj": percentile(realized, 0.95),
        "max_realized_obj": realized[-1],
        "avg_selected": mean(parse_float(r["selectedCount"]) for r in aligned),
        "source": dir_path.name,
    }


def load_iter1_q95_map() -> dict[tuple[str, str, str], float]:
    out: dict[tuple[str, str, str], float] = {}
    for row in read_csv(ITER1_SUMMARY):
        method_name = row["method_name"]
        b = format_b(row["C_h"])
        lam = "0" if method_name == "CSAA" else format_lambda(row["lambda"])
        out[(method_name, b, lam)] = parse_float(row["p95_实现目标值"])
    return out


def load_raw_q95_map() -> dict[tuple[str, str, str], float]:
    out: dict[tuple[str, str, str], float] = {}
    for root in (ITER2_RAW, ITER3_RAW):
        for dir_path in sorted(root.glob("RCSAA_W50_k1=1_raw_C*_lambda*")):
            if not (dir_path / "global_trials.csv").exists():
                continue
            row = summarize_global_trials(dir_path)
            out[(row["method_name"], row["C_h"], row["lambda"])] = float(row["q95_realized_obj"])
    for dir_path in sorted(ITER4_B01_RAW.glob("RCSAA_W50_k1=1_raw_C0.10_lambda*")):
        if not (dir_path / "global_trials.csv").exists():
            continue
        row = summarize_global_trials(dir_path)
        out[(row["method_name"], "0.1", row["lambda"])] = float(row["q95_realized_obj"])
    return out


def build_rows() -> list[dict[str, object]]:
    iter1_q95 = load_iter1_q95_map()
    raw_q95 = load_raw_q95_map()
    out: list[dict[str, object]] = []
    for row in read_csv(ITER4_V4_SUMMARY):
        method_name = row["method_name"]
        b = format_b(row["B"])
        lam = format_lambda(row["lambda"])
        q95 = raw_q95.get((method_name, b, lam))
        if q95 is None:
            q95 = iter1_q95.get((method_name, b, lam), math.nan)
        out.append(
            {
                "method_name": method_name,
                "C_h": b,
                "lambda": lam,
                "trial_count": int(float(row["trial_count"])),
                "mean_realized_obj": parse_float(row["mean_realized_obj"]),
                "std_realized_obj": parse_float(row["std_realized_obj"]),
                "q95_realized_obj": q95,
                "max_realized_obj": parse_float(row["max_realized_obj"]),
                "avg_selected": parse_float(row["avg_selected"]),
                "imp_mean": parse_float(row["imp_mean"]),
                "imp_std": parse_float(row["imp_std"]),
                "imp_max": parse_float(row["imp_max"]),
                "source": row["source"],
            }
        )
    return sorted(out, key=lambda r: (CH_ORDER.index(str(r["C_h"])), float(str(r["lambda"]))))


def add_q95_improvement(rows: list[dict[str, object]]) -> list[dict[str, object]]:
    baseline = build_baseline()
    out = []
    for row in rows:
        new_row = dict(row)
        new_row["imp_q95"] = (baseline["q95_cost"] - float(new_row["q95_realized_obj"])) / baseline["q95_cost"] * 100.0
        out.append(new_row)
    return out


def set_style() -> None:
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


def legend_handles() -> tuple[list[Line2D], list[str]]:
    handles: list[Line2D] = []
    labels: list[str] = []
    for b in CH_ORDER:
        handles.append(
            Line2D(
                [0],
                [0],
                color=COLORS[b],
                linestyle=LINESTYLES[b],
                linewidth=1.9,
                marker=MARKERS[b],
                markersize=6.0,
                markerfacecolor=COLORS[b],
                markeredgecolor="white",
                markeredgewidth=1.1,
                alpha=0.95,
            )
        )
        labels.append(f"B = {b}")
    return handles, labels


def values_by_b(rows: list[dict[str, object]], metric: str) -> dict[str, np.ndarray]:
    idx_map = {lam: i for i, lam in enumerate(LAMBDA_ORDER)}
    out: dict[str, np.ndarray] = {}
    for b in CH_ORDER:
        arr = np.full(len(LAMBDA_ORDER), np.nan)
        for row in rows:
            if row["C_h"] == b and row["lambda"] in idx_map:
                arr[idx_map[str(row["lambda"])]] = float(row[metric])
        out[b] = arr
    return out


def plot_single(rows: list[dict[str, object]], metric: str, ylabel: str, out_name: str) -> None:
    x = np.arange(len(LAMBDA_ORDER))
    metric_map = values_by_b(rows, metric)
    fig, ax = plt.subplots(figsize=(6.6, 4.2))
    for b in CH_ORDER:
        ax.plot(
            x,
            metric_map[b],
            color=COLORS[b],
            linestyle=LINESTYLES[b],
            marker=MARKERS[b],
            linewidth=1.9,
            markersize=6.0,
            markerfacecolor=COLORS[b],
            markeredgecolor="white",
            markeredgewidth=1.0,
            alpha=0.95,
        )
    ax.set_xlabel(r"$\lambda$")
    ax.set_ylabel(ylabel)
    ax.set_xticks(x)
    ax.set_xticklabels(LAMBDA_ORDER, rotation=35, ha="right")
    ax.grid(True, linestyle="--", alpha=0.35)
    handles, labels = legend_handles()
    ax.legend(handles, labels, frameon=False, loc="lower right", handlelength=2.6)
    fig.tight_layout()
    fig.savefig(ITER3_FIG / f"{out_name}.pdf", bbox_inches="tight")
    fig.savefig(ITER3_FIG / f"{out_name}.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def plot_combined(rows: list[dict[str, object]]) -> None:
    specs = [
        ("imp_mean", r"$\Delta_{\mathrm{cost}}$ (\%)"),
        ("imp_std", r"$\Delta_{\mathrm{std}}$ (\%)"),
        ("imp_max", r"$\Delta_{\max}$ (\%)"),
        ("avg_selected", "ANC"),
    ]
    x = np.arange(len(LAMBDA_ORDER))
    fig, axes = plt.subplots(2, 2, figsize=(11.6, 7.8))
    axes = axes.flatten()
    handles, labels = legend_handles()
    for ax, (metric, ylabel) in zip(axes, specs):
        metric_map = values_by_b(rows, metric)
        for b in CH_ORDER:
            ax.plot(
                x,
                metric_map[b],
                color=COLORS[b],
                linestyle=LINESTYLES[b],
                marker=MARKERS[b],
                linewidth=1.9,
                markersize=6.0,
                markerfacecolor=COLORS[b],
                markeredgecolor="white",
                markeredgewidth=1.0,
                alpha=0.95,
            )
        ax.set_xlabel(r"$\lambda$")
        ax.set_ylabel(ylabel)
        ax.set_xticks(x)
        ax.set_xticklabels(LAMBDA_ORDER, rotation=35, ha="right")
        ax.grid(True, linestyle="--", alpha=0.35)
        ax.legend(handles, labels, frameon=False, loc="lower right", handlelength=2.6)
    fig.tight_layout()
    fig.savefig(ITER3_FIG / "tail_lambda_combined.pdf", bbox_inches="tight")
    fig.savefig(ITER3_FIG / "tail_lambda_combined.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    ITER3_EXTRACT.mkdir(parents=True, exist_ok=True)
    ITER3_FIG.mkdir(parents=True, exist_ok=True)
    set_style()
    rows = add_q95_improvement(build_rows())
    fieldnames = [
        "method_name",
        "C_h",
        "lambda",
        "trial_count",
        "mean_realized_obj",
        "std_realized_obj",
        "q95_realized_obj",
        "max_realized_obj",
        "avg_selected",
        "imp_mean",
        "imp_std",
        "imp_q95",
        "imp_max",
        "source",
    ]
    write_csv(ITER3_EXTRACT / "k=1_扩展lambda到100000_summary.csv", rows, fieldnames)
    plot_single(rows, "imp_mean", r"$\Delta_{\mathrm{cost}}$ (\%)", "tail_lambda_mean")
    plot_single(rows, "imp_std", r"$\Delta_{\mathrm{std}}$ (\%)", "tail_lambda_std")
    plot_single(rows, "imp_q95", r"$\Delta_{q_{0.95}}$ (\%)", "tail_lambda_q95")
    plot_single(rows, "imp_max", r"$\Delta_{\max}$ (\%)", "tail_lambda_max")
    plot_single(rows, "avg_selected", "ANC", "tail_lambda_selected")
    plot_combined(rows)
    print(f"prepared iter3 outputs from version4 under {ITER3_ROOT}")


if __name__ == "__main__":
    main()
