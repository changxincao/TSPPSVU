package Test.analysis.synthetic;

import Basic.Sample;
import Test.analysis.synthetic.TRBReviewerOlistDynamicDgpV2.Calibration;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** Structural and Monte Carlo checks for the Olist-calibrated dynamic DGP v2. */
public final class TRBReviewerOlistDynamicDgpV2SelfCheck {
    private TRBReviewerOlistDynamicDgpV2SelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: <weekly-wide-csv>");
        }
        Path weekly = Path.of(args[0]).toAbsolutePath().normalize();
        for (int laneCount : new int[]{23, 50, 100, 200}) {
            Calibration calibration = TRBReviewerOlistDynamicDgpV2.calibrate(
                    weekly, laneCount);
            ReplicationData generated = TRBReviewerOlistDynamicDgpV2.generate(
                    calibration, 2_000, 1_000, 20_000, 73_021L,
                    InnovationDistribution.LOGNORMAL, 0.345);
            verifyStructure(generated, laneCount);
            double meanError = conditionalMeanRelativeError(generated);
            if (meanError > 0.035) {
                throw new IllegalStateException(String.format(Locale.US,
                        "Conditional-mean relative L1 error %.6f exceeds tolerance for J=%d",
                        meanError, laneCount));
            }
            double[] profile = calibration.baselineDemand().clone();
            Arrays.sort(profile);
            reverse(profile);
            double total = sum(profile);
            System.out.printf(Locale.US,
                    "J=%d baselineTotal=%.6f meanPerLane=%.6f top1=%.6f "
                            + "top5=%.6f gini=%.6f thetaDim=%d meanRelL1=%.6f%n",
                    laneCount, total, total / laneCount, profile[0] / total,
                    sumFirst(profile, Math.min(5, laneCount)) / total,
                    gini(profile), generated.thetaNow.values().length, meanError);
        }
        Calibration base = TRBReviewerOlistDynamicDgpV2.calibrate(weekly, 23);
        for (double sharePersistence : new double[]{0.205, 0.50, 0.80}) {
            ReplicationData generated = TRBReviewerOlistDynamicDgpV2.generate(
                    base, 20_000, 1_000, 100, 91_337L,
                    InnovationDistribution.LOGNORMAL, 0.345,
                    sharePersistence);
            DynamicMetrics metrics = dynamicMetrics(generated, base.baselineDemand());
            System.out.printf(Locale.US,
                    "rho=%.3f totalMean=%.6f totalCV=%.6f totalLag1=%.6f "
                            + "shareSlope=%.6f zeroRate=%.6f%n",
                    sharePersistence, metrics.totalMean, metrics.totalCv,
                    metrics.totalLag1, metrics.shareSlope, metrics.zeroRate);
        }
        System.out.println("OLIST_DYNAMIC_DGP_V2_SELF_CHECK=PASSED");
    }

    private static DynamicMetrics dynamicMetrics(ReplicationData generated,
                                                  double[] baseline) {
        int n = generated.trainingSamples.size();
        double[] totals = new double[n];
        double zeros = 0.0;
        for (int t = 0; t < n; t++) {
            double[] demand = generated.trainingSamples.get(t).demand();
            totals[t] = sum(demand);
            for (double value : demand) if (value <= 0.0) zeros++;
        }
        double mean = Arrays.stream(totals).average().orElseThrow();
        double variance = 0.0;
        for (double total : totals) variance += (total - mean) * (total - mean) / n;
        double lagNumerator = 0.0;
        double lagDenominatorLeft = 0.0;
        double lagDenominatorRight = 0.0;
        double leftMean = Arrays.stream(totals, 0, n - 1).average().orElseThrow();
        double rightMean = Arrays.stream(totals, 1, n).average().orElseThrow();
        double shareNumerator = 0.0;
        double shareDenominator = 0.0;
        double baselineTotal = sum(baseline);
        for (int t = 1; t < n; t++) {
            double left = totals[t - 1] - leftMean;
            double right = totals[t] - rightMean;
            lagNumerator += left * right;
            lagDenominatorLeft += left * left;
            lagDenominatorRight += right * right;
            if (totals[t - 1] <= 0.0 || totals[t] <= 0.0) continue;
            double[] previous = generated.trainingSamples.get(t - 1).demand();
            double[] current = generated.trainingSamples.get(t).demand();
            for (int j = 0; j < baseline.length; j++) {
                double targetShare = baseline[j] / baselineTotal;
                double previousDeviation = previous[j] / totals[t - 1] - targetShare;
                double currentDeviation = current[j] / totals[t] - targetShare;
                shareNumerator += previousDeviation * currentDeviation;
                shareDenominator += previousDeviation * previousDeviation;
            }
        }
        return new DynamicMetrics(mean, Math.sqrt(variance) / mean,
                lagNumerator / Math.sqrt(lagDenominatorLeft * lagDenominatorRight),
                shareNumerator / shareDenominator,
                zeros / (n * baseline.length));
    }

    private static void verifyStructure(ReplicationData generated, int laneCount) {
        if (generated.laneNames.size() != laneCount
                || generated.baselineDemand.length != laneCount
                || generated.thetaNow.values().length != 3 * laneCount
                || generated.trainingSamples.size() != 2_000
                || generated.oosSamples.size() != 20_000) {
            throw new IllegalStateException("Unexpected generated dimensions for J=" + laneCount);
        }
        for (Sample sample : generated.trainingSamples) verifyDemand(sample, laneCount);
        for (Sample sample : generated.oosSamples) verifyDemand(sample, laneCount);
    }

    private static void verifyDemand(Sample sample, int laneCount) {
        if (sample.demand().length != laneCount) {
            throw new IllegalStateException("Unexpected demand dimension.");
        }
        for (double value : sample.demand()) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalStateException("Demand must be finite and nonnegative.");
            }
        }
    }

    private static double conditionalMeanRelativeError(ReplicationData generated) {
        double[] empirical = new double[generated.conditionalMean.length];
        for (Sample sample : generated.oosSamples) {
            for (int j = 0; j < empirical.length; j++) {
                empirical[j] += sample.demand()[j] / generated.oosSamples.size();
            }
        }
        double absoluteError = 0.0;
        double target = 0.0;
        for (int j = 0; j < empirical.length; j++) {
            absoluteError += Math.abs(empirical[j] - generated.conditionalMean[j]);
            target += generated.conditionalMean[j];
        }
        return absoluteError / target;
    }

    private static double gini(double[] descendingValues) {
        double[] ascending = descendingValues.clone();
        Arrays.sort(ascending);
        double weighted = 0.0;
        double total = 0.0;
        for (int i = 0; i < ascending.length; i++) {
            weighted += (i + 1.0) * ascending[i];
            total += ascending[i];
        }
        return 2.0 * weighted / (ascending.length * total)
                - (ascending.length + 1.0) / ascending.length;
    }

    private static double sumFirst(double[] values, int count) {
        double total = 0.0;
        for (int i = 0; i < count; i++) total += values[i];
        return total;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static void reverse(double[] values) {
        for (int left = 0, right = values.length - 1; left < right; left++, right--) {
            double swap = values[left];
            values[left] = values[right];
            values[right] = swap;
        }
    }

    private record DynamicMetrics(double totalMean,
                                  double totalCv,
                                  double totalLag1,
                                  double shareSlope,
                                  double zeroRate) {
    }
}
