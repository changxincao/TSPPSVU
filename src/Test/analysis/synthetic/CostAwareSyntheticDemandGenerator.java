package Test.analysis.synthetic;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.CsvHistoryLoader;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.calculateHelper.KernelType;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 成本感知的合成周度需求生成器。
 *
 * 目标：
 * 1. 不改成本生成逻辑，只改需求；
 * 2. 利用“线路对应的低价 carrier 结构”把线路分成两个 regime 组；
 * 3. 让不同周的需求大头在线路组之间切换，同时保持一定持续性，
 *    从而让 lag-demand / lag-share 协变量更容易识别当前 regime；
 * 4. 在相同成本下，尽量让不同 regime 对应不同的低价供应商集合。
 */
public class CostAwareSyntheticDemandGenerator {
    private static final Locale US = Locale.US;

    private static final double TOP_LANE_COVERAGE = 0.90;
    private static final int REGIME_COUNT = 2;
    private static final int TOP_CHEAP_K = 5;
    private static final double REGIME_STAY_PROB = 0.94;
    private static final double BOOST_IN_GROUP = 8.0;
    private static final double BACKGROUND_OUT_GROUP = 0.20;
    private static final double ALPHA = 0.55;
    private static final double SHARE_NOISE_STD = 0.01;
    private static final double[] REGIME_TOTAL_MULTIPLIER = new double[] {0.92, 1.08};
    private static final double TOTAL_LOG_NOISE_STD = 0.06;
    private static final long SYNTH_SEED = 20260311L;

    public static void main(String[] args) throws Exception {
        Path historyCsv = Paths.get(args.length > 0 ? args[0] : "./fFreight_with_indicators_and_holidays.csv");
        Path outCsv = Paths.get(args.length > 1 ? args[1] : "./analysis/合成需求_成本感知_周度_仅前90%需求线路.csv");
        Path outNote = Paths.get(args.length > 2 ? args[2] : "./analysis/合成需求_成本感知_生成说明.txt");
        Path outGroupCsv = Paths.get(args.length > 3 ? args[3] : "./analysis/合成需求_成本感知_线路分组说明.csv");
        int numCarriers = (args.length > 4 ? Integer.parseInt(args[4]) : 15);

        Files.createDirectories(outCsv.toAbsolutePath().getParent());
        Files.createDirectories(outNote.toAbsolutePath().getParent());
        Files.createDirectories(outGroupCsv.toAbsolutePath().getParent());

        Config cfg = buildBaseConfig();
        InstanceGenerator.GenConfig genCfg = buildGenConfig();

        CsvHistoryLoader.HistoryLoadResult hist = CsvHistoryLoader.load(historyCsv, cfg);
        SampleBuilder.BuildResult br = SampleBuilder.build(hist.days, hist.laneNames, cfg);
        List<Integer> keepIdx = selectTopLaneIdx(br.periods, TOP_LANE_COVERAGE);
        List<String> laneNames = new ArrayList<>();
        for (int idx : keepIdx) laneNames.add(hist.laneNames.get(idx));

        List<PeriodData> periods = slicePeriods(br.periods, keepIdx);
        double[] dBase = buildBaselineDemand(periods);
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, cfg);

        boolean[][] cheapSignature = buildCheapSignature(params, TOP_CHEAP_K);
        int[] anchorPair = chooseBestAnchorPair(cheapSignature, buildBaseShare(periods));
        int[] laneGroup = assignLaneGroups(cheapSignature, anchorPair[0], anchorPair[1]);
        double[][] regimeProto = buildRegimePrototypes(periods, laneGroup);

        SyntheticWeeklyData syn = generateSyntheticWeekly(periods, regimeProto);

        writeWeeklyWideCsv(outCsv, laneNames, syn.demand);
        writeGroupCsv(outGroupCsv, laneNames, laneGroup, cheapSignature, params, buildBaseShare(periods));
        writeNote(outNote, laneNames, periods, params, anchorPair, laneGroup, regimeProto, syn, outCsv, outGroupCsv);

        System.out.println("[DONE] " + outCsv.toAbsolutePath());
        System.out.println("[DONE] " + outNote.toAbsolutePath());
        System.out.println("[DONE] " + outGroupCsv.toAbsolutePath());
    }

    private static Config buildBaseConfig() {
        Config cfg = new Config();
        cfg.fillMissingDates = true;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = 2;
        cfg.kernelType = KernelType.EXPONENTIAL;
        cfg.standardizeTheta = true;
        cfg.bandwidthH = 1.0;
        cfg.seed = 0;

        cfg.featureFlags.includeLagDemand = true;
        cfg.featureFlags.includeHolidayCount = false;
        cfg.featureFlags.includeFreightIndex = false;
        cfg.featureFlags.includeConsumptionIndex = false;
        cfg.featureFlags.includeWEIIndex = false;
        return cfg;
    }

    private static InstanceGenerator.GenConfig buildGenConfig() {
        InstanceGenerator.GenConfig g = new InstanceGenerator.GenConfig();
        g.rBarLow = 20.0;
        g.rBarHigh = 100.0;
        g.tauLow = 0.05;
        g.tauHigh = 0.30;
        g.mqcLow = 0.1;
        g.mqcHigh = 0.2;
        g.coverAllLanes = true;
        g.capacityMode = InstanceGenerator.CapacityMode.TIGHT;
        return g;
    }

    private static List<Integer> selectTopLaneIdx(List<PeriodData> periods, double coverage) {
        int J = periods.get(0).demandSum.length;
        double[] total = new double[J];
        for (PeriodData p : periods) {
            for (int j = 0; j < J; j++) total[j] += p.demandSum[j];
        }
        double grand = 0.0;
        for (double v : total) grand += v;

        List<Integer> order = new ArrayList<>();
        for (int j = 0; j < J; j++) order.add(j);
        order.sort(Comparator.comparingDouble((Integer j) -> total[j]).reversed());

        List<Integer> keep = new ArrayList<>();
        double cum = 0.0;
        for (int j : order) {
            keep.add(j);
            cum += total[j];
            if (grand <= 1e-12 || cum / grand >= coverage) break;
        }
        keep.sort(Integer::compareTo);
        return keep;
    }

    private static List<PeriodData> slicePeriods(List<PeriodData> periods, List<Integer> keepIdx) {
        List<PeriodData> out = new ArrayList<>(periods.size());
        for (PeriodData p : periods) {
            double[] d = new double[keepIdx.size()];
            for (int k = 0; k < keepIdx.size(); k++) d[k] = p.demandSum[keepIdx.get(k)];
            out.add(new PeriodData(
                    p.tIndex,
                    p.startDate,
                    p.endDate,
                    d,
                    p.holidayCount,
                    p.avgFreightIndex,
                    p.avgConsumptionIndex,
                    p.avgWEIIndex
            ));
        }
        return out;
    }

    private static double[] buildBaselineDemand(List<PeriodData> periods) {
        int J = periods.get(0).demandSum.length;
        double[] sum = new double[J];
        for (PeriodData p : periods) {
            for (int j = 0; j < J; j++) sum[j] += p.demandSum[j];
        }
        double denom = Math.max(1, periods.size());
        for (int j = 0; j < J; j++) sum[j] /= denom;
        return sum;
    }

    private static double[] buildBaseShare(List<PeriodData> periods) {
        int J = periods.get(0).demandSum.length;
        double[] total = new double[J];
        for (PeriodData p : periods) {
            for (int j = 0; j < J; j++) total[j] += p.demandSum[j];
        }
        return normalize(total);
    }

    private static boolean[][] buildCheapSignature(ProcurementParams params, int topK) {
        boolean[][] sign = new boolean[params.J][params.I];
        for (int j = 0; j < params.J; j++) {
            List<int[]> order = new ArrayList<>();
            for (int i = 0; i < params.I; i++) {
                order.add(new int[] {i, (int) Math.round(params.r[i][j] * 1_000_000.0)});
            }
            order.sort(Comparator.comparingInt((int[] x) -> x[1]).thenComparingInt(x -> x[0]));
            for (int k = 0; k < Math.min(topK, order.size()); k++) {
                sign[j][order.get(k)[0]] = true;
            }
        }
        return sign;
    }

    private static int[] chooseBestAnchorPair(boolean[][] sig, double[] baseShare) {
        int J = sig.length;
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestA = 0;
        int bestB = Math.min(1, J - 1);
        for (int a = 0; a < J; a++) {
            for (int b = a + 1; b < J; b++) {
                double dist = hamming(sig[a], sig[b]);
                if (dist < 0.55) continue;

                int cntA = 0;
                int cntB = 0;
                double shareA = 0.0;
                double shareB = 0.0;
                double margin = 0.0;
                for (int j = 0; j < J; j++) {
                    double simA = overlap(sig[j], sig[a]);
                    double simB = overlap(sig[j], sig[b]);
                    if (simA >= simB) {
                        cntA++;
                        shareA += baseShare[j];
                    } else {
                        cntB++;
                        shareB += baseShare[j];
                    }
                    margin += Math.abs(simA - simB) * baseShare[j];
                }
                if (Math.min(cntA, cntB) < Math.max(6, J / 6)) continue;
                if (Math.min(shareA, shareB) < 0.20) continue;

                double balancePenalty = Math.abs(shareA - shareB);
                double score = 3.0 * dist + 2.0 * margin - balancePenalty;
                if (score > bestScore) {
                    bestScore = score;
                    bestA = a;
                    bestB = b;
                }
            }
        }
        return new int[] {bestA, bestB};
    }

    private static int[] assignLaneGroups(boolean[][] sig, int anchorA, int anchorB) {
        int[] group = new int[sig.length];
        for (int j = 0; j < sig.length; j++) {
            double simA = overlap(sig[j], sig[anchorA]);
            double simB = overlap(sig[j], sig[anchorB]);
            group[j] = (simA >= simB ? 0 : 1);
        }
        return group;
    }

    private static double[][] buildRegimePrototypes(List<PeriodData> periods, int[] laneGroup) {
        double[] baseShare = buildBaseShare(periods);
        int J = baseShare.length;
        double[][] out = new double[REGIME_COUNT][J];
        for (int g = 0; g < REGIME_COUNT; g++) {
            double[] w = new double[J];
            for (int j = 0; j < J; j++) {
                double factor = (laneGroup[j] == g ? BOOST_IN_GROUP : BACKGROUND_OUT_GROUP);
                w[j] = baseShare[j] * factor;
            }
            out[g] = normalize(w);
        }
        return out;
    }

    private static SyntheticWeeklyData generateSyntheticWeekly(List<PeriodData> periods, double[][] regimeProto) {
        Random rnd = new Random(SYNTH_SEED);
        int T = periods.size();
        int J = periods.get(0).demandSum.length;

        double meanTotal = 0.0;
        for (PeriodData p : periods) {
            meanTotal += sum(p.demandSum);
        }
        meanTotal /= Math.max(1, T);

        int[] states = new int[T];
        states[0] = rnd.nextBoolean() ? 1 : 0;
        for (int t = 1; t < T; t++) {
            if (rnd.nextDouble() < REGIME_STAY_PROB) {
                states[t] = states[t - 1];
            } else {
                states[t] = 1 - states[t - 1];
            }
        }

        double[][] shares = new double[T][J];
        double[][] demand = new double[T][J];
        shares[0] = regimeProto[states[0]].clone();
        double lastLogTotal = Math.log(meanTotal * REGIME_TOTAL_MULTIPLIER[states[0]]);
        for (int t = 0; t < T; t++) {
            if (t > 0) {
                double[] target = regimeProto[states[t]];
                double[] x = new double[J];
                for (int j = 0; j < J; j++) {
                    x[j] = (1.0 - ALPHA) * shares[t - 1][j]
                            + ALPHA * target[j]
                            + rnd.nextGaussian() * SHARE_NOISE_STD;
                    if (x[j] < 1e-8) x[j] = 1e-8;
                }
                shares[t] = normalize(x);
            }

            double regimeMeanLog = Math.log(meanTotal * REGIME_TOTAL_MULTIPLIER[states[t]]);
            if (t == 0) {
                lastLogTotal = regimeMeanLog + rnd.nextGaussian() * TOTAL_LOG_NOISE_STD;
            } else {
                lastLogTotal = 0.65 * lastLogTotal + 0.35 * regimeMeanLog + rnd.nextGaussian() * TOTAL_LOG_NOISE_STD;
            }
            double total = Math.exp(lastLogTotal);
            for (int j = 0; j < J; j++) demand[t][j] = shares[t][j] * total;
        }
        return new SyntheticWeeklyData(states, shares, demand);
    }

    private static void writeWeeklyWideCsv(Path outFile, List<String> laneNames, double[][] demand) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("weekIndex");
            for (String lane : laneNames) bw.write("," + csvSafe(lane));
            bw.newLine();
            for (int t = 0; t < demand.length; t++) {
                bw.write(String.valueOf(t));
                for (int j = 0; j < demand[t].length; j++) {
                    bw.write(String.format(US, ",%.10f", demand[t][j]));
                }
                bw.newLine();
            }
        }
    }

    private static void writeGroupCsv(Path outFile,
                                      List<String> laneNames,
                                      int[] laneGroup,
                                      boolean[][] cheapSignature,
                                      ProcurementParams params,
                                      double[] baseShare) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("线路组,lineIdx,lineName,历史基础占比,top5低价carriers");
            bw.newLine();
            for (int j = 0; j < laneNames.size(); j++) {
                bw.write(String.format(US, "%d,%d,%s,%.10f,%s%n",
                        laneGroup[j],
                        j,
                        csvSafe(laneNames.get(j)),
                        baseShare[j],
                        csvSafe(topCarrierString(cheapSignature[j], params))));
            }
        }
    }

    private static void writeNote(Path outFile,
                                  List<String> laneNames,
                                  List<PeriodData> periods,
                                  ProcurementParams params,
                                  int[] anchorPair,
                                  int[] laneGroup,
                                  double[][] regimeProto,
                                  SyntheticWeeklyData syn,
                                  Path outCsv,
                                  Path outGroupCsv) throws Exception {
        double[] baseShare = buildBaseShare(periods);
        int[] groupCount = new int[REGIME_COUNT];
        double[] groupBaseShare = new double[REGIME_COUNT];
        int[] stateCount = new int[REGIME_COUNT];
        for (int g : laneGroup) groupCount[g]++;
        for (int j = 0; j < laneGroup.length; j++) groupBaseShare[laneGroup[j]] += baseShare[j];
        for (int s : syn.states) stateCount[s]++;

        List<String> lines = new ArrayList<>();
        lines.add("成本感知合成需求生成说明");
        lines.add("");
        lines.add("目的：只修改需求数据，不修改成本生成逻辑；通过让需求大头在线路组之间切换，提升 CSAA 相对 SAA 的条件化价值。");
        lines.add("");
        lines.add("关键参数：");
        lines.add("TOP_LANE_COVERAGE=" + TOP_LANE_COVERAGE);
        lines.add("REGIME_COUNT=" + REGIME_COUNT);
        lines.add("TOP_CHEAP_K=" + TOP_CHEAP_K);
        lines.add("REGIME_STAY_PROB=" + REGIME_STAY_PROB);
        lines.add("BOOST_IN_GROUP=" + BOOST_IN_GROUP);
        lines.add("BACKGROUND_OUT_GROUP=" + BACKGROUND_OUT_GROUP);
        lines.add("ALPHA=" + ALPHA);
        lines.add("SHARE_NOISE_STD=" + SHARE_NOISE_STD);
        lines.add("REGIME_TOTAL_MULTIPLIER=" + Arrays.toString(REGIME_TOTAL_MULTIPLIER));
        lines.add("TOTAL_LOG_NOISE_STD=" + TOTAL_LOG_NOISE_STD);
        lines.add("SYNTH_SEED=" + SYNTH_SEED);
        lines.add("");
        lines.add("输出文件：");
        lines.add("- 周度需求文件: " + outCsv.toAbsolutePath());
        lines.add("- 线路分组: " + outGroupCsv.toAbsolutePath());
        lines.add("");
        lines.add("线路组：");
        lines.add(String.format(US, "anchorA lineIdx=%d, laneName=%s, top5=%s",
                anchorPair[0], laneNames.get(anchorPair[0]),
                topCarrierString(buildCheapSignature(params, TOP_CHEAP_K)[anchorPair[0]], params)));
        lines.add(String.format(US, "anchorB lineIdx=%d, laneName=%s, top5=%s",
                anchorPair[1], laneNames.get(anchorPair[1]),
                topCarrierString(buildCheapSignature(params, TOP_CHEAP_K)[anchorPair[1]], params)));
        lines.add("");
        for (int g = 0; g < REGIME_COUNT; g++) {
            lines.add(String.format(US,
                    "线路组 %d: 线路数=%d, 历史基础share合计=%.6f, regime出现周数=%d, 组内常见低价carriers=%s",
                    g,
                    groupCount[g],
                    groupBaseShare[g],
                    stateCount[g],
                    dominantCarriersString(laneGroup, g, buildCheapSignature(params, TOP_CHEAP_K), params)));
        }
        lines.add("");
        lines.add("regime 原型 top10 线路：");
        for (int g = 0; g < REGIME_COUNT; g++) {
            lines.add("regime " + g + ": " + topLaneString(regimeProto[g], laneNames, 10));
        }
        Files.write(outFile, lines);
    }

    private static double overlap(boolean[] a, boolean[] b) {
        int inter = 0;
        int union = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] || b[i]) union++;
            if (a[i] && b[i]) inter++;
        }
        return union == 0 ? 0.0 : ((double) inter / union);
    }

    private static double hamming(boolean[] a, boolean[] b) {
        int diff = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) diff++;
        return (double) diff / a.length;
    }

    private static double[] normalize(double[] x) {
        double s = 0.0;
        for (double v : x) s += Math.max(v, 1e-12);
        double[] out = new double[x.length];
        if (s <= 1e-12) {
            Arrays.fill(out, 1.0 / x.length);
            return out;
        }
        for (int i = 0; i < x.length; i++) out[i] = Math.max(x[i], 1e-12) / s;
        return out;
    }

    private static double sum(double[] x) {
        double s = 0.0;
        for (double v : x) s += v;
        return s;
    }

    private static String topCarrierString(boolean[] sign, ProcurementParams params) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < sign.length; i++) if (sign[i]) ids.add(params.carriers.get(i));
        return String.join("|", ids);
    }

    private static String dominantCarriersString(int[] laneGroup,
                                                 int targetGroup,
                                                 boolean[][] sign,
                                                 ProcurementParams params) {
        double[] score = new double[params.I];
        for (int j = 0; j < laneGroup.length; j++) {
            if (laneGroup[j] != targetGroup) continue;
            for (int i = 0; i < params.I; i++) if (sign[j][i]) score[i] += 1.0;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < params.I; i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> score[i]).reversed());
        List<String> out = new ArrayList<>();
        for (int k = 0; k < Math.min(6, order.size()); k++) {
            int i = order.get(k);
            if (score[i] <= 0.0) break;
            out.add(params.carriers.get(i) + "(" + String.format(US, "%.0f", score[i]) + ")");
        }
        return String.join("|", out);
    }

    private static String topLaneString(double[] share, List<String> laneNames, int topN) {
        List<Integer> order = new ArrayList<>();
        for (int j = 0; j < share.length; j++) order.add(j);
        order.sort(Comparator.comparingDouble((Integer j) -> share[j]).reversed());
        List<String> out = new ArrayList<>();
        for (int k = 0; k < Math.min(topN, order.size()); k++) {
            int j = order.get(k);
            out.add(laneNames.get(j) + "(" + String.format(US, "%.4f", share[j]) + ")");
        }
        return String.join(" | ", out);
    }

    private static String csvSafe(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("|")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static class SyntheticWeeklyData {
        final int[] states;
        final double[][] shares;
        final double[][] demand;

        SyntheticWeeklyData(int[] states, double[][] shares, double[][] demand) {
            this.states = states;
            this.shares = shares;
            this.demand = demand;
        }
    }
}

