package Test.analysis.synthetic;

import Basic.Sample;
import Test.analysis.synthetic.TRBReviewerDecisionRelevantDemandGenerator.Mode;
import Test.analysis.synthetic.TRBReviewerDecisionRelevantDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;

import java.util.Locale;

/** Monte Carlo check of the coupled-regime conditional-mean calculation. */
public final class TRBReviewerDecisionRelevantDemandGeneratorSelfCheck {
    private TRBReviewerDecisionRelevantDemandGeneratorSelfCheck() {
    }

    public static void main(String[] args) {
        double[] baseline = TRBReviewerGroupSpecializedProcurementFactory.moderateBaseline(
                23, 2300.0, 0.65, 20260809L);
        int[] groups = TRBReviewerGroupSpecializedProcurementFactory.balancedLaneGroups(
                23, 3, 20260810L);

        Settings settings = new Settings();
        settings.mode = Mode.MARKOV_GROUP_SHARE;
        settings.trainingSampleCount = 3;
        settings.observedLagPeriods = 3;
        settings.warmupPeriods = 60;
        settings.oosSampleCount = 200_000;
        settings.innovationCv = 0.345;
        settings.latentCrossLaneCorrelation = 0.30;
        settings.activeGroupMultiplier = 2.0;
        settings.inactiveGroupMultiplier = 1.0;
        settings.regimeShareWeight = 1.0;
        settings.totalRegimeLogStep = Math.log(1.30);
        settings.totalRegimePersistence = 0.95;
        settings.orderedTotalRegimeTransitions = true;
        settings.coupledTotalAndShareRegime = true;

        ReplicationData data = TRBReviewerDecisionRelevantDemandGenerator.generate(
                settings, baseline, groups, 1_000_001L, 2_001_001L);
        double[] sampleMean = new double[baseline.length];
        for (Sample sample : data.oosSamples) {
            double[] demand = sample.demand();
            for (int j = 0; j < demand.length; j++) {
                if (!(demand[j] > 0.0) || !Double.isFinite(demand[j])) {
                    throw new AssertionError("Invalid demand at lane " + j);
                }
                sampleMean[j] += demand[j] / settings.oosSampleCount;
            }
        }

        double absoluteError = 0.0;
        double targetTotal = 0.0;
        for (int j = 0; j < sampleMean.length; j++) {
            absoluteError += Math.abs(sampleMean[j] - data.conditionalMean[j]);
            targetTotal += data.conditionalMean[j];
        }
        double normalizedL1 = absoluteError / targetTotal;
        if (normalizedL1 > 0.01) {
            throw new AssertionError("Conditional-mean Monte Carlo error=" + normalizedL1);
        }
        System.out.printf(Locale.US,
                "PASS oos=%d normalizedL1=%.8f%n",
                settings.oosSampleCount, normalizedL1);
    }
}
