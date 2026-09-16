package Test;

import Basic.Sample;

import java.util.*;

/**
 * DemandAnalyzer: compute demand volatility (CV = std/mean) per lane and for total demand,
 * based on a given training sample list.
 *
 * - Supports both unweighted and weighted statistics (using Sample.weight).
 * - If mean is 0 (or extremely small), CV is reported as NaN to avoid divide-by-zero.
 */
public class DemandAnalyzer {

    /** Numerical guard for "mean close to 0". */
    private static final double EPS = 1e-12;

    /** A compact stats container. */
    public static class Stats {
        public final int n;
        public final double mean;
        public final double std;
        public final double min;
        public final double max;
        public final double cv;

        public Stats(int n, double mean, double std, double min, double max, double cv) {
            this.n = n;
            this.mean = mean;
            this.std = std;
            this.min = min;
            this.max = max;
            this.cv = cv;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "n=%d, mean=%.6f, std=%.6f, min=%.6f, max=%.6f, CV=%.6f",
                    n, mean, std, min, max, min, max, cv);
        }
    }

    /** Full report: per-lane + total. */
    public static class Report {
        public final boolean weighted;
        public final int nSamples;
        public final int J;

        /** laneStats[j] is stats for lane j. */
        public final Stats[] laneStats;

        /** stats for total demand sum_j d_j per sample. */
        public final Stats totalStats;

        public Report(boolean weighted, int nSamples, int J, Stats[] laneStats, Stats totalStats) {
            this.weighted = weighted;
            this.nSamples = nSamples;
            this.J = J;
            this.laneStats = laneStats;
            this.totalStats = totalStats;
        }

        /** Pretty print for quick logging. */
        public String pretty() {
            StringBuilder sb = new StringBuilder();
            sb.append("DemandAnalyzer Report\n");
            sb.append("weighted=").append(weighted).append("\n");
            sb.append("nSamples=").append(nSamples).append("\n");
            sb.append("J=").append(J).append("\n\n");

            sb.append("[Per-lane CV]\n");
            for (int j = 0; j < J; j++) {
                Stats s = laneStats[j];
                sb.append(String.format(Locale.US,
                        "lane=%d: mean=%.6f, std=%.6f, CV=%s, min=%.6f, max=%.6f\n",
                        j, s.mean, s.std,
                        (Double.isFinite(s.cv) ? String.format(Locale.US, "%.6f", s.cv) : "NaN"),
                        s.min, s.max));
            }
            sb.append("\n[Total demand (sum over lanes) CV]\n");
            sb.append(String.format(Locale.US,
                    "mean=%.6f, std=%.6f, CV=%s, min=%.6f, max=%.6f\n",
                    totalStats.mean, totalStats.std,
                    (Double.isFinite(totalStats.cv) ? String.format(Locale.US, "%.6f", totalStats.cv) : "NaN"),
                    totalStats.min, totalStats.max));

            return sb.toString();
        }
    }

    /**
     * Compute UNWEIGHTED per-lane CV and total-demand CV from training samples.
     */
    public static void analyzeUnweighted(List<Sample> train) {
        System.out.println(analyze(train, false));
    }

    /**
     * Compute WEIGHTED per-lane CV and total-demand CV from training samples.
     * Weight uses Sample.weight; robust to weights not summing to 1.
     */
    public static void analyzeWeighted(List<Sample> train) {
    	 System.out.println(analyze(train, true));
    }

    // ===================== core =====================

    private static String analyze(List<Sample> train, boolean weighted) {
        if (train == null || train.isEmpty()) {
          return new Report(weighted, 0, 0, new Stats[0],
                    new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)).pretty();
        }

        // infer J from first sample demand length
        double[] d0 = train.get(0).demand();
        int J = (d0 == null ? 0 : d0.length);
        int N = train.size();

        // store per-sample totals
        double[] total = new double[N];

        // initialize lane arrays
        double[][] laneVals = new double[J][N];
        for (int t = 0; t < N; t++) {
            double[] d = train.get(t).demand();
            if (d == null || d.length != J) {
                throw new IllegalArgumentException("Inconsistent demand dimension at sample index " + t);
            }
            double sum = 0.0;
            for (int j = 0; j < J; j++) {
                laneVals[j][t] = d[j];
                sum += d[j];
            }
            total[t] = sum;
        }

        Stats[] laneStats = new Stats[J];
        for (int j = 0; j < J; j++) {
            laneStats[j] = weighted
                    ? statsWeighted(laneVals[j], train)
                    : statsUnweighted(laneVals[j]);
        }

        Stats totalStats = weighted ? statsWeighted(total, train) : statsUnweighted(total);

        return new Report(weighted, N, J, laneStats, totalStats).pretty();
    }

    // ===================== stats helpers =====================

    private static Stats statsUnweighted(double[] x) {
        int n = x.length;
        if (n == 0) return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (double v : x) {
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / n;

        double var = 0.0;
        for (double v : x) {
            double d = v - mean;
            var += d * d;
        }
        // sample std (n-1); if n==1 -> std=0
        double std = (n >= 2) ? Math.sqrt(var / (n - 1)) : 0.0;

        double cv = (Math.abs(mean) > EPS) ? (std / mean) : Double.NaN;
        return new Stats(n, mean, std, min, max, cv);
    }

    /**
     * Weighted std using population-style variance:
     *   mean = sum(w x)/sum(w)
     *   var  = sum(w (x-mean)^2)/sum(w)
     *
     * (This is usually what you want for weighted scenario summaries.)
     */
    private static Stats statsWeighted(double[] x, List<Sample> train) {
        int n = x.length;
        if (n == 0) return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        if (train.size() != n) throw new IllegalArgumentException("train size mismatch for weighted stats.");

        double sumW = 0.0;
        double sumWX = 0.0;

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (int t = 0; t < n; t++) {
            double w = train.get(t).weight;
            double v = x[t];

            if (v < min) min = v;
            if (v > max) max = v;

            if (!Double.isFinite(w) || w <= 0) continue;
            sumW += w;
            sumWX += w * v;
        }

        if (!(sumW > 0.0)) {
            // no valid weights -> return NaNs
            return new Stats(n, Double.NaN, Double.NaN, min, max, Double.NaN);
        }

        double mean = sumWX / sumW;

        double sumWVar = 0.0;
        for (int t = 0; t < n; t++) {
            double w = train.get(t).weight;
            if (!Double.isFinite(w) || w <= 0) continue;
            double d = x[t] - mean;
            sumWVar += w * d * d;
        }
        double var = sumWVar / sumW;
        double std = Math.sqrt(Math.max(0.0, var));

        double cv = (Math.abs(mean) > EPS) ? (std / mean) : Double.NaN;
        return new Stats(n, mean, std, min, max, cv);
    }
}
