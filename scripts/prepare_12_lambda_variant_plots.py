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
TEST_ROOT = OUT_ROOT / "12_lambda实验_MQC与capacity两组新配置"

VARIANTS = [
    {
        "name": "01_MQC_0.3-0.4_capacity_0.5-0.7",
        "label": "MQC=[0.3,0.4], capacity=[0.5,0.7]",
    },
    {
        "name": "02_MQC_0.5-0.6_capacity_0.8-1.0",
        "label": "MQC=[0.5,0.6], capacity=[0.8,1.0]",
    },
]

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
B_ORDER = ["0.1", "0.5", "1", "100"]
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


def write_text_if_absent(path: Path, text: str) -> None:
    if path.exists():
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def parse_float(value: object) -> float:
    text = "" if value is None else str(value).strip()
    if text == "" or text.lower() == "nan":
        return math.nan
    return float(text)


def format_lambda(value: str) -> str:
    num = float(value)
    if num.is_integer():
        return str(int(num))
    return str(num)


def aligned_rows(global_trials_csv: Path) -> list[dict[str, str]]:
    rows = read_csv(global_trials_csv)
    dedup: dict[tuple[str, str], dict[str, str]] = {}
    for row in rows:
        dedup[(row["trialId"], row["testIdx"])] = row

    out = []
    for row in dedup.values():
        actual_period = int(row["testIdx"]) + int(row["k1Lag"])
        if MIN_PERIOD <= actual_period <= MAX_PERIOD:
            out.append(row)
    if len(out) != 51:
        raise RuntimeError(f"{global_trials_csv} aligned row count expected 51, got {len(out)}")
    return sorted(out, key=lambda r: int(r["testIdx"]))


def baseline_stats(variant_root: Path) -> dict[str, float]:
    rows = aligned_rows(variant_root / "00_MeanDeterministic_baseline" / "global_trials.csv")
    realized = [parse_float(r["realizedObj"]) for r in rows]
    return {
        "mean_cost": mean(realized),
        "std_cost": pstdev(realized) if len(realized) > 1 else 0.0,
        "max_cost": max(realized),
    }


def summarize_dir(dir_path: Path) -> dict[str, object]:
    rows = aligned_rows(dir_path / "global_trials.csv")
    realized = sorted(parse_float(r["realizedObj"]) for r in rows)
    if dir_path.name.startswith("CSAA_"):
        parts = dir_path.name.split("_")
        b = parts[4].replace("C", "")
        method_name = "CSAA"
        lam = "0"
    else:
        parts = dir_path.name.split("_")
        b = parts[4].replace("C", "")
        method_name = "RCSAA"
        lam = dir_path.name.split("lambda", 1)[1]
    return {
        "method_name": method_name,
        "B": str(float(b)).rstrip("0").rstrip("."),
        "lambda": format_lambda(lam),
        "trial_count": len(rows),
        "mean_realized_obj": mean(realized),
        "std_realized_obj": pstdev(realized) if len(realized) > 1 else 0.0,
        "max_realized_obj": realized[-1],
        "avg_selected": mean(parse_float(r["selectedCount"]) for r in rows),
        "source": dir_path.name,
    }


def build_rows(variant_root: Path) -> list[dict[str, object]]:
    raw_root = variant_root / "01_raw_outputs"
    rows: list[dict[str, object]] = []
    for dir_path in sorted(raw_root.glob("CSAA_W50_k1=1_raw_C*")):
        if (dir_path / "global_summary.csv").exists():
            rows.append(summarize_dir(dir_path))
    for dir_path in sorted(raw_root.glob("RCSAA_W50_k1=1_raw_C*_lambda*")):
        if (dir_path / "global_summary.csv").exists():
            rows.append(summarize_dir(dir_path))
    return sorted(rows, key=lambda r: (B_ORDER.index(str(r["B"])), float(str(r["lambda"]))))


def add_improvements(rows: list[dict[str, object]], baseline: dict[str, float]) -> list[dict[str, object]]:
    out = []
    for row in rows:
        row = dict(row)
        row["imp_mean"] = (baseline["mean_cost"] - float(row["mean_realized_obj"])) / baseline["mean_cost"] * 100.0
        row["imp_std"] = (baseline["std_cost"] - float(row["std_realized_obj"])) / baseline["std_cost"] * 100.0
        row["imp_max"] = (baseline["max_cost"] - float(row["max_realized_obj"])) / baseline["max_cost"] * 100.0
        out.append(row)
    return out


def set_style() -> None:
    plt.rcParams.update(
        {
            "font.family": "Times New Roman",
            "mathtext.fontset": "stix",
            "font.size": 14,
            "axes.labelsize": 20,
            "legend.fontsize": 15,
            "xtick.labelsize": 15,
            "ytick.labelsize": 17,
            "axes.spines.top": False,
            "axes.spines.right": False,
            "axes.linewidth": 1.0,
            "grid.linewidth": 0.6,
            "pdf.fonttype": 42,
            "ps.fonttype": 42,
        }
    )


def legend_handles() -> list[Line2D]:
    return [
        Line2D(
            [0],
            [0],
            color=COLORS[b],
            linestyle=LINESTYLES[b],
            linewidth=2.8,
            marker=MARKERS[b],
            markersize=8.8,
            markerfacecolor=COLORS[b],
            markeredgecolor="white",
            markeredgewidth=1.2,
            alpha=0.95,
            label=f"B = {b}",
        )
        for b in B_ORDER
    ]


def values_by_b(rows: list[dict[str, object]], metric: str) -> dict[str, np.ndarray]:
    idx_map = {lam: i for i, lam in enumerate(LAMBDA_ORDER)}
    out: dict[str, np.ndarray] = {}
    for b in B_ORDER:
        arr = np.full(len(LAMBDA_ORDER), np.nan)
        for row in rows:
            if row["B"] == b and row["lambda"] in idx_map:
                arr[idx_map[str(row["lambda"])]] = float(row[metric])
        out[b] = arr
    return out


def plot_single(rows: list[dict[str, object]], metric: str, ylabel: str, out_name: str, fig_dir: Path) -> None:
    x = np.arange(len(LAMBDA_ORDER))
    metric_map = values_by_b(rows, metric)
    fig, ax = plt.subplots(figsize=(7.8, 5.2))
    for b in B_ORDER:
        ax.plot(
            x,
            metric_map[b],
            color=COLORS[b],
            linestyle=LINESTYLES[b],
            marker=MARKERS[b],
            linewidth=2.8,
            markersize=8.4,
            markerfacecolor=COLORS[b],
            markeredgecolor="white",
            markeredgewidth=1.1,
            alpha=0.95,
        )
    ax.set_xlabel(r"$\lambda$")
    ax.set_ylabel(ylabel)
    ax.set_xticks(x)
    ax.set_xticklabels(LAMBDA_ORDER, rotation=30, ha="right")
    ax.tick_params(axis="x", pad=6)
    ax.tick_params(axis="y", pad=8)
    ax.grid(True, linestyle="--", alpha=0.35)
    ax.legend(handles=legend_handles(), frameon=False, loc="lower right", handlelength=3.0)
    fig.subplots_adjust(left=0.16, right=0.985, bottom=0.23, top=0.985)
    fig.savefig(fig_dir / f"{out_name}.pdf", bbox_inches="tight")
    fig.savefig(fig_dir / f"{out_name}.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def plot_combined(rows: list[dict[str, object]], fig_dir: Path) -> None:
    specs = [
        ("imp_mean", r"$\Delta_{\mathrm{cost}}$ (\%)"),
        ("imp_std", r"$\Delta_{\mathrm{std}}$ (\%)"),
        ("imp_max", r"$\Delta_{\max}$ (\%)"),
        ("avg_selected", "ANC"),
    ]
    x = np.arange(len(LAMBDA_ORDER))
    fig, axes = plt.subplots(2, 2, figsize=(12.8, 8.9))
    axes = axes.flatten()
    handles = legend_handles()
    for ax, (metric, ylabel) in zip(axes, specs):
        metric_map = values_by_b(rows, metric)
        for b in B_ORDER:
            ax.plot(
                x,
                metric_map[b],
                color=COLORS[b],
                linestyle=LINESTYLES[b],
                marker=MARKERS[b],
                linewidth=2.6,
                markersize=7.8,
                markerfacecolor=COLORS[b],
                markeredgecolor="white",
                markeredgewidth=1.0,
                alpha=0.95,
            )
        ax.set_xlabel(r"$\lambda$")
        ax.set_ylabel(ylabel)
        ax.set_xticks(x)
        ax.set_xticklabels(LAMBDA_ORDER, rotation=30, ha="right")
        ax.tick_params(axis="x", pad=5)
        ax.tick_params(axis="y", pad=6)
        ax.grid(True, linestyle="--", alpha=0.35)
        ax.legend(handles=handles, frameon=False, loc="lower right", handlelength=2.9)

    fig.subplots_adjust(left=0.09, right=0.985, bottom=0.12, top=0.985, wspace=0.20, hspace=0.24)
    fig.savefig(fig_dir / "lambda_combined.pdf", bbox_inches="tight")
    fig.savefig(fig_dir / "lambda_combined.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def prepare_variant(variant: dict[str, str]) -> None:
    variant_root = TEST_ROOT / variant["name"]
    processed_dir = variant_root / "02_processed"
    figures_dir = variant_root / "03_figures"
    processed_dir.mkdir(parents=True, exist_ok=True)
    figures_dir.mkdir(parents=True, exist_ok=True)

    baseline = baseline_stats(variant_root)
    rows = add_improvements(build_rows(variant_root), baseline)

    fieldnames = [
        "method_name",
        "B",
        "lambda",
        "trial_count",
        "mean_realized_obj",
        "std_realized_obj",
        "max_realized_obj",
        "avg_selected",
        "imp_mean",
        "imp_std",
        "imp_max",
        "source",
    ]
    write_csv(processed_dir / "k=1_lambda_summary_relative_to_variant_deterministic.csv", rows, fieldnames)
    write_csv(processed_dir / "deterministic_baseline_summary.csv", [baseline], list(baseline.keys()))

    plot_single(rows, "imp_mean", r"$\Delta_{\mathrm{cost}}$ (\%)", "lambda_mean", figures_dir)
    plot_single(rows, "imp_std", r"$\Delta_{\mathrm{std}}$ (\%)", "lambda_std", figures_dir)
    plot_single(rows, "imp_max", r"$\Delta_{\max}$ (\%)", "lambda_max", figures_dir)
    plot_single(rows, "avg_selected", "ANC", "lambda_selected", figures_dir)
    plot_combined(rows, figures_dir)

    write_text_if_absent(
        processed_dir / "说明.txt",
        (
            "本目录按之前最终版本相同的口径整理当前 variant 的 lambda 实验结果。\n"
            "做法：先对 global_trials.csv 去重，再只保留 actual_test_period 在 53-103 的 51 个样本外 trial，"
            "然后计算 mean/std/max/ANC，并相对本 variant 自己的 MeanDeterministic baseline 计算改进率。\n"
        ),
    )
    write_text_if_absent(
        figures_dir / "说明.txt",
        (
            "本目录图形沿用之前最终版本的风格与指标定义：\n"
            "前三张图分别为相对 deterministic baseline 的 mean/std/max 改进，第四张为 ANC，另含 2x2 组合图。\n"
        ),
    )


def main() -> None:
    set_style()
    for variant in VARIANTS:
        prepare_variant(variant)
    print(f"prepared plots under {TEST_ROOT}")


if __name__ == "__main__":
    main()
