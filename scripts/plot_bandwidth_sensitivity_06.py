from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[1]
OUT06 = (
    ROOT
    / "analysis"
    / "巴西数据分析"
    / "新版_purchase时间"
    / "输出"
    / "06_补充分析_C_h灵敏度"
)
SUMMARY_CSV = (
    OUT06
    / "01_正式汇总表"
    / "01_CSAA_C_h完整汇总"
    / "CSAA_C_h灵敏度_完整汇总统计.csv"
)
BASE_SUMMARY_CSV = (
    ROOT
    / "analysis"
    / "巴西数据分析"
    / "新版_purchase时间"
    / "输出"
    / "03_10供应商基础网格与提取"
    / "按purchase时间_raw网格实验_10供应商"
    / "全部实验配置_4方法_对齐51次样本外结果"
    / "全部实验配置_对齐后51次样本外_summary.csv"
)
FIG_DIR = OUT06 / "01_正式汇总表" / "04_带宽灵敏度图"

ORDER = ["0.1", "0.5", "1", "3", "5", "10", "30", "50", "100", "inf"]
X_POS = np.arange(len(ORDER))
X_LABELS = ["0.1", "0.5", "1", "3", "5", "10", "30", "50", "100", "∞"]

COLORS = {
    1: "#0072B2",
    2: "#D55E00",
    3: "#009E73",
}
MARKERS = {
    1: "o",
    2: "s",
    3: "^",
}


def load_summary() -> pd.DataFrame:
    df = pd.read_csv(SUMMARY_CSV)
    df["base_method"] = df["base_method"].fillna("")
    df["k"] = df["k"].astype(int)
    df["C_h"] = df["C_h"].fillna("")
    return df


def load_deterministic_baseline() -> dict[str, float]:
    df = pd.read_csv(BASE_SUMMARY_CSV)
    mean_row = df[df["method_name"] == "Mean"].iloc[0]
    return {
        "mean_obj": float(mean_row["mean_实现目标值"]),
        "std_obj": float(mean_row["std_实现目标值"]),
        "q20_obj": float(mean_row["p20_实现目标值"]),
        "q95_obj": float(mean_row["p95_实现目标值"]),
        "max_obj": float(mean_row["max_实现目标值"]),
    }


def format_b(value: str) -> str:
    x = float(value)
    if x.is_integer():
        return str(int(x))
    return str(x)


def build_plot_df(df: pd.DataFrame, det: dict[str, float]) -> pd.DataFrame:
    saa = df[df["base_method"] == "SAA"].iloc[0]
    csaa = df[df["base_method"] == "CSAA"].copy()

    csaa["B_plot"] = csaa["C_h"].apply(format_b)
    csaa["imp_mean"] = (det["mean_obj"] - csaa["mean_realized_obj"]) / det["mean_obj"] * 100.0
    csaa["imp_std"] = (det["std_obj"] - csaa["std_realized_obj"]) / det["std_obj"] * 100.0
    csaa["imp_q20"] = (det["q20_obj"] - csaa["p20_realized_obj"]) / det["q20_obj"] * 100.0
    csaa["imp_q95"] = (det["q95_obj"] - csaa["p95_realized_obj"]) / det["q95_obj"] * 100.0
    csaa["imp_max"] = (det["max_obj"] - csaa["max_realized_obj"]) / det["max_obj"] * 100.0
    csaa["AESS"] = csaa["mean_ess"]

    saa_imp_mean = (det["mean_obj"] - float(saa["mean_realized_obj"])) / det["mean_obj"] * 100.0
    saa_imp_std = (det["std_obj"] - float(saa["std_realized_obj"])) / det["std_obj"] * 100.0
    saa_imp_q20 = (det["q20_obj"] - float(saa["p20_realized_obj"])) / det["q20_obj"] * 100.0
    saa_imp_q95 = (det["q95_obj"] - float(saa["p95_realized_obj"])) / det["q95_obj"] * 100.0
    saa_imp_max = (det["max_obj"] - float(saa["max_realized_obj"])) / det["max_obj"] * 100.0

    saa_rows = []
    for k in (1, 2, 3):
        saa_rows.append(
            {
                "k": k,
                "B_plot": "inf",
                "imp_mean": saa_imp_mean,
                "imp_std": saa_imp_std,
                "imp_q20": saa_imp_q20,
                "imp_q95": saa_imp_q95,
                "imp_max": saa_imp_max,
                "AESS": 50.0,
            }
        )

    plot_df = pd.concat(
        [
            csaa[["k", "B_plot", "imp_mean", "imp_std", "imp_q20", "imp_q95", "imp_max", "AESS"]],
            pd.DataFrame(saa_rows),
        ],
        ignore_index=True,
    )
    plot_df["B_plot"] = pd.Categorical(plot_df["B_plot"], categories=ORDER, ordered=True)
    plot_df = plot_df.sort_values(["k", "B_plot"]).reset_index(drop=True)
    return plot_df


def style_axis(ax: plt.Axes) -> None:
    ax.set_xticks(X_POS)
    ax.set_xticklabels(X_LABELS)
    ax.grid(True, linestyle="--", alpha=0.35)
    ax.tick_params(axis="x", pad=4)


def plot_one(plot_df: pd.DataFrame, col: str, ylabel: str, out_base: Path) -> None:
    fig, ax = plt.subplots(figsize=(5.6, 3.9))
    for k in (1, 2, 3):
        sub = plot_df[plot_df["k"] == k].set_index("B_plot").loc[ORDER].reset_index()
        ax.plot(
            X_POS,
            sub[col].values,
            color=COLORS[k],
            marker=MARKERS[k],
            linewidth=2.1,
            markersize=6.6,
            markerfacecolor="white",
            markeredgewidth=1.35,
            label=f"k = {k}",
        )
    ax.set_xlabel("B")
    ax.set_ylabel(ylabel)
    style_axis(ax)
    ax.legend(frameon=False, loc="center right", bbox_to_anchor=(0.98, 0.52))
    fig.tight_layout()
    fig.savefig(out_base.with_suffix(".pdf"), bbox_inches="tight")
    fig.savefig(out_base.with_suffix(".png"), dpi=600, bbox_inches="tight")
    plt.close(fig)


def plot_combined(plot_df: pd.DataFrame, metric_info: list[tuple[str, str]]) -> None:
    fig, axes = plt.subplots(2, 3, figsize=(13.8, 7.8))
    axes = axes.flatten()

    for idx, (ax, (col, ylabel)) in enumerate(zip(axes, metric_info)):
        for k in (1, 2, 3):
            sub = plot_df[plot_df["k"] == k].set_index("B_plot").loc[ORDER].reset_index()
            ax.plot(
                X_POS,
                sub[col].values,
                color=COLORS[k],
                marker=MARKERS[k],
                linewidth=2.0,
                markersize=5.8,
                markerfacecolor="white",
                markeredgewidth=1.2,
                label=f"k = {k}",
            )
        ax.set_ylabel(ylabel)
        if idx >= 3:
            ax.set_xlabel("B")
        style_axis(ax)

    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", ncol=3, frameon=False)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    fig.savefig(FIG_DIR / "sens_B_combined.pdf", bbox_inches="tight")
    fig.savefig(FIG_DIR / "sens_B_combined.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    plt.rcParams.update(
        {
            "font.family": "Times New Roman",
            "font.size": 12,
            "axes.labelsize": 12,
            "legend.fontsize": 11,
            "xtick.labelsize": 12,
            "ytick.labelsize": 11,
            "axes.spines.top": False,
            "axes.spines.right": False,
            "axes.linewidth": 0.8,
            "grid.linewidth": 0.5,
            "pdf.fonttype": 42,
            "ps.fonttype": 42,
            "mathtext.fontset": "stix",
        }
    )

    FIG_DIR.mkdir(parents=True, exist_ok=True)

    df = load_summary()
    det = load_deterministic_baseline()
    plot_df = build_plot_df(df, det)

    metric_info = [
        ("imp_mean", r"$\Delta_{\mathrm{cost}}\ (\%)$"),
        ("imp_std", r"$\Delta_{\mathrm{std}}\ (\%)$"),
        ("imp_q20", r"$\Delta_{q_{0.20}}\ (\%)$"),
        ("imp_q95", r"$\Delta_{q_{0.95}}\ (\%)$"),
        ("imp_max", r"$\Delta_{\max}\ (\%)$"),
        ("AESS", "AESS"),
    ]

    file_names = {
        "imp_mean": "sens_B_mean",
        "imp_std": "sens_B_std",
        "imp_q20": "sens_B_q20",
        "imp_q95": "sens_B_q95",
        "imp_max": "sens_B_max",
        "AESS": "sens_B_aess",
    }

    for col, ylabel in metric_info:
        plot_one(plot_df, col, ylabel, FIG_DIR / file_names[col])

    plot_combined(plot_df, metric_info)
    print(f"wrote figures to {FIG_DIR}")


if __name__ == "__main__":
    main()
