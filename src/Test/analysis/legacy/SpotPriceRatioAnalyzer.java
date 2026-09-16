package Test.analysis.legacy;

import Basic.ProcurementParams;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * spot 与合同价比值诊断器。
 *
 * 这个类只分析价格结构，不涉及求解。输入一组 `ProcurementParams` 后，
 * 会统计 `e_j / r_ij` 在 pair、line、carrier 三个层面的分布，
 * 用来判断当前实例里 spot 相对合同价到底高多少、哪些线路/供应商上的差距最大。
 */
public final class SpotPriceRatioAnalyzer {

    private SpotPriceRatioAnalyzer() {}

    /** Convenience: print summary to console + write CSVs to outDir. */
    public static void run(ProcurementParams params, Path outDir) throws IOException {
//     
    	if(outDir!=null)
    	Files.createDirectories(outDir);

        Report rep = analyze(params);

        // console summary (concise)
        System.out.println(rep.prettySummary(10, 10));

        // full CSV outputs
//        writeLaneCsv(rep, outDir.resolve("lane_spot_vs_contract.csv"));
//        writeCarrierCsv(rep, outDir.resolve("carrier_spot_vs_contract.csv"));
//        writePairRatioCsv(rep, outDir.resolve("pair_spot_over_rij.csv"));
    }

    /** Only analyze and return report object (no I/O). */
    public static Report analyze(ProcurementParams params) {
        Objects.requireNonNull(params, "params");

        int I = params.I;
        int J = params.J;

        // ===== global lists =====
        List<Double> allEligibleR = new ArrayList<>();
        List<Double> allSpotE = new ArrayList<>();
        List<Double> pairRatio = new ArrayList<>(); // e_j / r_ij across eligible pairs
        int pairCnt = 0;
        int pairSpotHigherCnt = 0; // e_j > r_ij count

        // ===== lane-level records =====
        LaneRec[] laneRecs = new LaneRec[J];

        for (int j = 0; j < J; j++) {
            double e = params.e[j];
            allSpotE.add(e);

            List<Double> rList = new ArrayList<>();
            for (int i = 0; i < I; i++) {
                if (!params.eligible[i][j]) continue;

                double rij = params.r[i][j];
                if (!Double.isFinite(rij) || rij <= 0) continue; // guard
                rList.add(rij);
                allEligibleR.add(rij);

                double ratio = e / rij;
                if (Double.isFinite(ratio)) {
                    pairRatio.add(ratio);
                    pairCnt++;
                    if (e > rij) pairSpotHigherCnt++;
                }
            }

            laneRecs[j] = LaneRec.from(j, e, rList);
        }

        // ===== carrier-level records =====
        CarrierRec[] carrierRecs = new CarrierRec[I];
        for (int i = 0; i < I; i++) {
            List<Double> ratios = new ArrayList<>();
            List<Double> rList = new ArrayList<>();
            List<Double> eList = new ArrayList<>();

            for (int j = 0; j < J; j++) {
                if (!params.eligible[i][j]) continue;

                double rij = params.r[i][j];
                double e = params.e[j];

                if (!Double.isFinite(rij) || rij <= 0) continue;

                rList.add(rij);
                eList.add(e);

                double ratio = e / rij;
                if (Double.isFinite(ratio)) ratios.add(ratio);
            }

            carrierRecs[i] = CarrierRec.from(i, params.carriers.get(i), rList, eList, ratios);
        }

        // ===== summary stats =====
        Stats spotStats = Stats.of(allSpotE);
        Stats rStats = Stats.of(allEligibleR);
        Stats pairRatioStats = Stats.of(pairRatio);

        // global mean ratio: mean(spot) / mean(contract)
        double globalMeanSpotOverMeanR = (rStats.mean > 0 ? spotStats.mean / rStats.mean : Double.NaN);

        // lane-type counts: spot cheaper than all / more expensive than all
        int lanesSpotCheaperThanAll = 0;
        int lanesSpotMoreExpThanAll = 0;
        int lanesValid = 0;
        for (LaneRec lr : laneRecs) {
            if (!lr.valid) continue;
            lanesValid++;
            if (lr.e < lr.rMin) lanesSpotCheaperThanAll++;
            if (lr.e > lr.rMax) lanesSpotMoreExpThanAll++;
        }

        return new Report(
                params,
                spotStats, rStats, pairRatioStats,
                globalMeanSpotOverMeanR,
                pairCnt, pairSpotHigherCnt,
                lanesValid, lanesSpotCheaperThanAll, lanesSpotMoreExpThanAll,
                laneRecs, carrierRecs,
                pairRatio
        );
    }

    // ===================== CSV writers =====================

    private static void writeLaneCsv(Report rep, Path csv) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write(String.join(",",
                    "laneIdx",
                    "spot_e",
                    "nEligible",
                    "r_min","r_mean","r_median","r_max",
                    "e_over_r_min","e_over_r_mean","e_over_r_median","e_over_r_max",
                    "spotCheaperThanAll","spotMoreExpThanAll"
            ));
            bw.newLine();

            for (LaneRec lr : rep.lanes) {
                if (!lr.valid) continue;
                bw.write(String.format(Locale.US,
                        "%d,%.10f,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%s,%s%n",
                        lr.laneIdx,
                        lr.e,
                        lr.nEligible,
                        lr.rMin, lr.rMean, lr.rMedian, lr.rMax,
                        lr.eOverMin, lr.eOverMean, lr.eOverMedian, lr.eOverMax,
                        String.valueOf(lr.e < lr.rMin),
                        String.valueOf(lr.e > lr.rMax)
                ));
            }
        }
    }

    private static void writeCarrierCsv(Report rep, Path csv) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write(String.join(",",
                    "carrierIdx","carrierName",
                    "nEligibleLanes",
                    "r_mean","r_median","r_min","r_max",
                    "spot_mean_on_covered_lanes",
                    "mean_of(e_over_rij)","median_of(e_over_rij)","p80_of(e_over_rij)","p95_of(e_over_rij)"
            ));
            bw.newLine();

            for (CarrierRec cr : rep.carriers) {
                if (!cr.valid) continue;
                bw.write(String.format(Locale.US,
                        "%d,%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                        cr.carrierIdx,
                        safe(cr.name),
                        cr.nEligibleLanes,
                        cr.rStats.mean, cr.rStats.p50, cr.rStats.min, cr.rStats.max,
                        cr.spotMean,
                        cr.ratioStats.mean, cr.ratioStats.p50, cr.ratioStats.p80, cr.ratioStats.p95
                ));
            }
        }
    }

    private static void writePairRatioCsv(Report rep, Path csv) throws IOException {
        // pair-level ratios are useful when you want to see the full distribution
        // (e.g., how often spot is >= 1.2x contract).
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write("pairIdx,spot_over_rij");
            bw.newLine();
            int idx = 0;
            for (double v : rep.pairRatios) {
                bw.write(String.format(Locale.US, "%d,%.10f%n", idx++, v));
            }
        }
    }

    private static String safe(String s) {
        return (s == null) ? "" : s.replace(",", "_");
    }

    // ===================== Report / Records =====================

    public static final class Report {
        public final ProcurementParams params;

        public final Stats spotStats;          // e_j over lanes
        public final Stats contractStats;      // r_ij over eligible pairs
        public final Stats pairRatioStats;     // e_j / r_ij over eligible pairs

        public final double globalMeanSpotOverMeanContract; // mean(e) / mean(r)

        public final int nPairs;
        public final int nPairsSpotHigher; // count of (e_j > r_ij)

        public final int nLanesValid;
        public final int nLanesSpotCheaperThanAll;
        public final int nLanesSpotMoreExpThanAll;

        public final LaneRec[] lanes;
        public final CarrierRec[] carriers;

        // full distribution for downstream analysis (optional)
        public final List<Double> pairRatios;

        private Report(ProcurementParams params,
                       Stats spotStats, Stats contractStats, Stats pairRatioStats,
                       double globalMeanSpotOverMeanContract,
                       int nPairs, int nPairsSpotHigher,
                       int nLanesValid, int nLanesSpotCheaperThanAll, int nLanesSpotMoreExpThanAll,
                       LaneRec[] lanes, CarrierRec[] carriers,
                       List<Double> pairRatios) {
            this.params = params;
            this.spotStats = spotStats;
            this.contractStats = contractStats;
            this.pairRatioStats = pairRatioStats;
            this.globalMeanSpotOverMeanContract = globalMeanSpotOverMeanContract;
            this.nPairs = nPairs;
            this.nPairsSpotHigher = nPairsSpotHigher;
            this.nLanesValid = nLanesValid;
            this.nLanesSpotCheaperThanAll = nLanesSpotCheaperThanAll;
            this.nLanesSpotMoreExpThanAll = nLanesSpotMoreExpThanAll;
            this.lanes = lanes;
            this.carriers = carriers;
            this.pairRatios = pairRatios;
        }

        /** Print summary + top-K worst lanes and carriers by (spot/meanContract). */
        public String prettySummary(int topKLanes, int topKCarriers) {
            StringBuilder sb = new StringBuilder();
            Locale us = Locale.US;

            sb.append("===== Spot vs Contract Ratio Report =====\n");
            sb.append(String.format(us, "I=%d, J=%d, alpha=%d, beta=%d\n", params.I, params.J, params.alpha, params.beta));
            sb.append("\n");

            sb.append("[Spot e_j stats over lanes]\n").append(spotStats.pretty()).append("\n\n");
            sb.append("[Contract r_ij stats over eligible pairs]\n").append(contractStats.pretty()).append("\n\n");

            sb.append("[Pair ratio stats: e_j / r_ij over eligible pairs]\n")
              .append(pairRatioStats.pretty()).append("\n\n");

            sb.append(String.format(us,
                    "Global mean ratio: mean(spot)/mean(contract) = %.6f\n",
                    globalMeanSpotOverMeanContract));

            if (nPairs > 0) {
                double frac = (double) nPairsSpotHigher / (double) nPairs;
                sb.append(String.format(us,
                        "Fraction of eligible (i,j) with spot > contract: %d/%d = %.4f\n",
                        nPairsSpotHigher, nPairs, frac));
            }

            if (nLanesValid > 0) {
                sb.append(String.format(us,
                        "Lanes where spot < min(contract): %d/%d\n",
                        nLanesSpotCheaperThanAll, nLanesValid));
                sb.append(String.format(us,
                        "Lanes where spot > max(contract): %d/%d\n",
                        nLanesSpotMoreExpThanAll, nLanesValid));
            }
            sb.append("\n");

            // top worst lanes by e/mean(r)
            List<LaneRec> laneList = new ArrayList<>();
            for (LaneRec lr : lanes) if (lr.valid) laneList.add(lr);
            laneList.sort((a, b) -> Double.compare(b.eOverMean, a.eOverMean));

            sb.append(String.format(us, "Top-%d lanes by spot/mean(contract):\n", topKLanes));
            for (int k = 0; k < Math.min(topKLanes, laneList.size()); k++) {
                LaneRec lr = laneList.get(k);
                sb.append(String.format(us,
                        "  lane=%d | e=%.3f | rMean=%.3f | e/rMean=%.3f | e/rMin=%.3f | e/rMax=%.3f\n",
                        lr.laneIdx, lr.e, lr.rMean, lr.eOverMean, lr.eOverMin, lr.eOverMax));
            }
            sb.append("\n");

            // top worst carriers by mean(e/r_ij)
            List<CarrierRec> carrierList = new ArrayList<>();
            for (CarrierRec cr : carriers) if (cr.valid) carrierList.add(cr);
            carrierList.sort((a, b) -> Double.compare(b.ratioStats.mean, a.ratioStats.mean));

            sb.append(String.format(us, "Top-%d carriers by mean(spot/contract):\n", topKCarriers));
            for (int k = 0; k < Math.min(topKCarriers, carrierList.size()); k++) {
                CarrierRec cr = carrierList.get(k);
                sb.append(String.format(us,
                        "  carrier=%s | mean(e/r)=%.3f | median(e/r)=%.3f | rMean=%.3f | spotMean=%.3f\n",
                        cr.name, cr.ratioStats.mean, cr.ratioStats.p50, cr.rStats.mean, cr.spotMean));
            }

            sb.append("========================================\n");
            return sb.toString();
        }
    }

    /** Lane-level summary: spot vs distribution of contract prices on this lane. */
    static final class LaneRec {
        final int laneIdx;
        final double e;

        final int nEligible;
        final double rMin, rMean, rMedian, rMax;

        final double eOverMin, eOverMean, eOverMedian, eOverMax;

        final boolean valid;

        private LaneRec(int laneIdx, double e,
                        int nEligible,
                        double rMin, double rMean, double rMedian, double rMax,
                        double eOverMin, double eOverMean, double eOverMedian, double eOverMax,
                        boolean valid) {
            this.laneIdx = laneIdx;
            this.e = e;
            this.nEligible = nEligible;
            this.rMin = rMin;
            this.rMean = rMean;
            this.rMedian = rMedian;
            this.rMax = rMax;
            this.eOverMin = eOverMin;
            this.eOverMean = eOverMean;
            this.eOverMedian = eOverMedian;
            this.eOverMax = eOverMax;
            this.valid = valid;
        }

        static LaneRec from(int laneIdx, double e, List<Double> rList) {
            if (rList == null || rList.isEmpty()) {
                return new LaneRec(laneIdx, e, 0,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        false);
            }
            Stats rs = Stats.of(rList);
            double eOverMin = (rs.min > 0 ? e / rs.min : Double.NaN);
            double eOverMean = (rs.mean > 0 ? e / rs.mean : Double.NaN);
            double eOverMedian = (rs.p50 > 0 ? e / rs.p50 : Double.NaN);
            double eOverMax = (rs.max > 0 ? e / rs.max : Double.NaN);

            return new LaneRec(laneIdx, e, rList.size(),
                    rs.min, rs.mean, rs.p50, rs.max,
                    eOverMin, eOverMean, eOverMedian, eOverMax,
                    true);
        }
    }

    /** Carrier-level summary: average of (spot/contract) across lanes it can serve. */
    static final class CarrierRec {
        final int carrierIdx;
        final String name;

        final int nEligibleLanes;
        final Stats rStats;
        final Stats ratioStats;

        final double spotMean;
        final boolean valid;

        private CarrierRec(int carrierIdx, String name,
                           int nEligibleLanes,
                           Stats rStats, Stats ratioStats,
                           double spotMean,
                           boolean valid) {
            this.carrierIdx = carrierIdx;
            this.name = name;
            this.nEligibleLanes = nEligibleLanes;
            this.rStats = rStats;
            this.ratioStats = ratioStats;
            this.spotMean = spotMean;
            this.valid = valid;
        }

        static CarrierRec from(int idx, String name,
                               List<Double> rList,
                               List<Double> eList,
                               List<Double> ratios) {
            if (rList == null || rList.isEmpty()) {
                return new CarrierRec(idx, name, 0,
                        Stats.nan(), Stats.nan(),
                        Double.NaN,
                        false);
            }
            Stats rStats = Stats.of(rList);
            Stats ratioStats = (ratios == null || ratios.isEmpty()) ? Stats.nan() : Stats.of(ratios);
            Stats eStats = (eList == null || eList.isEmpty()) ? Stats.nan() : Stats.of(eList);

            return new CarrierRec(idx, name, rList.size(), rStats, ratioStats, eStats.mean, true);
        }
    }

    /** Simple stats with quantiles; robust against empty list. */
    static final class Stats {
        final int n;
        final double mean, std, min, max;
        final double p20, p50, p80, p95;

        private Stats(int n, double mean, double std, double min, double max,
                      double p20, double p50, double p80, double p95) {
            this.n = n;
            this.mean = mean;
            this.std = std;
            this.min = min;
            this.max = max;
            this.p20 = p20;
            this.p50 = p50;
            this.p80 = p80;
            this.p95 = p95;
        }

        static Stats nan() {
            return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        static Stats of(List<Double> xs) {
            if (xs == null || xs.isEmpty()) return nan();
            double[] a = xs.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
            if (a.length == 0) return nan();

            int n = a.length;

            double sum = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double v : a) {
                if (!Double.isFinite(v)) continue;
                sum += v;
                if (v < min) min = v;
                if (v > max) max = v;
            }
            double mean = sum / n;

            double var = 0;
            for (double v : a) {
                double d = v - mean;
                var += d * d;
            }
            double std = Math.sqrt(var / Math.max(1, n - 1));

            Arrays.sort(a);
            double p20 = q(a, 0.20);
            double p50 = q(a, 0.50);
            double p80 = q(a, 0.80);
            double p95 = q(a, 0.95);

            return new Stats(n, mean, std, min, max, p20, p50, p80, p95);
        }

        private static double q(double[] s, double q) {
            if (s.length == 1) return s[0];
            double pos = q * (s.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = (int) Math.ceil(pos);
            if (lo == hi) return s[lo];
            double w = pos - lo;
            return s[lo] * (1 - w) + s[hi] * w;
        }

        String pretty() {
            Locale us = Locale.US;
            return String.format(us,
                    "n=%d\nmean=%.6f\nstd=%.6f\nmin=%.6f\nP20=%.6f\nP50=%.6f\nP80=%.6f\nP95=%.6f\nmax=%.6f",
                    n, mean, std, min, p20, p50, p80, p95, max);
        }
    }
}

