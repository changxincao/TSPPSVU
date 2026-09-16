package Test.analysis.synthetic;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.WeeklyWideLoader;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Builds a Table-2 semi-synthetic instance around the observed Olist lane
 * means while retaining the independent three-lag demand DGP.
 *
 * <p>Only the 104-week lane means and names are read from the observed data.
 * Training paths, the query and OOS innovations are newly generated.  The
 * procurement parameters use the original Table-2 generator and seed zero.</p>
 */
public final class TRBReviewerOlistCalibratedLog1pInstanceBuilder {

    private static final int CARRIER_COUNT = 10;
    private static final int PROCUREMENT_SEED = 0;

    private TRBReviewerOlistCalibratedLog1pInstanceBuilder() {
    }

    /** Usage: {@code <weekly-wide-csv> <output-directory> <replication-seed> [oos-draws]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException(
                    "Usage: <weekly-wide-csv> <output-directory> <replication-seed> [oos-draws]");
        }

        Path weeklyCsv = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(weeklyCsv);
        double[] baseline = laneMeans(weekly.periods);

        Settings settings = new Settings();
        settings.laneCount = baseline.length;
        settings.replicationSeed = Long.parseLong(args[2]);
        settings.oosSampleCount = args.length == 4 ? Integer.parseInt(args[3]) : 200;
        settings.trainingSampleCount = 50;
        settings.observedLagPeriods = 3;
        settings.innovationDistribution = InnovationDistribution.LOGNORMAL;
        settings.innovationCv = 0.30;
        settings.latentCrossLaneCorrelation = 0.30;
        settings.longRunWeight = 0.20;
        settings.globalHistoryWeight = 0.60;
        settings.laneHistoryWeight = 0.20;

        ReplicationData generated =
                TRBReviewerR3M3IndependentPathDemandGenerator.generate(settings, baseline);
        log1pContexts(generated);
        ReplicationData demand = new ReplicationData(
                weekly.laneNames, generated.baselineDemand, generated.trainingSamples,
                generated.thetaNow, generated.oosSamples, generated.conditionalMean,
                generated.queryHistoryLatestFirst);

        Config procurementConfig = new Config();
        procurementConfig.seed = PROCUREMENT_SEED;
        procurementConfig.enforceDemandEquality = true;
        ProcurementParams procurement = InstanceGenerator.generate(
                CARRIER_COUNT, baseline, new InstanceGenerator.GenConfig(), procurementConfig);

        TRBReviewerR3M3SyntheticInstanceIO.save(
                outputDirectory, settings, demand, procurement);
        Files.write(outputDirectory.resolve("experiment_provenance.properties"), List.of(
                        "# Olist-calibrated semi-synthetic Table 2 mechanism gate",
                        "weeklyInput=" + weeklyCsv,
                        "baselineDefinition=LANE_MEAN_OVER_ALL_OBSERVED_WEEKS",
                        "contextTransform=LOG1P_THEN_TRAINING_ONLY_ZSCORE",
                        "dynamicsProfile=COMMON_FACTOR_CORRELATED",
                        "procurementFactory=InstanceGenerator.DEFAULT",
                        "procurementSeed=" + PROCUREMENT_SEED,
                        "carrierCount=" + CARRIER_COUNT),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("OLIST_CALIBRATED_LOG1P_INSTANCE_SAVED " + outputDirectory);
    }

    private static double[] laneMeans(List<PeriodData> periods) {
        if (periods.isEmpty()) throw new IllegalArgumentException("Weekly input is empty.");
        int laneCount = periods.get(0).demandSum.length;
        double[] mean = new double[laneCount];
        for (PeriodData period : periods) {
            if (period.demandSum.length != laneCount) {
                throw new IllegalArgumentException("Weekly input has inconsistent lane counts.");
            }
            for (int j = 0; j < laneCount; j++) mean[j] += period.demandSum[j];
        }
        for (int j = 0; j < laneCount; j++) mean[j] /= periods.size();
        return mean;
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
