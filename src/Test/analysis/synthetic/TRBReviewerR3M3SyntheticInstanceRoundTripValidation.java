package Test.analysis.synthetic;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticInstanceIO.StoredInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reviewer 3, Comment 3: pure-data save/load acceptance check.
 *
 * <p>Input: a small deterministic synthetic instance. Operation: save and load
 * every demand, context and procurement field, then compare double bit patterns.
 * Output: {@code ROUND_TRIP_OK} and the retained validation directory. No
 * optimization solver is instantiated or called.</p>
 */
public final class TRBReviewerR3M3SyntheticInstanceRoundTripValidation {
    private TRBReviewerR3M3SyntheticInstanceRoundTripValidation() {
    }

    public static void main(String[] args) throws IOException {
        Path parent = Path.of("tmp").toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path directory = args.length == 0
                ? Files.createTempDirectory(parent, "trb_r3m3_roundtrip_")
                : Path.of(args[0]).toAbsolutePath().normalize();

        Settings expectedSettings = validationSettings();
        ReplicationData expectedDemand =
                TRBReviewerR3M3IndependentPathDemandGenerator.generate(expectedSettings);
        ProcurementParams expectedParams =
                TRBReviewerSyntheticProcurementFactory.generateScaleComparable(
                        expectedDemand.baselineDemand, 5, 314159L).params;

        TRBReviewerR3M3SyntheticInstanceBuilder.buildAndSave(
                directory, expectedSettings, 5, 314159L);
        StoredInstance actual = TRBReviewerR3M3SyntheticInstanceIO.load(directory);

        assertSettingsEqual(expectedSettings, actual.settings);
        assertDemandEqual(expectedDemand, actual.demandData);
        require(actual.hasProcurementParams(), "Procurement parameters were not restored.");
        assertProcurementEqual(expectedParams, actual.procurementParams);
        System.out.println("ROUND_TRIP_OK " + directory);
    }

    private static Settings validationSettings() {
        Settings s = new Settings();
        s.laneCount = 7;
        s.trainingSampleCount = 11;
        s.observedLagPeriods = 2;
        s.warmupPeriods = 9;
        s.oosSampleCount = 13;
        s.meanDemandPerLane = 83.75;
        s.laneScaleLower = 0.71;
        s.laneScaleUpper = 1.39;
        s.innovationCv = 0.21;
        s.latentCrossLaneCorrelation = 0.17;
        s.longRunWeight = 0.25;
        s.globalHistoryWeight = 0.50;
        s.laneHistoryWeight = 0.25;
        s.innovationDistribution = InnovationDistribution.UNIFORM;
        s.baselineSeed = 9876543210123L;
        s.replicationSeed = -442211009988L;
        return s;
    }

    private static void assertSettingsEqual(Settings a, Settings b) {
        require(a.laneCount == b.laneCount, "laneCount differs.");
        require(a.trainingSampleCount == b.trainingSampleCount, "trainingSampleCount differs.");
        require(a.observedLagPeriods == b.observedLagPeriods, "observedLagPeriods differs.");
        require(a.warmupPeriods == b.warmupPeriods, "warmupPeriods differs.");
        require(a.oosSampleCount == b.oosSampleCount, "oosSampleCount differs.");
        same(a.meanDemandPerLane, b.meanDemandPerLane, "meanDemandPerLane");
        same(a.laneScaleLower, b.laneScaleLower, "laneScaleLower");
        same(a.laneScaleUpper, b.laneScaleUpper, "laneScaleUpper");
        same(a.innovationCv, b.innovationCv, "innovationCv");
        same(a.latentCrossLaneCorrelation, b.latentCrossLaneCorrelation,
                "latentCrossLaneCorrelation");
        same(a.longRunWeight, b.longRunWeight, "longRunWeight");
        same(a.globalHistoryWeight, b.globalHistoryWeight, "globalHistoryWeight");
        same(a.laneHistoryWeight, b.laneHistoryWeight, "laneHistoryWeight");
        require(a.innovationDistribution == b.innovationDistribution,
                "innovationDistribution differs.");
        require(a.baselineSeed == b.baselineSeed, "baselineSeed differs.");
        require(a.replicationSeed == b.replicationSeed, "replicationSeed differs.");
    }

    private static void assertDemandEqual(ReplicationData a, ReplicationData b) {
        require(a.laneNames.equals(b.laneNames), "laneNames differ.");
        vector(a.baselineDemand, b.baselineDemand, "baselineDemand");
        samples(a.trainingSamples, b.trainingSamples, "trainingSamples");
        vector(a.thetaNow.values(), b.thetaNow.values(), "thetaNow");
        samples(a.oosSamples, b.oosSamples, "oosSamples");
        vector(a.conditionalMean, b.conditionalMean, "conditionalMean");
        require(a.queryHistoryLatestFirst.length == b.queryHistoryLatestFirst.length,
                "query history lag count differs.");
        for (int lag = 0; lag < a.queryHistoryLatestFirst.length; lag++) {
            vector(a.queryHistoryLatestFirst[lag], b.queryHistoryLatestFirst[lag],
                    "queryHistory[" + lag + "]");
        }
    }

    private static void samples(List<Sample> a, List<Sample> b, String label) {
        require(a.size() == b.size(), label + " size differs.");
        for (int n = 0; n < a.size(); n++) {
            Sample x = a.get(n);
            Sample y = b.get(n);
            require(x.id == y.id, label + " id differs at " + n);
            same(x.weight, y.weight, label + " weight at " + n);
            period(x.period, y.period, label + " period at " + n);
            vector(x.theta.values(), y.theta.values(), label + " theta at " + n);
        }
    }

    private static void period(PeriodData a, PeriodData b, String label) {
        require(a.tIndex == b.tIndex, label + " tIndex differs.");
        require(a.startDate.equals(b.startDate), label + " startDate differs.");
        require(a.endDate.equals(b.endDate), label + " endDate differs.");
        require(a.holidayCount == b.holidayCount, label + " holidayCount differs.");
        same(a.avgFreightIndex, b.avgFreightIndex, label + " freight");
        same(a.avgConsumptionIndex, b.avgConsumptionIndex, label + " consumption");
        same(a.avgWEIIndex, b.avgWEIIndex, label + " WEI");
        vector(a.demandSum, b.demandSum, label + " demand");
    }

    private static void assertProcurementEqual(ProcurementParams a, ProcurementParams b) {
        require(a != null && b != null, "Procurement parameters are null.");
        require(a.carriers.equals(b.carriers), "carrier names differ.");
        require(a.I == b.I && a.J == b.J, "Procurement dimensions differ.");
        require(a.alpha == b.alpha && a.beta == b.beta, "alpha/beta differ.");
        vector(a.e, b.e, "e");
        vector(a.p, b.p, "p");
        vector(a.h, b.h, "h");
        vector(a.M, b.M, "M");
        for (int i = 0; i < a.I; i++) {
            vector(a.q[i], b.q[i], "q[" + i + "]");
            vector(a.r[i], b.r[i], "r[" + i + "]");
            for (int j = 0; j < a.J; j++) {
                require(a.eligible[i][j] == b.eligible[i][j],
                        "eligible differs at " + i + "," + j);
            }
        }
    }

    private static void vector(double[] a, double[] b, String label) {
        require(a.length == b.length, label + " length differs.");
        for (int i = 0; i < a.length; i++) same(a[i], b[i], label + " at " + i);
    }

    private static void same(double a, double b, String label) {
        require(Double.doubleToLongBits(a) == Double.doubleToLongBits(b), label + " differs.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
