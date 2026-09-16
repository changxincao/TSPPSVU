# -*- coding: utf-8 -*-
"""
合成周度需求生成器（仅保留历史总需求覆盖前90%的线路）。

目标：让“过去k1周的share拼接”作为协变量时，thetaDist 更稳定地代理 demandDist，
从而显著降低 corrTheta_demandDist<0 的比例（negTD_rate）。

生成思想（更直接、可控）：
1) 从真实周 share 中抽取 K 个“原型结构”(prototypes)；
2) 结构状态 r_t 按高持久性的 Markov 链转移（制造结构阶段性一致）；
3) share 做平滑自回归：s_t = normalize((1-α) s_{t-1} + α proto_{r_t} + noise)，
   这样 past shares 的距离天然会预测 current share 的距离；
4) 为避免尺度干扰，默认固定总量 T_t（可改为 AR(1)）。

输出：
- analysis/合成需求_周度_仅前90%需求线路.csv
- analysis/合成需求_周度_生成参数说明.txt
"""

from __future__ import annotations

import csv
import math
from datetime import datetime, timedelta
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np


ROOT = Path(".")
HISTORY_CSV = ROOT / "fFreight_with_indicators_and_holidays.csv"
OUT_DIR = ROOT / "analysis"
OUT_DIR.mkdir(exist_ok=True)

AGG_DAYS = 7
TOP_LANE_COVERAGE = 0.90

SEED = 7

# Prototype + smoothing parameters
K_PROTOS = 6
ALPHA = 0.30  # larger => faster move toward current prototype
NOISE_STD = 0.015  # share noise (simplex-projected)
P_STAY = 0.90  # Markov persistence of regime

# Total demand
USE_CONSTANT_TOTAL = True
TOTAL_LEVEL = None  # if None, use mean(total) from real weekly totals

EPS = 1e-12

OUT_WEEKLY_WIDE = OUT_DIR / f"合成需求_周度_仅前{int(TOP_LANE_COVERAGE*100)}%需求线路.csv"
OUT_NOTE = OUT_DIR / "合成需求_周度_生成参数说明.txt"


def parse_date(s: str):
    return datetime.strptime(s.strip(), "%Y/%m/%d").date()


def load_weekly_periods(csv_path: Path, agg_days: int = 7) -> Tuple[List[str], List[dict]]:
    rows = []
    cities = set()
    with csv_path.open(encoding="utf-8-sig", newline="") as f:
        r = csv.reader(f)
        _ = next(r)
        for row in r:
            if not row:
                continue
            d = parse_date(row[0])
            city = row[5].strip()
            wkg = float(row[7].strip() or 0)
            rows.append((d, city, wkg))
            cities.add(city)

    lane_names = sorted(cities)
    idx = {c: i for i, c in enumerate(lane_names)}
    J = len(lane_names)

    by_day: Dict = {}
    for d, city, wkg in rows:
        rec = by_day.setdefault(d, np.zeros(J, dtype=float))
        rec[idx[city]] += wkg

    min_d = min(by_day)
    max_d = max(by_day)
    all_days = []
    cur = min_d
    while cur <= max_d:
        if cur in by_day:
            all_days.append((cur, by_day[cur]))
        else:
            all_days.append((cur, np.zeros(J, dtype=float)))
        cur += timedelta(days=1)

    periods = []
    for start in range(0, len(all_days) - agg_days + 1, agg_days):
        end = start + agg_days
        dem = np.zeros(J, dtype=float)
        for k in range(start, end):
            dem += np.floor(all_days[k][1]).astype(float)
        periods.append({"tIndex": len(periods), "demand": dem})
    return lane_names, periods


def select_top_lanes(periods: List[dict], coverage: float) -> List[int]:
    totals = np.zeros_like(periods[0]["demand"], dtype=float)
    for p in periods:
        totals += p["demand"]
    total_sum = float(np.sum(totals))
    if total_sum <= EPS:
        return list(range(len(totals)))
    order = np.argsort(-totals)
    keep = []
    cum = 0.0
    for j in order:
        keep.append(int(j))
        cum += float(totals[j])
        if cum / total_sum >= coverage:
            break
    keep.sort()
    return keep


def normalize_to_simplex(x: np.ndarray) -> np.ndarray:
    y = np.maximum(x, EPS)
    s = float(np.sum(y))
    if s <= EPS:
        return np.ones_like(y) / len(y)
    return y / s


def demand_to_share(d: np.ndarray) -> np.ndarray:
    tot = float(np.sum(d))
    if tot <= EPS:
        return np.ones_like(d) / len(d)
    return d / tot


def farthest_point_sampling(X: np.ndarray, k: int, rng: np.random.Generator) -> List[int]:
    # Pick diverse prototypes without external libs.
    n = X.shape[0]
    if k >= n:
        return list(range(n))
    chosen = [int(rng.integers(0, n))]
    dist2 = np.full(n, np.inf, dtype=float)
    for _ in range(1, k):
        c = X[chosen[-1]]
        d2 = np.sum((X - c) ** 2, axis=1)
        dist2 = np.minimum(dist2, d2)
        nxt = int(np.argmax(dist2))
        chosen.append(nxt)
    return chosen


def make_markov_states(T: int, K: int, p_stay: float, rng: np.random.Generator) -> np.ndarray:
    r = np.zeros(T, dtype=int)
    r[0] = int(rng.integers(0, K))
    for t in range(1, T):
        if rng.random() < p_stay:
            r[t] = r[t - 1]
        else:
            # switch to a different state
            cand = int(rng.integers(0, K - 1))
            r[t] = cand if cand < r[t - 1] else cand + 1
    return r


def main():
    rng = np.random.default_rng(SEED)

    lane_names, periods = load_weekly_periods(HISTORY_CSV, AGG_DAYS)
    keep_idx = select_top_lanes(periods, TOP_LANE_COVERAGE)
    lane_names = [lane_names[i] for i in keep_idx]
    for p in periods:
        p["demand"] = p["demand"][keep_idx]

    D_real = np.vstack([p["demand"] for p in periods])  # (T, J)
    Tn, J = D_real.shape
    totals_real = D_real.sum(axis=1)
    S_real = np.vstack([demand_to_share(D_real[t]) for t in range(Tn)])

    # Select K prototypes from real shares (diverse)
    proto_idx = farthest_point_sampling(S_real, K_PROTOS, rng)
    protos = np.vstack([S_real[i] for i in proto_idx])  # (K, J)

    # Markov regime sequence
    states = make_markov_states(Tn, K_PROTOS, P_STAY, rng)

    # Total sequence
    if TOTAL_LEVEL is None:
        total_level = float(np.mean(totals_real))
    else:
        total_level = float(TOTAL_LEVEL)

    if USE_CONSTANT_TOTAL:
        T_syn = np.ones(Tn, dtype=float) * total_level
    else:
        # simple AR(1) around mean (still no exogenous)
        rho = 0.85
        sigma = float(np.std(np.log(totals_real + EPS), ddof=0)) * 0.25
        logT = np.zeros(Tn, dtype=float)
        logT[0] = math.log(total_level + EPS)
        for t in range(1, Tn):
            logT[t] = rho * logT[t - 1] + (1 - rho) * math.log(total_level + EPS) + rng.normal(0.0, sigma)
        T_syn = np.exp(logT)

    # Share dynamics (directly aligned with lagged-share covariates)
    S_syn = np.zeros((Tn, J), dtype=float)
    S_syn[0] = normalize_to_simplex(S_real[0])
    for t in range(1, Tn):
        target = protos[states[t]]
        noise = rng.normal(0.0, NOISE_STD, size=J)
        s = (1.0 - ALPHA) * S_syn[t - 1] + ALPHA * target + noise
        S_syn[t] = normalize_to_simplex(s)

    D_syn = S_syn * T_syn[:, None]

    with OUT_WEEKLY_WIDE.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["weekIndex"] + lane_names)
        for t in range(Tn):
            w.writerow([t] + [float(x) for x in D_syn[t]])

    note = []
    note.append("合成需求生成参数说明")
    note.append("")
    note.append(f"输入历史文件: {HISTORY_CSV}")
    note.append(f"周聚合天数: {AGG_DAYS}")
    note.append(f"仅保留覆盖前{int(TOP_LANE_COVERAGE*100)}%总需求线路数: {J}")
    note.append(f"随机种子: {SEED}")
    note.append("")
    note.append("结构生成：从真实周share抽取K个原型(prototypes)，按Markov链切换，并做share平滑自回归。")
    note.append(f"K_PROTOS={K_PROTOS}, ALPHA={ALPHA}, NOISE_STD={NOISE_STD}, P_STAY={P_STAY}")
    note.append(f"prototype_week_indices={proto_idx}")
    note.append("")
    note.append("总量：默认固定为真实周总量均值，以减少尺度对距离相关性的干扰。")
    note.append(f"USE_CONSTANT_TOTAL={USE_CONSTANT_TOTAL}, TOTAL_LEVEL={total_level:.6f}")
    OUT_NOTE.write_text("\n".join(note), encoding="utf-8-sig")

    print(OUT_WEEKLY_WIDE)
    print(OUT_NOTE)


if __name__ == "__main__":
    main()

