package Test.analysis.synthetic;

import Basic.Sample;
import Test.analysis.synthetic.TRBReviewerDecisionRelevantDemandGenerator.Mode;
import Test.analysis.synthetic.TRBReviewerDecisionRelevantDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Structural and Monte Carlo checks for the one-path, one-query protocol. */
public final class TRBReviewerContinuousPathDemandGeneratorSelfCheck {
    private TRBReviewerContinuousPathDemandGeneratorSelfCheck() {
    }

    public static void main(String[] args) {
        double[] baseline = TRBReviewerGroupSpecializedProcurementFactory.moderateBaseline(
                23, 2300.0, 0.65, 20260809L);
        int[] groups = TRBReviewerGroupSpecializedProcurementFactory.balancedLaneGroups(
                23, 3, 20260810L);
        Settings settings = settings(20_000);

        ReplicationData data = TRBReviewerDecisionRelevantDemandGenerator.generateContinuousPath(
                settings, baseline, groups, 1_000_101L, 2_101_001L);
        ReplicationData repeated = TRBReviewerDecisionRelevantDemandGenerator.generateContinuousPath(
                settings, baseline, groups, 1_000_101L, 2_101_001L);
        ReplicationData differentOos = TRBReviewerDecisionRelevantDemandGenerator.generateContinuousPath(
                settings, baseline, groups, 1_000_101L, 2_101_002L);
        ReplicationData pairedIndependent =
                TRBReviewerDecisionRelevantDemandGenerator
                        .generateIndependentTrainingAtContinuousQuery(
                                settings, baseline, groups, 1_000_101L, 2_101_001L);

        require(data.trainingSamples.size() == settings.trainingSampleCount,
                "training sample count");
        require(data.oosSamples.size() == settings.oosSampleCount, "OOS sample count");
        require(consecutive(data.trainingSamples, baseline.length),
                "training lag windows are not consecutive");
        require(equals(data.trainingSamples.get(data.trainingSamples.size() - 1).demand(),
                data.queryHistoryLatestFirst[0]), "terminal history does not contain last response");
        require(equals(data.thetaNow.values(), historyTheta(data.queryHistoryLatestFirst, 3)),
                "thetaNow does not match terminal history");
        for (Sample sample : data.oosSamples) {
            require(equals(sample.theta.values(), data.thetaNow.values()),
                    "OOS context changed across conditional draws");
        }
        require(sameReplication(data, repeated), "same seeds are not reproducible");
        require(sameTrainingAndQuery(data, differentOos),
                "OOS seed changed training path or terminal query");
        require(!equals(data.oosSamples.get(0).demand(), differentOos.oosSamples.get(0).demand()),
                "OOS seed did not change held-out demand");
        require(equals(data.thetaNow.values(), pairedIndependent.thetaNow.values())
                        && sameSamples(data.oosSamples, pairedIndependent.oosSamples),
                "paired control does not share terminal query and OOS draws");

        Settings settings100 = settings(20_000);
        settings100.trainingSampleCount = 100;
        ReplicationData nested50 =
                TRBReviewerDecisionRelevantDemandGenerator.generateContinuousPath(
                        settings, baseline, groups, 1_000_101L, 2_101_001L, 100);
        ReplicationData nested100 =
                TRBReviewerDecisionRelevantDemandGenerator.generateContinuousPath(
                        settings100, baseline, groups, 1_000_101L, 2_101_001L, 100);
        require(equals(nested50.thetaNow.values(), nested100.thetaNow.values())
                        && equals(nested50.conditionalMean, nested100.conditionalMean)
                        && sameSamples(nested50.oosSamples, nested100.oosSamples),
                "nested training windows do not share terminal query and OOS draws");
        require(sameSamples(nested50.trainingSamples,
                        nested100.trainingSamples.subList(50, 100)),
                "50-sample window is not the final half of the 100-sample path");

        double[] sampleMean = new double[baseline.length];
        for (Sample sample : data.oosSamples) {
            double[] demand = sample.demand();
            for (int j = 0; j < demand.length; j++) sampleMean[j] += demand[j] / settings.oosSampleCount;
        }
        double absoluteError = 0.0;
        double targetTotal = 0.0;
        for (int j = 0; j < sampleMean.length; j++) {
            absoluteError += Math.abs(sampleMean[j] - data.conditionalMean[j]);
            targetTotal += data.conditionalMean[j];
        }
        double normalizedL1 = absoluteError / targetTotal;
        require(normalizedL1 <= 0.02, "conditional-mean Monte Carlo error=" + normalizedL1);
        System.out.printf(Locale.US,
                "PASS training=%d oos=%d normalizedL1=%.8f%n",
                data.trainingSamples.size(), data.oosSamples.size(), normalizedL1);
    }

    private static Settings settings(int oosDraws) {
        Settings settings = new Settings();
        settings.mode = Mode.MARKOV_GROUP_SHARE;
        settings.trainingSampleCount = 50;
        settings.observedLagPeriods = 3;
        settings.warmupPeriods = 60;
        settings.oosSampleCount = oosDraws;
        settings.innovationCv = 0.345;
        settings.latentCrossLaneCorrelation = 0.30;
        settings.regimePersistence = 0.90;
        settings.activeGroupMultiplier = 2.00;
        settings.inactiveGroupMultiplier = 1.00;
        settings.regimeShareWeight = 1.00;
        settings.totalRegimeLogStep = Math.log(1.30);
        settings.totalRegimePersistence = 0.95;
        settings.orderedTotalRegimeTransitions = true;
        settings.coupledTotalAndShareRegime = true;
        return settings;
    }

    private static boolean consecutive(List<Sample> samples, int lanes) {
        for (int index = 0; index + 1 < samples.size(); index++) {
            double[] currentTheta = samples.get(index).theta.values();
            double[] nextTheta = samples.get(index + 1).theta.values();
            if (!equals(samples.get(index).demand(), Arrays.copyOfRange(nextTheta, 0, lanes))) {
                return false;
            }
            if (!equals(Arrays.copyOfRange(currentTheta, 0, 2 * lanes),
                    Arrays.copyOfRange(nextTheta, lanes, 3 * lanes))) {
                return false;
            }
        }
        return true;
    }

    private static double[] historyTheta(double[][] latestFirst, int lags) {
        double[] out = new double[lags * latestFirst[0].length];
        int position = 0;
        for (int lag = 0; lag < lags; lag++) {
            System.arraycopy(latestFirst[lag], 0, out, position, latestFirst[lag].length);
            position += latestFirst[lag].length;
        }
        return out;
    }

    private static boolean sameReplication(ReplicationData left, ReplicationData right) {
        return sameTrainingAndQuery(left, right)
                && sameSamples(left.oosSamples, right.oosSamples);
    }

    private static boolean sameTrainingAndQuery(ReplicationData left, ReplicationData right) {
        return sameSamples(left.trainingSamples, right.trainingSamples)
                && equals(left.thetaNow.values(), right.thetaNow.values())
                && equals(left.conditionalMean, right.conditionalMean);
    }

    private static boolean sameSamples(List<Sample> left, List<Sample> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (!equals(left.get(index).demand(), right.get(index).demand())
                    || !equals(left.get(index).theta.values(), right.get(index).theta.values())) {
                return false;
            }
        }
        return true;
    }

    private static boolean equals(double[] left, double[] right) {
        return Arrays.equals(left, right);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
