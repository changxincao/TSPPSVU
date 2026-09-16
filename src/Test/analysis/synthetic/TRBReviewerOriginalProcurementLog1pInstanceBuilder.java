package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Builds the Table-2 mechanism gate with the accepted demand DGP and the
 * project's original, frozen procurement-parameter generator.
 *
 * <p>The demand side uses the three-lag common-factor profile and LOG1P
 * contexts.  The procurement side uses full lane coverage, the original
 * capacity/MQC ranges, {@code h_i=max_j r_ij}, and one fixed procurement seed.
 * The class only generates and saves an instance; it never calls a solver.</p>
 */
public final class TRBReviewerOriginalProcurementLog1pInstanceBuilder {

    private static final int CARRIER_COUNT = 10;
    private static final long PROCUREMENT_SEED = 20_260_808L;

    private TRBReviewerOriginalProcurementLog1pInstanceBuilder() {
    }

    /** Usage: {@code <output-directory> <replication-seed> [oos-draws]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: <output-directory> <replication-seed> [oos-draws]");
        }

        Path outputDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Settings settings = new Settings();
        settings.replicationSeed = Long.parseLong(args[1]);
        settings.oosSampleCount = args.length == 3 ? Integer.parseInt(args[2]) : 200;
        settings.trainingSampleCount = 50;
        settings.observedLagPeriods = 3;
        settings.innovationDistribution = InnovationDistribution.LOGNORMAL;
        settings.innovationCv = 0.30;
        settings.latentCrossLaneCorrelation = 0.30;
        settings.longRunWeight = 0.20;
        settings.globalHistoryWeight = 0.60;
        settings.laneHistoryWeight = 0.20;

        ReplicationData demand =
                TRBReviewerR3M3IndependentPathDemandGenerator.generate(settings);
        log1pContexts(demand);
        ProcurementParams procurement = TRBReviewerSyntheticProcurementFactory
                .generateScaleComparable(demand.baselineDemand, CARRIER_COUNT, PROCUREMENT_SEED)
                .params;

        TRBReviewerR3M3SyntheticInstanceIO.save(
                outputDirectory, settings, demand, procurement);
        Files.write(outputDirectory.resolve("experiment_provenance.properties"), List.of(
                        "# Table 2 mechanism gate; demand and procurement are generated independently",
                        "contextTransform=LOG1P_THEN_TRAINING_ONLY_ZSCORE",
                        "dynamicsProfile=COMMON_FACTOR_CORRELATED",
                        "procurementFactory=TRBReviewerSyntheticProcurementFactory.generateScaleComparable",
                        "procurementSeed=" + PROCUREMENT_SEED,
                        "carrierCount=" + CARRIER_COUNT),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("ORIGINAL_PROCUREMENT_LOG1P_INSTANCE_SAVED " + outputDirectory);
    }

    private static void log1pContexts(ReplicationData demand) {
        for (Sample sample : demand.trainingSamples) log1pInPlace(sample.theta.values());
        log1pInPlace(demand.thetaNow.values());
        for (Sample sample : demand.oosSamples) log1pInPlace(sample.theta.values());
    }

    private static void log1pInPlace(double[] values) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] < 0.0 || !Double.isFinite(values[index])) {
                throw new IllegalArgumentException(
                        "LOG1P context requires finite nonnegative values; index=" + index);
            }
            values[index] = Math.log1p(values[index]);
        }
    }
}
