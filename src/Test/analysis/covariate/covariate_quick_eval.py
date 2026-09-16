# -*- coding: utf-8 -*-
"""
协变量候选的快速评估脚本。

目的：
1. 不求解模型，只比较不同协变量设计是否更能代理需求相似性；
2. 对每个 k1、每个设计、每个 rolling trial 输出诊断指标；
3. 汇总成类似 global_trials / global_summary 的 trial 级与设计级表格。

核心输出：
- corrTheta_demandDist
- corrW_demandDist
- corrW_thetaDist
- ESS
- 近邻质量 NN ratio
- 大头 line 重合度
"""
from __future__ import annotations
import csv
import math
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Dict, Tuple, Callable, Optional

import argparse
import numpy as np
import pandas as pd

ROOT = Path('.')
DEFAULT_HISTORY_CSV = ROOT / 'fFreight_with_indicators_and_holidays.csv'
OUT_DIR = ROOT / 'analysis'
OUT_DIR.mkdir(exist_ok=True)

W = 24
K1_LIST = [1, 2, 3, 4]
TOP_THRESHOLDS = [0.8, 0.9]
NN_KS = [5, 10]

APPLY_TOP_LANES_FILTER = True
TOP_LANE_COVERAGE = 0.90  # Keep lanes that cover this fraction of total historical demand.


def out_paths(suffix: str):
    out_tag = suffix or ''
    if APPLY_TOP_LANES_FILTER and '_仅前' not in out_tag:
        out_tag = out_tag + f'_仅前{int(TOP_LANE_COVERAGE*100)}%需求线路'
    return (
        OUT_DIR / f'协变量快速评估_逐trial{out_tag}.csv',
        OUT_DIR / f'协变量快速评估_方案汇总{out_tag}.csv',
        OUT_DIR / f'协变量快速评估_结论{out_tag}.txt',
    )


def parse_date(s: str):
    return datetime.strptime(s.strip(), '%Y/%m/%d').date()


def load_periods(csv_path: Path, agg_days: int = 7):
    rows = []
    cities = set()
    with csv_path.open(encoding='utf-8-sig', newline='') as f:
        r = csv.reader(f)
        header = next(r)
        for row in r:
            if not row:
                continue
            d = parse_date(row[0])
            city = row[5].strip()
            wkg = float(row[7].strip() or 0)
            tsi = float(row[10].strip() or 0)
            pce = float(row[11].strip() or 0)
            wei = float(row[12].strip() or 0)
            hol = int(float(row[13].strip() or 0))
            rows.append((d, city, wkg, tsi, pce, wei, hol))
            cities.add(city)

    lane_names = sorted(cities)
    idx = {c: i for i, c in enumerate(lane_names)}
    J = len(lane_names)

    by_day: Dict = {}
    for d, city, wkg, tsi, pce, wei, hol in rows:
        rec = by_day.setdefault(d, {'demand': np.zeros(J), 'tsi': tsi, 'pce': pce, 'wei': wei, 'hol': hol})
        rec['demand'][idx[city]] += wkg

    min_d = min(by_day)
    max_d = max(by_day)
    all_days = []
    cur = min_d
    last_tsi = by_day[min_d]['tsi']
    last_pce = by_day[min_d]['pce']
    last_wei = by_day[min_d]['wei']
    while cur <= max_d:
        if cur in by_day:
            rec = by_day[cur]
            last_tsi, last_pce, last_wei = rec['tsi'], rec['pce'], rec['wei']
            all_days.append((cur, rec['demand'], rec['tsi'], rec['pce'], rec['wei'], rec['hol']))
        else:
            wd = cur.weekday()
            hol = 1 if wd >= 5 else 0
            all_days.append((cur, np.zeros(J), last_tsi, last_pce, last_wei, hol))
        cur += timedelta(days=1)

    periods = []
    for start in range(0, len(all_days) - agg_days + 1, agg_days):
        end = start + agg_days
        dem = np.zeros(J)
        hol = 0
        tsi = pce = wei = 0.0
        for k in range(start, end):
            dem += np.floor(all_days[k][1]).astype(float)
            tsi += all_days[k][2]
            pce += all_days[k][3]
            wei += all_days[k][4]
            hol += all_days[k][5]
        periods.append({
            'tIndex': len(periods),
            'demand': dem,
            'holidayCount': hol,
            'avgFreight': tsi / agg_days,
            'avgConsumption': pce / agg_days,
            'avgWEI': wei / agg_days,
        })
    return lane_names, periods


def load_periods_weekly_wide(csv_path: Path):
    df = pd.read_csv(csv_path, encoding='utf-8-sig')
    if 'weekIndex' not in df.columns:
        raise ValueError('weekly_wide 需要包含 weekIndex 列')
    lane_names = [c for c in df.columns if c != 'weekIndex']
    periods = []
    for i, row in df.iterrows():
        dem = row[lane_names].to_numpy(dtype=float)
        periods.append({
            'tIndex': int(row['weekIndex']),
            'demand': dem,
            'holidayCount': 0.0,
            'avgFreight': 0.0,
            'avgConsumption': 0.0,
            'avgWEI': 0.0,
        })
    return lane_names, periods


def select_top_lanes(periods: List[dict], coverage: float) -> List[int]:
    totals = np.zeros_like(periods[0]['demand'], dtype=float)
    for p in periods:
        totals += p['demand']
    total_sum = float(np.sum(totals))
    if total_sum <= 1e-12:
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


def demand_share(x: np.ndarray) -> np.ndarray:
    s = float(np.sum(x))
    if s <= 1e-12:
        return np.zeros_like(x)
    return x / s


def head_sparse_share(x: np.ndarray, threshold: float) -> np.ndarray:
    share = demand_share(x)
    if np.sum(share) <= 1e-12:
        return np.zeros_like(x)
    order = np.argsort(-share)
    out = np.zeros_like(share)
    cum = 0.0
    for rank, j in enumerate(order):
        if rank == 0 or cum < threshold:
            out[j] = share[j]
            cum += share[j]
        else:
            break
    return out


def head_set(x: np.ndarray, threshold: float) -> set:
    sparse = head_sparse_share(x, threshold)
    return {int(i) for i in np.where(sparse > 0)[0]}


def build_old_baseline(periods, k1: int):
    feats = []
    valid_t = []
    for tt in range(k1, len(periods)):
        parts = []
        for lag in range(1, k1 + 1):
            parts.append(float(np.sum(periods[tt - lag]['demand'])))
        parts.extend([
            float(periods[tt]['holidayCount']),
            float(periods[tt]['avgFreight']),
            float(periods[tt]['avgConsumption']),
            float(periods[tt]['avgWEI']),
        ])
        feats.append(np.array(parts, dtype=float))
        valid_t.append(tt)
    return np.vstack(feats), valid_t


def build_concat_vectors(periods, k1: int, vec_fn: Callable[[np.ndarray], np.ndarray]):
    feats = []
    valid_t = []
    for tt in range(k1, len(periods)):
        blocks = []
        for lag in range(1, k1 + 1):
            blocks.append(vec_fn(periods[tt - lag]['demand']))
        feats.append(np.concatenate(blocks))
        valid_t.append(tt)
    return np.vstack(feats), valid_t


def build_union_head_sets(periods, k1: int, threshold: float):
    feats = []
    valid_t = []
    for tt in range(k1, len(periods)):
        s = set()
        for lag in range(1, k1 + 1):
            s |= head_set(periods[tt - lag]['demand'], threshold)
        feats.append(s)
        valid_t.append(tt)
    return feats, valid_t


def zscore_fit_transform(X: np.ndarray):
    mu = X.mean(axis=0)
    sd = X.std(axis=0)
    sd = np.where(sd < 1e-12, 1.0, sd)
    Z = (X - mu) / sd
    return Z, mu, sd


def zscore_apply(X: np.ndarray, mu: np.ndarray, sd: np.ndarray):
    sd2 = np.where(sd < 1e-12, 1.0, sd)
    return (X - mu) / sd2


def pca_project(X: np.ndarray, n_comp: int):
    Z, mu, sd = zscore_fit_transform(X)
    U, S, Vt = np.linalg.svd(Z, full_matrices=False)
    comps = Vt[:n_comp].T
    Xp = Z @ comps
    return Xp, mu, sd, comps


def pca_apply(X: np.ndarray, mu: np.ndarray, sd: np.ndarray, comps: np.ndarray):
    Z = zscore_apply(X, mu, sd)
    return Z @ comps


def pearson(x: np.ndarray, y: np.ndarray) -> float:
    mask = np.isfinite(x) & np.isfinite(y)
    x = x[mask]
    y = y[mask]
    if len(x) < 2:
        return float('nan')
    sx = np.std(x)
    sy = np.std(y)
    if sx < 1e-12 or sy < 1e-12:
        return float('nan')
    return float(np.corrcoef(x, y)[0, 1])


def summarize(xs: List[float]):
    arr = np.array([x for x in xs if np.isfinite(x)], dtype=float)
    if len(arr) == 0:
        return dict(mean=np.nan, std=np.nan, min=np.nan, p20=np.nan, p50=np.nan, p80=np.nan, p95=np.nan, max=np.nan)
    return dict(
        mean=float(arr.mean()),
        std=float(arr.std(ddof=0)),
        min=float(np.quantile(arr, 0.0)),
        p20=float(np.quantile(arr, 0.2)),
        p50=float(np.quantile(arr, 0.5)),
        p80=float(np.quantile(arr, 0.8)),
        p95=float(np.quantile(arr, 0.95)),
        max=float(np.quantile(arr, 1.0)),
    )


def normalized_exp_weights(dist: np.ndarray) -> np.ndarray:
    pos = dist[np.isfinite(dist) & (dist > 1e-12)]
    scale = float(np.median(pos)) if len(pos) else 1.0
    if scale < 1e-12:
        scale = 1.0
    z = np.exp(-dist / scale)
    s = float(np.sum(z))
    if s <= 1e-12:
        return np.ones_like(z) / len(z)
    return z / s


def l2_demand(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.linalg.norm(a - b))


def avg_jaccard(neighbor_indices: List[int], sample_head_sets: List[set], test_head_set: set) -> float:
    vals = []
    for i in neighbor_indices:
        s = sample_head_sets[i]
        inter = len(s & test_head_set)
        union = len(s | test_head_set)
        vals.append(inter / union if union else 1.0)
    return float(np.mean(vals)) if vals else float('nan')


def build_designs(periods, k1: int):
    designs = {}
    X_old, valid_t = build_old_baseline(periods, k1)
    designs['old_baseline_current'] = ('vector', X_old, valid_t, 'euclidean_zscore')

    # 方案：直接用“需求向量”作为协变量（规模+结构都包含，维度较高）。
    X_raw_demand, valid_t2 = build_concat_vectors(periods, k1, lambda d: d.astype(float))
    designs['allline_demand'] = ('vector', X_raw_demand, valid_t2, 'euclidean_zscore')

    # 方案：对需求做 log1p 压缩后再拼接，减少极端大需求对距离的主导（仍然是规模+结构）。
    X_log_demand, valid_t2b = build_concat_vectors(periods, k1, lambda d: np.log1p(d.astype(float)))
    designs['allline_log1p_demand'] = ('vector', X_log_demand, valid_t2b, 'euclidean_zscore')

    X_raw_share, valid_t3 = build_concat_vectors(periods, k1, demand_share)
    designs['allline_share'] = ('vector', X_raw_share, valid_t3, 'euclidean_zscore')

    for n_comp in [3, 4, 5]:
        pname = f'allline_share_pca{n_comp}'
        designs[pname] = ('vector_pca', X_raw_share, valid_t3, f'pca{n_comp}', n_comp)

    for thr in TOP_THRESHOLDS:
        X_sparse, valid_t4 = build_concat_vectors(periods, k1, lambda d, thr=thr: head_sparse_share(d, thr))
        name = f'head{int(thr*100)}_sparse_share'
        designs[name] = ('vector', X_sparse, valid_t4, 'euclidean_zscore')

        set_union, valid_t5 = build_union_head_sets(periods, k1, thr)
        name2 = f'head{int(thr*100)}_union_set_jaccard'
        designs[name2] = ('set', set_union, valid_t5, 'jaccard')

        for n_comp in [3, 4, 5]:
            pname = f'head{int(thr*100)}_sparse_share_pca{n_comp}'
            designs[pname] = ('vector_pca', X_sparse, valid_t4, f'pca{n_comp}', n_comp)

    for n_comp in [3, 4, 5]:
        pname = f'allline_demand_pca{n_comp}'
        designs[pname] = ('vector_pca', X_raw_demand, valid_t2, f'pca{n_comp}', n_comp)

    for n_comp in [3, 4, 5]:
        pname = f'allline_log1p_demand_pca{n_comp}'
        designs[pname] = ('vector_pca', X_log_demand, valid_t2b, f'pca{n_comp}', n_comp)

    return designs


def evaluate_design(periods, k1: int, design_name: str, design_type: str, feats, valid_t: List[int], dist_label: str, n_comp: int | None = None):
    sample_periods = valid_t
    demand_samples = [periods[t]['demand'] for t in sample_periods]
    head80_sets = [head_set(periods[t]['demand'], 0.8) for t in sample_periods]
    head90_sets = [head_set(periods[t]['demand'], 0.9) for t in sample_periods]

    rows = []
    for test_pos in range(W, len(sample_periods)):
        train_idx = list(range(test_pos - W, test_pos))
        test_idx = test_pos
        d_test = demand_samples[test_idx]

        if design_type in ('vector', 'vector_pca'):
            X_train = np.vstack([feats[i] for i in train_idx])
            x_now = feats[test_idx].reshape(1, -1)
            if design_type == 'vector':
                Z_train, mu, sd = zscore_fit_transform(X_train)
                z_now = zscore_apply(x_now, mu, sd)[0]
            else:
                Xp_train, mu, sd, comps = pca_project(X_train, n_comp)
                Z_train = Xp_train
                z_now = pca_apply(x_now, mu, sd, comps)[0]
            theta_dist = np.linalg.norm(Z_train - z_now, axis=1)
            theta_dim = Z_train.shape[1]
        else:
            set_now = feats[test_idx]
            theta_dist = []
            for i in train_idx:
                s = feats[i]
                inter = len(s & set_now)
                union = len(s | set_now)
                theta_dist.append(1.0 - (inter / union if union else 1.0))
            theta_dist = np.array(theta_dist, dtype=float)
            theta_dim = np.nan

        demand_dist = np.array([l2_demand(demand_samples[i], d_test) for i in train_idx], dtype=float)
        w = normalized_exp_weights(theta_dist)
        sumW2 = float(np.sum(w * w))
        ess = 1.0 / sumW2 if sumW2 > 1e-12 else float('nan')
        sorted_w = np.sort(w)[::-1]
        top1 = float(sorted_w[0]) if len(sorted_w) else np.nan
        top5 = float(np.sum(sorted_w[:5])) if len(sorted_w) else np.nan
        meanw = float(np.mean(w)) if len(w) else np.nan
        max_over_mean = top1 / meanw if meanw and np.isfinite(meanw) and meanw > 0 else np.nan

        ord_idx = np.argsort(theta_dist)
        nn5 = ord_idx[:5].tolist()
        nn10 = ord_idx[:10].tolist()
        train_head80 = [head80_sets[i] for i in train_idx]
        train_head90 = [head90_sets[i] for i in train_idx]
        test_head80 = head80_sets[test_idx]
        test_head90 = head90_sets[test_idx]
        dd_mean = float(np.mean(demand_dist)) if len(demand_dist) else np.nan
        nn5_ratio = float(np.mean(demand_dist[nn5]) / dd_mean) if dd_mean > 1e-12 else np.nan
        nn10_ratio = float(np.mean(demand_dist[nn10]) / dd_mean) if dd_mean > 1e-12 else np.nan

        row = {
            'design': design_name,
            'k1': k1,
            'trialId': test_pos - W,
            'testSamplePos': test_idx,
            'testPeriodIndex': sample_periods[test_idx],
            'trainSize': len(train_idx),
            'thetaDim': theta_dim,
            'distanceType': dist_label,
            'sumW': float(np.sum(w)),
            'sumW2': sumW2,
            'ESS': ess,
            'top1W': top1,
            'top5Wsum': top5,
            'maxW_over_meanW': max_over_mean,
            'thetaDist_mean': float(np.mean(theta_dist)),
            'thetaDist_median': float(np.median(theta_dist)),
            'thetaDist_min': float(np.min(theta_dist)),
            'thetaDist_max': float(np.max(theta_dist)),
            'demandDist_mean': float(np.mean(demand_dist)),
            'demandDist_median': float(np.median(demand_dist)),
            'demandDist_min': float(np.min(demand_dist)),
            'demandDist_max': float(np.max(demand_dist)),
            'corrW_thetaDist': pearson(w, theta_dist),
            'corrW_demandDist': pearson(w, demand_dist),
            'corrTheta_demandDist': pearson(theta_dist, demand_dist),
            'neg_corrTheta_demandDist': int(np.isfinite(pearson(theta_dist, demand_dist)) and pearson(theta_dist, demand_dist) < 0),
            'neg_corrW_demandDist': int(np.isfinite(pearson(w, demand_dist)) and pearson(w, demand_dist) < 0),
            'nn5Ratio': nn5_ratio,
            'nn10Ratio': nn10_ratio,
            'nn5Head80Jaccard': avg_jaccard(nn5, train_head80, test_head80),
            'nn10Head80Jaccard': avg_jaccard(nn10, train_head80, test_head80),
            'nn5Head90Jaccard': avg_jaccard(nn5, train_head90, test_head90),
            'nn10Head90Jaccard': avg_jaccard(nn10, train_head90, test_head90),
        }
        rows.append(row)
    return rows


def build_summary(df: pd.DataFrame):
    summary_rows = []
    for (design, k1), g in df.groupby(['design', 'k1'], sort=False):
        row = {'design': design, 'k1': k1, 'nTrials': len(g)}
        for col, prefix in [
            ('corrTheta_demandDist', 'corrTD'),
            ('corrW_demandDist', 'corrWD'),
            ('corrW_thetaDist', 'corrWT'),
            ('ESS', 'ess'),
            ('nn5Ratio', 'nn5Ratio'),
            ('nn10Ratio', 'nn10Ratio'),
            ('nn5Head80Jaccard', 'nn5Head80Jac'),
            ('nn10Head80Jaccard', 'nn10Head80Jac'),
            ('nn5Head90Jaccard', 'nn5Head90Jac'),
            ('nn10Head90Jaccard', 'nn10Head90Jac'),
        ]:
            s = summarize(g[col].tolist())
            for k, v in s.items():
                row[f'{prefix}_{k}'] = v
        row['negTD_rate'] = float(np.nanmean(g['neg_corrTheta_demandDist']))
        row['negWD_rate'] = float(np.nanmean(g['neg_corrW_demandDist']))
        summary_rows.append(row)
    return pd.DataFrame(summary_rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--input', type=str, default=str(DEFAULT_HISTORY_CSV))
    ap.add_argument('--format', type=str, default='daily', choices=['daily', 'weekly_wide'])
    ap.add_argument('--out_suffix', type=str, default='')
    args = ap.parse_args()

    in_path = Path(args.input)
    if args.format == 'daily':
        lane_names, periods = load_periods(in_path)
    else:
        lane_names, periods = load_periods_weekly_wide(in_path)

    if APPLY_TOP_LANES_FILTER and len(periods) and len(periods[0]['demand']) > 0:
        keep_idx = select_top_lanes(periods, TOP_LANE_COVERAGE)
        lane_names = [lane_names[i] for i in keep_idx]
        for p in periods:
            p['demand'] = p['demand'][keep_idx]

    TRIAL_OUT, SUMMARY_OUT, TXT_OUT = out_paths(args.out_suffix)
    all_rows = []
    for k1 in K1_LIST:
        designs = build_designs(periods, k1)
        for design_name, spec in designs.items():
            if len(spec) == 4:
                design_type, feats, valid_t, dist_label = spec
                rows = evaluate_design(periods, k1, design_name, design_type, feats, valid_t, dist_label)
            else:
                design_type, feats, valid_t, dist_label, n_comp = spec
                rows = evaluate_design(periods, k1, design_name, design_type, feats, valid_t, dist_label, n_comp)
            all_rows.extend(rows)
    trial_df = pd.DataFrame(all_rows)
    summary_df = build_summary(trial_df)

    trial_df.to_csv(TRIAL_OUT, index=False, encoding='utf-8-sig')
    summary_df.to_csv(SUMMARY_OUT, index=False, encoding='utf-8-sig')

    # text summary: compare to old baseline for each k1
    lines = []
    lines.append('协变量快速评估结论')
    lines.append('')
    lines.append('口径：rolling W=24，k1 in {1,2,3,4}，不求解模型，只看协变量与 demandDist 的关系。')
    lines.append('重点指标：corrTheta_demandDist、corrW_demandDist、负相关比例、NN ratio、大头line重合度。')
    lines.append('')
    for k1 in K1_LIST:
        lines.append(f'[k1={k1}]')
        sub = summary_df[summary_df['k1'] == k1].copy()
        old = sub[sub['design'] == 'old_baseline_current'].iloc[0]
        lines.append('baseline(old_baseline_current): corrTD_p50=%.4f, negTD_rate=%.4f, corrWD_p50=%.4f, nn5Ratio_p50=%.4f' % (
            old['corrTD_p50'], old['negTD_rate'], old['corrWD_p50'], old['nn5Ratio_p50']))
        better = sub[(sub['corrTD_p50'] > old['corrTD_p50']) & (sub['negTD_rate'] < old['negTD_rate'])].copy()
        better = better.sort_values(['corrTD_p50', 'corrWD_p50'], ascending=[False, True]).head(8)
        lines.append('优于旧baseline的候选（按 corrTD_p50 和 negTD_rate 粗筛）：')
        if better.empty:
            lines.append('- 无明显同时改善的方案')
        else:
            for _, r in better.iterrows():
                lines.append('- %s: corrTD_p50=%.4f, negTD_rate=%.4f, corrWD_p50=%.4f, nn5Ratio_p50=%.4f, nn5Head80Jac_p50=%.4f' % (
                    r['design'], r['corrTD_p50'], r['negTD_rate'], r['corrWD_p50'], r['nn5Ratio_p50'], r['nn5Head80Jac_p50']))
        lines.append('')
    TXT_OUT.write_text('\n'.join(lines), encoding='utf-8-sig')
    print(TRIAL_OUT)
    print(SUMMARY_OUT)
    print(TXT_OUT)


if __name__ == '__main__':
    main()

