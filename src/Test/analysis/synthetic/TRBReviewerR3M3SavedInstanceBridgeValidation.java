package Test.analysis.synthetic;

import Helper.basicHelper.Config;
import Model.SolveMode;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticInstanceIO.StoredInstance;

import java.nio.file.Path;

/**
 * End-to-end data-interface check for Reviewer 3, Comment 3.
 *
 * <p>Input: a directory created by the synthetic instance builder. Operation:
 * load the exact saved demand/procurement data and prepare D, SAA, CSAA and
 * RCSAA {@code Data} objects. Output: a concise dimension/weight check. No
 * optimization solver is called.</p>
 */
public final class TRBReviewerR3M3SavedInstanceBridgeValidation {

    private TRBReviewerR3M3SavedInstanceBridgeValidation() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: <saved-instance-directory>");
        }
        StoredInstance stored = TRBReviewerR3M3SyntheticInstanceIO.load(
                Path.of(args[0]).toAbsolutePath().normalize());
        if (!stored.hasProcurementParams()) {
            throw new IllegalArgumentException("Saved instance has no procurement parameters.");
        }

        prepareAndCheck(stored, SolveMode.MeanDeterministic);
        prepareAndCheck(stored, SolveMode.SAA);
        prepareAndCheck(stored, SolveMode.CSAA);
        prepareAndCheck(stored, SolveMode.RCSAA);
        System.out.printf("SAVED_INSTANCE_BRIDGE_OK I=%d J=%d S=%d M=%d theta=%d%n",
                stored.procurementParams.I,
                stored.procurementParams.J,
                stored.demandData.trainingSamples.size(),
                stored.demandData.oosSamples.size(),
                stored.demandData.thetaNow.dim());
    }

    private static void prepareAndCheck(StoredInstance stored, SolveMode mode) {
        Config config = new Config();
        config.solveMode = mode;
        config.k1LagPeriods = stored.settings.observedLagPeriods;
        config.standardizeTheta = true;
        config.C_h = 1.0;
        config.lambda = 1.0;
        config.enforceDemandEquality = true;

        TRBReviewerR3M3SyntheticSolveBridge.PreparedInput prepared =
                mode == SolveMode.MeanDeterministic
                        ? TRBReviewerR3M3SyntheticSolveBridge.prepareMeanDeterministic(
                                stored.demandData, stored.procurementParams, config)
                        : TRBReviewerR3M3SyntheticSolveBridge.prepare(
                                stored.demandData, stored.procurementParams, config);
        if (prepared.solveData.params != stored.procurementParams) {
            throw new IllegalStateException("Bridge replaced the saved procurement parameters.");
        }
        if (prepared.oosSamples.size() != stored.settings.oosSampleCount) {
            throw new IllegalStateException("Bridge changed the OOS sample count.");
        }
    }
}
