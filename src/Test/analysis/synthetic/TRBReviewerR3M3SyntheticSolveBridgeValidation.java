package Test.analysis.synthetic;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.calculateHelper.KernelType;
import Model.SolveMode;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;

/**
 * Pure data-interface check for Reviewer 3, Comment 3.
 *
 * <p>It verifies DGP-to-{@code Data} conversion for the deterministic, SAA,
 * CSAA and RCSAA input modes. It does not call CPLEX/MOSEK or claim any model
 * performance result.</p>
 */
public final class TRBReviewerR3M3SyntheticSolveBridgeValidation {

    private TRBReviewerR3M3SyntheticSolveBridgeValidation() {
    }

    public static void main(String[] args) {
        Settings settings = new Settings();
        settings.trainingSampleCount = 12;
        settings.oosSampleCount = 20;
        settings.replicationSeed = 20260808L;
        ReplicationData replication = TRBReviewerR3M3IndependentPathDemandGenerator.generate(settings);

        Config instanceConfig = config(SolveMode.SAA);
        InstanceGenerator.GenConfig generation = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(
                10, replication.baselineDemand, generation, instanceConfig);

        double originalFirstTheta = replication.trainingSamples.get(0).theta.values()[0];
        validateMean(replication, params);
        validateWeighted(replication, params, SolveMode.SAA);
        validateWeighted(replication, params, SolveMode.CSAA);
        validateWeighted(replication, params, SolveMode.RCSAA);
        validateGaussianFloor(replication, params);
        require(Double.doubleToLongBits(originalFirstTheta)
                        == Double.doubleToLongBits(replication.trainingSamples.get(0).theta.values()[0]),
                "bridge mutated source replication");
        System.out.println("SOLVE_BRIDGE_VALIDATION_OK");
    }

    private static void validateGaussianFloor(ReplicationData replication,
                                              ProcurementParams params) {
        Config config = config(SolveMode.CSAA);
        config.kernelType = KernelType.GAUSSIAN;
        config.C_h = 0.01;
        var prepared = TRBReviewerR3M3SyntheticSolveBridge.prepare(replication, params, config);
        double sum = 0.0;
        for (Sample sample : prepared.solveData.samples) {
            require(sample.weight > 0.0, "Gaussian floored weight is positive");
            sum += sample.weight;
        }
        require(Math.abs(sum - 1.0) < 1e-10, "Gaussian floored weight sum");
    }

    private static void validateMean(ReplicationData replication, ProcurementParams params) {
        Config config = config(SolveMode.MeanDeterministic);
        TRBReviewerR3M3SyntheticSolveBridge.PreparedInput prepared =
                TRBReviewerR3M3SyntheticSolveBridge.prepareMeanDeterministic(
                        replication, params, config);
        require(prepared.solveData.samples.size() == 1, "mean scenario count");
        require(prepared.solveData.samples.get(0).weight == 1.0, "mean scenario weight");
        validateCommon(prepared.solveData, prepared.oosSamples.size(), replication, params);
    }

    private static void validateWeighted(ReplicationData replication,
                                         ProcurementParams params,
                                         SolveMode solveMode) {
        Config config = config(solveMode);
        TRBReviewerR3M3SyntheticSolveBridge.PreparedInput prepared =
                TRBReviewerR3M3SyntheticSolveBridge.prepare(replication, params, config);
        require(prepared.solveData.samples.size() == replication.trainingSamples.size(),
                solveMode + " training size");
        double sum = 0.0;
        for (Sample sample : prepared.solveData.samples) sum += sample.weight;
        require(Math.abs(sum - 1.0) < 1e-10, solveMode + " weight sum");
        validateCommon(prepared.solveData, prepared.oosSamples.size(), replication, params);
    }

    private static void validateCommon(Data data,
                                       int oosCount,
                                       ReplicationData replication,
                                       ProcurementParams params) {
        require(data.lanes.size() == params.J, "lane dimension");
        require(data.thetaNow.dim() == replication.thetaNow.dim(), "theta dimension");
        require(oosCount == replication.oosSamples.size(), "OOS count");
    }

    private static Config config(SolveMode solveMode) {
        Config config = new Config();
        config.solveMode = solveMode;
        config.k1LagPeriods = 3;
        config.standardizeTheta = true;
        config.C_h = 1.0;
        config.lambda = 1.0;
        config.enforceDemandEquality = true;
        return config;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Validation failed: " + label);
    }
}
