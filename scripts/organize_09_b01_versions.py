from __future__ import annotations

import csv
import math
import shutil
from pathlib import Path
from statistics import mean, pstdev

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


ROOT = Path(__file__).resolve().parents[1]
OUT_ROOT = ROOT / "analysis" / "巴西数据分析" / "新版_purchase时间" / "输出"
ROOT_09 = OUT_ROOT / "09_lambda超大值延伸分析_k=1"
ROOT_11 = OUT_ROOT / "11_B=0.1_权重下限1e-8重跑_lambda全扫描"
COMPARE_ROOT = ROOT_09 / "05_B=0.1_三种权重处理版本对照"

OLD_SUMMARY = ROOT_09 / "02_整理结果" / "k=1_扩展lambda到100000_summary.csv"
NEW_RAW_ROOT = ROOT_11 / "01_raw_outputs"
BASELINE_SUMMARY = (
    OUT_ROOT
    / "03_10供应商基础网格与提取"
    / "按purchase时间_raw网格实验_10供应商"
    / "全部实验配置_4方法_对齐51次样本外结果"
    / "全部实验配置_对齐后51次样本外_summary.csv"
)

VERSION1 = COMPARE_ROOT / "01_版本1_全部sample直接入模_数值问题"
VERSION2 = COMPARE_ROOT / "02_版本2_删除极小权重sample_旧结果"
VERSION3 = COMPARE_ROOT / "03_版本3_权重下限1e-8重标化_新结果"

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

COLOR = "#7A1E6C"
LINESTYLE = "-"
MARKER = "o"
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


def format_lambda(value: str | float) -> str:
    num = float(value)
    if num.is_integer():
        return str(int(num))
    return str(num)


def baseline_values() -> dict[str, float]:
    rows = read_csv(BASELINE_SUMMARY)
    row = next(r for r in rows if r["method_name"] == "Mean" and r["k1"] == "1")
    return {
        "mean_cost": parse_float(row["mean_实现目标值"]),
        "std_cost": parse_float(row["std_实现目标值"]),
        "max_cost": parse_float(row["max_实现目标值"]),
    }


def add_improvements(rows: list[dict[str, object]]) -> list[dict[str, object]]:
    base = baseline_values()
    out: list[dict[str, object]] = []
    for row in rows:
        new_row = dict(row)
        new_row["imp_mean"] = (base["mean_cost"] - float(new_row["mean_realized_obj"])) / base["mean_cost"] * 100.0
        new_row["imp_std"] = (base["std_cost"] - float(new_row["std_realized_obj"])) / base["std_cost"] * 100.0
        new_row["imp_max"] = (base["max_cost"] - float(new_row["max_realized_obj"])) / base["max_cost"] * 100.0
        out.append(new_row)
    return out


def version2_rows() -> list[dict[str, object]]:
    rows = read_csv(OLD_SUMMARY)
    out = []
    for row in rows:
        b = row.get("C_h", row.get("B", ""))
        if str(b) != "0.1":
            continue
        out.append(
            {
                "method_name": row["method_name"],
                "B": "0.1",
                "lambda": format_lambda(row["lambda"]),
                "trial_count": int(float(row["trial_count"])),
                "mean_realized_obj": parse_float(row["mean_realized_obj"]),
                "std_realized_obj": parse_float(row["std_realized_obj"]),
                "max_realized_obj": parse_float(row["max_realized_obj"]),
                "avg_selected": parse_float(row.get("avg_selected", row.get("mean_selected_count", ""))),
                "source": row.get("source", "09_old"),
            }
        )
    return add_improvements(sorted(out, key=lambda r: float(str(r["lambda"]))))


def summarize_new_raw(dir_path: Path) -> dict[str, object]:
    rows = read_csv(dir_path / "global_trials.csv")
    dedup: dict[tuple[str, str], dict[str, str]] = {}
    for row in rows:
        dedup[(row["trialId"], row["testIdx"])] = row

    aligned: list[dict[str, str]] = []
    for row in dedup.values():
        actual_period = int(row["testIdx"]) + int(row["k1Lag"])
        if MIN_PERIOD <= actual_period <= MAX_PERIOD:
            aligned.append(row)

    if len(aligned) != 51:
        raise RuntimeError(f"{dir_path.name} aligned row count expected 51, got {len(aligned)}")

    realized = sorted(parse_float(r["realizedObj"]) for r in aligned)
    return {
        "method_name": "RCSAA",
        "B": "0.1",
        "lambda": format_lambda(dir_path.name.split("lambda", 1)[1]),
        "trial_count": 51,
        "mean_realized_obj": mean(realized),
        "std_realized_obj": pstdev(realized) if len(realized) > 1 else 0.0,
        "max_realized_obj": realized[-1],
        "avg_selected": mean(parse_float(r["selectedCount"]) for r in aligned),
        "source": dir_path.name,
    }


def version3_rows(old_rows: list[dict[str, object]]) -> list[dict[str, object]]:
    csaa_row = next(r for r in old_rows if r["method_name"] == "CSAA" and str(r["lambda"]) == "0")
    out = [dict(csaa_row)]
    for dir_path in sorted(NEW_RAW_ROOT.glob("RCSAA_W50_k1=1_raw_C0.10_lambda*")):
        if not (dir_path / "global_summary.csv").exists():
            continue
        out.append(summarize_new_raw(dir_path))
    return add_improvements(sorted(out, key=lambda r: float(str(r["lambda"]))))


def ensure_dir_structure() -> None:
    for path in [
        VERSION1 / "01_说明",
        VERSION1 / "02_图",
        VERSION1 / "03_代码",
        VERSION2 / "01_整理结果",
        VERSION2 / "02_图",
        VERSION2 / "03_代码",
        VERSION3 / "01_从11复制的原始求解目录",
        VERSION3 / "02_整理结果",
        VERSION3 / "03_图",
        VERSION3 / "04_代码",
    ]:
        path.mkdir(parents=True, exist_ok=True)


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


def metric_values(rows: list[dict[str, object]], metric: str) -> np.ndarray:
    index = {lam: i for i, lam in enumerate(LAMBDA_ORDER)}
    arr = np.full(len(LAMBDA_ORDER), np.nan)
    for row in rows:
        lam = str(row["lambda"])
        if lam in index:
            arr[index[lam]] = float(row[metric])
    return arr


def plot_single_version(rows: list[dict[str, object]], fig_dir: Path, prefix: str) -> None:
    set_style()
    x = np.arange(len(LAMBDA_ORDER))
    specs = [
        ("imp_mean", r"$\Delta_{\mathrm{cost}}$ (\%)", "mean"),
        ("imp_std", r"$\Delta_{\mathrm{std}}$ (\%)", "std"),
        ("imp_max", r"$\Delta_{\max}$ (\%)", "max"),
        ("avg_selected", "ANC", "selected"),
    ]
    for metric, ylabel, name in specs:
        fig, ax = plt.subplots(figsize=(6.6, 4.2))
        ax.plot(
            x,
            metric_values(rows, metric),
            color=COLOR,
            linestyle=LINESTYLE,
            marker=MARKER,
            linewidth=2.0,
            markersize=6.5,
            markerfacecolor=COLOR,
            markeredgecolor="white",
            markeredgewidth=1.0,
            label="B = 0.1",
        )
        ax.set_xlabel(r"$\lambda$")
        ax.set_ylabel(ylabel)
        ax.set_xticks(x)
        ax.set_xticklabels(LAMBDA_ORDER, rotation=35, ha="right")
        ax.grid(True, linestyle="--", alpha=0.35)
        ax.legend(frameon=False, loc="lower right", handlelength=2.6)
        fig.tight_layout()
        fig.savefig(fig_dir / f"{prefix}_{name}.pdf", bbox_inches="tight")
        fig.savefig(fig_dir / f"{prefix}_{name}.png", dpi=600, bbox_inches="tight")
        plt.close(fig)

    fig, axes = plt.subplots(2, 2, figsize=(10.6, 7.4))
    axes = axes.flatten()
    for ax, (metric, ylabel, _) in zip(axes, specs):
        ax.plot(
            x,
            metric_values(rows, metric),
            color=COLOR,
            linestyle=LINESTYLE,
            marker=MARKER,
            linewidth=2.0,
            markersize=6.0,
            markerfacecolor=COLOR,
            markeredgecolor="white",
            markeredgewidth=1.0,
            label="B = 0.1",
        )
        ax.set_xlabel(r"$\lambda$")
        ax.set_ylabel(ylabel)
        ax.set_xticks(x)
        ax.set_xticklabels(LAMBDA_ORDER, rotation=35, ha="right")
        ax.grid(True, linestyle="--", alpha=0.35)
        ax.legend(frameon=False, loc="lower right", handlelength=2.6)
    fig.tight_layout()
    fig.savefig(fig_dir / f"{prefix}_combined.pdf", bbox_inches="tight")
    fig.savefig(fig_dir / f"{prefix}_combined.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def render_version1_note(fig_dir: Path) -> None:
    plt.rcParams.update({"font.family": "Microsoft YaHei", "axes.unicode_minus": False, "pdf.fonttype": 42, "ps.fonttype": 42})
    text = (
        "版本1：所有sample都直接入模，不管权重大小。\n\n"
        "问题：B=0.1时会出现极小权重，\n"
        "从而使 1/sqrt(weight) 特别大，\n"
        "导致DRO模型数值问题，部分求解失败。\n\n"
        "因此这一版本没有保留下来可用于正式比较的完整曲线结果。"
    )
    fig, ax = plt.subplots(figsize=(8.2, 4.8))
    ax.axis("off")
    ax.text(0.03, 0.92, text, va="top", ha="left", fontsize=13)
    fig.tight_layout()
    fig.savefig(fig_dir / "版本1_数值问题说明.pdf", bbox_inches="tight")
    fig.savefig(fig_dir / "版本1_数值问题说明.png", dpi=300, bbox_inches="tight")
    plt.close(fig)


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def copy_version3_raw() -> None:
    dst_root = VERSION3 / "01_从11复制的原始求解目录"
    for src in sorted(NEW_RAW_ROOT.glob("RCSAA_W50_k1=1_raw_C0.10_lambda*")):
        dst = dst_root / src.name
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(src, dst)


def write_code_stubs() -> None:
    code_v1 = '''# 版本1说明图生成代码
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).resolve().parent.parent
FIG_DIR = HERE / "02_图"
FIG_DIR.mkdir(parents=True, exist_ok=True)

text = (
    "版本1：所有sample都直接入模，不管权重大小。\\n\\n"
    "问题：B=0.1时会出现极小权重，\\n"
    "从而使 1/sqrt(weight) 特别大，\\n"
    "导致DRO模型数值问题，部分求解失败。\\n\\n"
    "因此这一版本没有保留下来可用于正式比较的完整曲线结果。"
)

fig, ax = plt.subplots(figsize=(8.2, 4.8))
ax.axis("off")
ax.text(0.03, 0.92, text, va="top", ha="left", fontsize=13)
fig.tight_layout()
fig.savefig(FIG_DIR / "版本1_数值问题说明.png", dpi=300, bbox_inches="tight")
'''
    write_text(VERSION1 / "03_代码" / "render_version1_note.py", code_v1)

    common_plot = '''from pathlib import Path
import csv, math
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

LAMBDA_ORDER = ["0","0.01","0.05","0.1","1","5","10","50","100","300","500","1000","10000","50000","100000"]
COLOR = "#7A1E6C"

def read_csv(path):
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))

def parse_float(v):
    t = "" if v is None else str(v).strip()
    if t == "" or t.lower() == "nan":
        return math.nan
    return float(t)

def values(rows, key):
    idx = {lam:i for i, lam in enumerate(LAMBDA_ORDER)}
    arr = np.full(len(LAMBDA_ORDER), np.nan)
    for r in rows:
        lam = str(r["lambda"])
        if lam in idx:
            arr[idx[lam]] = parse_float(r[key])
    return arr

def draw(input_csv, fig_dir, prefix):
    rows = read_csv(input_csv)
    x = np.arange(len(LAMBDA_ORDER))
    specs = [
        ("imp_mean", r"$\\Delta_{\\mathrm{cost}}$ (\\%)", "mean"),
        ("imp_std", r"$\\Delta_{\\mathrm{std}}$ (\\%)", "std"),
        ("imp_max", r"$\\Delta_{\\max}$ (\\%)", "max"),
        ("avg_selected", "ANC", "selected"),
    ]
    plt.rcParams.update({
        "font.family":"Times New Roman",
        "mathtext.fontset":"stix",
        "axes.spines.top":False,
        "axes.spines.right":False,
        "pdf.fonttype":42,
        "ps.fonttype":42,
    })
    for key, ylabel, name in specs:
        fig, ax = plt.subplots(figsize=(6.6, 4.2))
        ax.plot(x, values(rows, key), color=COLOR, marker="o", linewidth=2.0, markersize=6.5, label="B = 0.1")
        ax.set_xlabel(r"$\\lambda$")
        ax.set_ylabel(ylabel)
        ax.set_xticks(x)
        ax.set_xticklabels(LAMBDA_ORDER, rotation=35, ha="right")
        ax.grid(True, linestyle="--", alpha=0.35)
        ax.legend(frameon=False, loc="lower right")
        fig.tight_layout()
        fig.savefig(fig_dir / f"{prefix}_{name}.png", dpi=600, bbox_inches="tight")
'''

    code_v2 = common_plot + '''
HERE = Path(__file__).resolve().parent.parent
draw(HERE / "01_整理结果" / "B=0.1_版本2_汇总.csv", HERE / "02_图", "version2_b01")
'''
    write_text(VERSION2 / "03_代码" / "plot_version2_b01.py", code_v2)

    code_v3 = common_plot + '''
HERE = Path(__file__).resolve().parent.parent
draw(HERE / "02_整理结果" / "B=0.1_版本3_汇总.csv", HERE / "03_图", "version3_b01")
'''
    write_text(VERSION3 / "04_代码" / "plot_version3_b01.py", code_v3)


def write_notes() -> None:
    top_note = """这里整理 B=0.1 的三种方案迭代。

1、最早期所有sample都用，不管权重大小，此时可能权重很小很小，导致模型里边1/根号(权重)以后特别大导致数值问题。这就导致了B=0.1的时候某些求解出错。
2、基于这个想到了把那些sample很接近0的直接删掉，不作为训练集，但这个修改会出问题，即当lambda变大的时候，是在所有的sample里边重新分配样本权重，寻找最坏情况。而这里的做法导致了某些sample被强制设置为0权重，导致结果相对较好，这也是之前那个图里边为什么最终只有B=0.1的结果没有收敛到和别的B的值一样的。
3、还是第一种做法，所有sample都应出现在模型里边，对很小的权重那些做一个数值处理，小权重认为是1e-8的权重，现在的结果基本就对了。

B>=0.5的那些不会有这个问题，权重虽然小但基本不会有数值问题，所以直接用第一种的时候没有事情。
"""
    write_text(COMPARE_ROOT / "说明.txt", top_note)

    write_text(
        VERSION1 / "01_说明" / "说明.txt",
        "版本1对应最早期做法：所有sample都进DRO模型，不管权重多小。\n\n"
        "B=0.1时会出现极小权重，导致 1/sqrt(weight) 特别大，从而出现数值问题，部分求解失败。这里没有保留下来完整可用的正式结果曲线，因此只保留说明图，不把它当正式结果使用。\n",
    )
    write_text(
        VERSION2 / "01_整理结果" / "说明.txt",
        "版本2对应旧做法：把很接近0的sample直接删掉，不作为训练集。这里的结果就是之前09目录下用于画图的旧结果。这个版本的问题是把某些sample强制设成了0权重，所以当lambda变大时，B=0.1的结果会相对偏好。\n",
    )
    write_text(
        VERSION3 / "02_整理结果" / "说明.txt",
        "版本3对应当前新做法：所有sample都应出现在模型里边，对很小的权重做数值处理，凡是<=1e-8的权重都按1e-8处理，然后重新归一化。这里的结果来自11目录的重跑，并复制到09目录下整理。\n",
    )


def compare_rows(old_rows: list[dict[str, object]], new_rows: list[dict[str, object]]) -> list[dict[str, object]]:
    old_map = {str(r["lambda"]): r for r in old_rows}
    new_map = {str(r["lambda"]): r for r in new_rows}
    out = []
    for lam in LAMBDA_ORDER:
        if lam not in old_map or lam not in new_map:
            continue
        o = old_map[lam]
        n = new_map[lam]
        out.append(
            {
                "lambda": lam,
                "old_mean_realized_obj": o["mean_realized_obj"],
                "new_mean_realized_obj": n["mean_realized_obj"],
                "delta_mean_realized_obj": float(n["mean_realized_obj"]) - float(o["mean_realized_obj"]),
                "old_imp_mean": o["imp_mean"],
                "new_imp_mean": n["imp_mean"],
                "delta_imp_mean": float(n["imp_mean"]) - float(o["imp_mean"]),
                "old_avg_selected": o["avg_selected"],
                "new_avg_selected": n["avg_selected"],
                "delta_avg_selected": float(n["avg_selected"]) - float(o["avg_selected"]),
                "old_max_realized_obj": o["max_realized_obj"],
                "new_max_realized_obj": n["max_realized_obj"],
            }
        )
    return out


def main() -> None:
    ensure_dir_structure()
    write_notes()

    old_rows = version2_rows()
    new_rows = version3_rows(old_rows)

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
    write_csv(VERSION2 / "01_整理结果" / "B=0.1_版本2_汇总.csv", old_rows, fieldnames)
    write_csv(VERSION3 / "02_整理结果" / "B=0.1_版本3_汇总.csv", new_rows, fieldnames)
    write_csv(
        COMPARE_ROOT / "版本2_vs_版本3_B=0.1_对比.csv",
        compare_rows(old_rows, new_rows),
        [
            "lambda",
            "old_mean_realized_obj",
            "new_mean_realized_obj",
            "delta_mean_realized_obj",
            "old_imp_mean",
            "new_imp_mean",
            "delta_imp_mean",
            "old_avg_selected",
            "new_avg_selected",
            "delta_avg_selected",
            "old_max_realized_obj",
            "new_max_realized_obj",
        ],
    )

    plot_single_version(old_rows, VERSION2 / "02_图", "version2_b01")
    plot_single_version(new_rows, VERSION3 / "03_图", "version3_b01")
    render_version1_note(VERSION1 / "02_图")
    copy_version3_raw()
    write_code_stubs()


if __name__ == "__main__":
    main()

