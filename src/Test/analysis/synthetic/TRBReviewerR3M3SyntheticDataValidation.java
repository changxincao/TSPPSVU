package Test.analysis.synthetic;

import Basic.Sample;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;

import java.util.Locale;

/** Data-only validation for Reviewer 3, Comment 3. No optimization model is solved. */
public final class TRBReviewerR3M3SyntheticDataValidation {
    private static final int CONDITIONAL_OOS_DRAWS = 20_000;
    private static final int WARMUP_REPLICATIONS = 10_000;

    private TRBReviewerR3M3SyntheticDataValidation() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);

        for (InnovationDistribution distribution : InnovationDistribution.values()) {
            validateCell(distribution, 0.15);
            validateCell(distribution, 0.30);
        }
        validateObservedLagViews();
        compareWarmupLengths();
        System.out.println("VALIDATION_OK");
    }

    private static void validateCell(InnovationDistribution distribution, double cv) {
        Settings settings = baseSettings();
        settings.innovationDistribution = distribution;
        settings.innovationCv = cv;
        settings.oosSampleCount = CONDITIONAL_OOS_DRAWS;

        ReplicationData first = TRBReviewerR3M3IndependentPathDemandGenerator.generate(settings);
        ReplicationData repeated = TRBReviewerR3M3IndependentPathDemandGenerator.generate(settings.copy());
        Settings differentSeedSettings = settings.copy();
        differentSeedSettings.replicationSeed++;
        ReplicationData differentSeed = TRBReviewerR3M3IndependentPathDemandGenerator.generate(differentSeedSettings);

        require(first.trainingSamples.size() == settings.trainingSampleCount, "training size");
        require(first.oosSamples.size() == settings.oosSampleCount, "OOS size");
        require(first.thetaNow.dim() == settings.observedLagPeriods * settings.laneCount, "theta dimension");
        require(allNonnegative(first), "nonnegative demand");
        require(queryThetaMatchesHistory(first, settings), "theta ordering");
        require(fingerprint(first) == fingerprint(repeated), "same-seed reproducibility");
        require(fingerprint(first) != fingerprint(differentSeed), "different-seed variation");

        double aggregateMeanError = aggregateConditionalMeanError(first);
        double empiricalCv = averageInnovationCv(first);
        require(aggregateMeanError < 0.02, "conditional mean error");
        require(Math.abs(empiricalCv - cv) < 0.02, "innovation CV");

        System.out.printf(Locale.US,
                "cell distribution=%s cv=%.2f thetaDim=%d conditionalMeanError=%.6f empiricalCv=%.6f%n",
                distribution,
                cv,
                first.thetaNow.dim(),
                aggregateMeanError,
                empiricalCv);
    }

    private static void validateObservedLagViews() {
        Settings k1Settings = baseSettings();
        k1Settings.observedLagPeriods = 1;
        Settings k2Settings = k1Settings.copy();
        k2Settings.observedLagPeriods = 2;
        Settings k3Settings = k1Settings.copy();
        k3Settings.observedLagPeriods = 3;

        ReplicationData k1 = TRBReviewerR3M3IndependentPathDemandGenerator.generate(k1Settings);
        ReplicationData k2 = TRBReviewerR3M3IndependentPathDemandGenerator.generate(k2Settings);
        ReplicationData k3 = TRBReviewerR3M3IndependentPathDemandGenerator.generate(k3Settings);

        require(k1.thetaNow.dim() == 23, "k1 theta dimension");
        require(k2.thetaNow.dim() == 46, "k2 theta dimension");
        require(k3.thetaNow.dim() == 69, "k3 theta dimension");
        require(prefixEquals(k1.thetaNow.values(), k2.thetaNow.values()), "k1/k2 theta prefix");
        require(prefixEquals(k2.thetaNow.values(), k3.thetaNow.values()), "k2/k3 theta prefix");
        require(demandsEqual(k1, k2) && demandsEqual(k2, k3), "observed k must not change demand paths");
        System.out.println("lagViewCheck k1=23 k2=46 k3=69 underlyingPathsIdentical=true");
    }

    private static void compareWarmupLengths() {
        RunningStats warmup50 = new RunningStats();
        RunningStats warmup100 = new RunningStats();

        for (int r = 0; r < WARMUP_REPLICATIONS; r++) {
            Settings s50 = baseSettings();
            s50.trainingSampleCount = 1;
            s50.oosSampleCount = 1;
            s50.warmupPeriods = 50;
            s50.replicationSeed = 10_000L + r;
            warmup50.add(latestTotal(TRBReviewerR3M3IndependentPathDemandGenerator.generate(s50)));

            Settings s100 = s50.copy();
            s100.warmupPeriods = 100;
            warmup100.add(latestTotal(TRBReviewerR3M3IndependentPathDemandGenerator.generate(s100)));
        }

        double meanRelativeDifference = relativeDifference(warmup50.mean(), warmup100.mean());
        double sdRelativeDifference = relativeDifference(warmup50.standardDeviation(), warmup100.standardDeviation());
        System.out.printf(Locale.US,
                "warmupCheck reps=%d B50(mean=%.6f,sd=%.6f) B100(mean=%.6f,sd=%.6f) "
                        + "meanRelDiff=%.6f sdRelDiff=%.6f%n",
                WARMUP_REPLICATIONS,
                warmup50.mean(),
                warmup50.standardDeviation(),
                warmup100.mean(),
                warmup100.standardDeviation(),
                meanRelativeDifference,
                sdRelativeDifference);
    }

    private static Settings baseSettings() {
        Settings settings = new Settings();
        settings.laneCount = 23;
        settings.trainingSampleCount = 50;
        settings.observedLagPeriods = 3;
        settings.warmupPeriods = 50;
        settings.oosSampleCount = 1000;
        settings.meanDemandPerLane = 100.0;
        settings.laneScaleLower = 0.80;
        settings.laneScaleUpper = 1.20;
        settings.latentCrossLaneCorrelation = 0.10;
        settings.baselineSeed = 20260808L;
        settings.replicationSeed = 1L;
        return settings;
    }

    private static boolean allNonnegative(ReplicationData data) {
        for (Sample sample : data.trainingSamples) {
            for (double demand : sample.demand()) if (!(demand >= 0.0) || !Double.isFinite(demand)) return false;
        }
        for (Sample sample : data.oosSamples) {
            for (double demand : sample.demand()) if (!(demand >= 0.0) || !Double.isFinite(demand)) return false;
        }
        return true;
    }

    private static boolean queryThetaMatchesHistory(ReplicationData data, Settings settings) {
        double[] theta = data.thetaNow.values();
        for (int lag = 0; lag < settings.observedLagPeriods; lag++) {
            for (int j = 0; j < settings.laneCount; j++) {
                double expected = data.queryHistoryLatestFirst[lag][j];
                double actual = theta[lag * settings.laneCount + j];
                if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) return false;
            }
        }
        return true;
    }

    private static double aggregateConditionalMeanError(ReplicationData data) {
        double observed = 0.0;
        for (Sample sample : data.oosSamples) observed += sum(sample.demand());
        observed /= data.oosSamples.size();
        return relativeDifference(observed, sum(data.conditionalMean));
    }

    private static double averageInnovationCv(ReplicationData data) {
        int J = data.conditionalMean.length;
        int M = data.oosSamples.size();
        double cvSum = 0.0;
        for (int j = 0; j < J; j++) {
            double mean = 0.0;
            for (Sample sample : data.oosSamples) mean += sample.demand()[j] / data.conditionalMean[j];
            mean /= M;

            double variance = 0.0;
            for (Sample sample : data.oosSamples) {
                double innovation = sample.demand()[j] / data.conditionalMean[j];
                double delta = innovation - mean;
                variance += delta * delta;
            }
            variance /= Math.max(1, M - 1);
            cvSum += Math.sqrt(variance) / mean;
        }
        return cvSum / J;
    }

    private static double latestTotal(ReplicationData data) {
        return sum(data.queryHistoryLatestFirst[0]);
    }

    private static long fingerprint(ReplicationData data) {
        long value = 1125899906842597L;
        for (Sample sample : data.trainingSamples) {
            for (double x : sample.theta.values()) value = 31L * value + Double.doubleToLongBits(x);
            for (double x : sample.demand()) value = 31L * value + Double.doubleToLongBits(x);
        }
        for (double x : data.thetaNow.values()) value = 31L * value + Double.doubleToLongBits(x);
        for (Sample sample : data.oosSamples) {
            for (double x : sample.demand()) value = 31L * value + Double.doubleToLongBits(x);
        }
        return value;
    }

    private static boolean prefixEquals(double[] shorter, double[] longer) {
        if (shorter.length > longer.length) return false;
        for (int i = 0; i < shorter.length; i++) {
            if (Double.doubleToLongBits(shorter[i]) != Double.doubleToLongBits(longer[i])) return false;
        }
        return true;
    }

    private static boolean demandsEqual(ReplicationData left, ReplicationData right) {
        if (left.trainingSamples.size() != right.trainingSamples.size()
                || left.oosSamples.size() != right.oosSamples.size()) return false;
        for (int s = 0; s < left.trainingSamples.size(); s++) {
            if (!arrayEquals(left.trainingSamples.get(s).demand(), right.trainingSamples.get(s).demand())) return false;
        }
        for (int m = 0; m < left.oosSamples.size(); m++) {
            if (!arrayEquals(left.oosSamples.get(m).demand(), right.oosSamples.get(m).demand())) return false;
        }
        return arrayEquals(left.conditionalMean, right.conditionalMean);
    }

    private static boolean arrayEquals(double[] left, double[] right) {
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) {
            if (Double.doubleToLongBits(left[i]) != Double.doubleToLongBits(right[i])) return false;
        }
        return true;
    }

    private static double relativeDifference(double a, double b) {
        return Math.abs(a - b) / Math.max(1e-12, Math.abs(b));
    }

    private static double sum(double[] values) {
        double out = 0.0;
        for (double value : values) out += value;
        return out;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Validation failed: " + label);
    }

    private static final class RunningStats {
        private int count;
        private double sum;
        private double sumSquares;

        void add(double value) {
            count++;
            sum += value;
            sumSquares += value * value;
        }

        double mean() {
            return sum / count;
        }

        double standardDeviation() {
            double variance = (sumSquares - sum * sum / count) / Math.max(1, count - 1);
            return Math.sqrt(Math.max(0.0, variance));
        }
    }
}
