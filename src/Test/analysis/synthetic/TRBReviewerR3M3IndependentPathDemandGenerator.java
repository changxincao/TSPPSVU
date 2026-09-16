package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.SampleBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Synthetic demand generator for Reviewer 3, Comment 3 (multiple DGPs).
 *
 * <p>Each training row is extracted from an independent dynamic path. A separate
 * path supplies the current context. Conditional OOS demands are then generated
 * by fixing that context and resampling only the next-period innovation.</p>
 *
 * <p>The true demand process has three lags. {@code observedLagPeriods} controls
 * whether the optimization method sees one, two, or all three lags; it never
 * changes the underlying path.</p>
 */
public final class TRBReviewerR3M3IndependentPathDemandGenerator {
    private static final int TRUE_LAG_PERIODS = 3;
    private static final double[] LAG_WEIGHTS = {0.50, 0.30, 0.20};
    private static final LocalDate BASE_DATE = LocalDate.of(2000, 1, 3);

    private TRBReviewerR3M3IndependentPathDemandGenerator() {
    }

    public enum InnovationDistribution {
        LOGNORMAL,
        UNIFORM
    }

    public static final class Settings {
        public int laneCount = 23;
        public int trainingSampleCount = 50;
        public int observedLagPeriods = 3;
        public int warmupPeriods = 50;
        public int oosSampleCount = 1000;

        public double meanDemandPerLane = 100.0;
        public double laneScaleLower = 0.80;
        public double laneScaleUpper = 1.20;
        public double innovationCv = 0.30;
        /** Gaussian-copula correlation parameter, not demand Pearson correlation. */
        public double latentCrossLaneCorrelation = 0.10;
        /** Convex weights in the next-period conditional mean. */
        public double longRunWeight = 0.20;
        public double globalHistoryWeight = 0.20;
        public double laneHistoryWeight = 0.60;

        public InnovationDistribution innovationDistribution = InnovationDistribution.LOGNORMAL;
        public long baselineSeed = 20260808L;
        public long replicationSeed = 1L;

        public Settings copy() {
            Settings c = new Settings();
            c.laneCount = laneCount;
            c.trainingSampleCount = trainingSampleCount;
            c.observedLagPeriods = observedLagPeriods;
            c.warmupPeriods = warmupPeriods;
            c.oosSampleCount = oosSampleCount;
            c.meanDemandPerLane = meanDemandPerLane;
            c.laneScaleLower = laneScaleLower;
            c.laneScaleUpper = laneScaleUpper;
            c.innovationCv = innovationCv;
            c.latentCrossLaneCorrelation = latentCrossLaneCorrelation;
            c.longRunWeight = longRunWeight;
            c.globalHistoryWeight = globalHistoryWeight;
            c.laneHistoryWeight = laneHistoryWeight;
            c.innovationDistribution = innovationDistribution;
            c.baselineSeed = baselineSeed;
            c.replicationSeed = replicationSeed;
            return c;
        }

        void validate() {
            if (laneCount <= 0) throw new IllegalArgumentException("laneCount must be positive.");
            if (trainingSampleCount <= 0) {
                throw new IllegalArgumentException("trainingSampleCount must be positive.");
            }
            if (observedLagPeriods < 1 || observedLagPeriods > TRUE_LAG_PERIODS) {
                throw new IllegalArgumentException("observedLagPeriods must be 1, 2, or 3.");
            }
            if (warmupPeriods < 0) throw new IllegalArgumentException("warmupPeriods cannot be negative.");
            if (oosSampleCount <= 0) throw new IllegalArgumentException("oosSampleCount must be positive.");
            if (!(meanDemandPerLane > 0.0)) {
                throw new IllegalArgumentException("meanDemandPerLane must be positive.");
            }
            if (!(laneScaleLower > 0.0) || laneScaleUpper < laneScaleLower) {
                throw new IllegalArgumentException("Invalid lane scale interval.");
            }
            if (!(innovationCv > 0.0)) throw new IllegalArgumentException("innovationCv must be positive.");
            if (innovationDistribution == InnovationDistribution.UNIFORM
                    && innovationCv >= 1.0 / Math.sqrt(3.0)) {
                throw new IllegalArgumentException("Uniform innovationCv must be below 1/sqrt(3).");
            }
            if (latentCrossLaneCorrelation < 0.0 || latentCrossLaneCorrelation >= 1.0) {
                throw new IllegalArgumentException("latentCrossLaneCorrelation must be in [0, 1).");
            }
            double dynamicWeightSum = longRunWeight + globalHistoryWeight + laneHistoryWeight;
            if (longRunWeight < 0.0 || globalHistoryWeight < 0.0 || laneHistoryWeight < 0.0
                    || Math.abs(dynamicWeightSum - 1.0) > 1e-12) {
                throw new IllegalArgumentException("Conditional-mean weights must be nonnegative and sum to one.");
            }
            if (innovationDistribution == null) {
                throw new IllegalArgumentException("innovationDistribution is required.");
            }
        }
    }

    public static final class ReplicationData {
        public final List<String> laneNames;
        public final double[] baselineDemand;
        public final List<Sample> trainingSamples;
        public final CovariateVector thetaNow;
        public final List<Sample> oosSamples;
        public final double[] conditionalMean;
        /** Query history in latest-first order: D_t, D_{t-1}, D_{t-2}. */
        public final double[][] queryHistoryLatestFirst;

        ReplicationData(List<String> laneNames,
                        double[] baselineDemand,
                        List<Sample> trainingSamples,
                        CovariateVector thetaNow,
                        List<Sample> oosSamples,
                        double[] conditionalMean,
                        double[][] queryHistoryLatestFirst) {
            this.laneNames = Collections.unmodifiableList(new ArrayList<>(laneNames));
            this.baselineDemand = baselineDemand.clone();
            this.trainingSamples = Collections.unmodifiableList(trainingSamples);
            this.thetaNow = thetaNow.copy();
            this.oosSamples = Collections.unmodifiableList(oosSamples);
            this.conditionalMean = conditionalMean.clone();
            this.queryHistoryLatestFirst = deepCopy(queryHistoryLatestFirst);
        }
    }

    public static ReplicationData generate(Settings settings) {
        settings.validate();
        return generateFromBaseline(settings, buildBaselineDemand(settings));
    }

    /**
     * Generates the same independent-path DGP around a caller-supplied lane
     * baseline.  This supports semi-synthetic experiments calibrated to an
     * observed lane-size distribution without reading any optimization data.
     */
    public static ReplicationData generate(Settings settings, double[] baselineDemand) {
        settings.validate();
        if (baselineDemand == null || baselineDemand.length != settings.laneCount) {
            throw new IllegalArgumentException(
                    "baselineDemand length must equal laneCount=" + settings.laneCount);
        }
        double[] baseline = baselineDemand.clone();
        for (int j = 0; j < baseline.length; j++) {
            if (!(baseline[j] > 0.0) || !Double.isFinite(baseline[j])) {
                throw new IllegalArgumentException(
                        "baselineDemand must be finite and positive; lane=" + j);
            }
        }
        return generateFromBaseline(settings, baseline);
    }

    private static ReplicationData generateFromBaseline(Settings settings, double[] baseline) {
        List<String> laneNames = buildLaneNames(settings.laneCount);
        List<Sample> training = new ArrayList<>(settings.trainingSampleCount);
        double trainingWeight = 1.0 / settings.trainingSampleCount;

        for (int s = 0; s < settings.trainingSampleCount; s++) {
            Random pathRandom = stream(settings.replicationSeed, 10_000L + s);
            PathState state = simulateCurrentState(baseline, settings, pathRandom);
            double[] response = drawNextDemand(state, baseline, settings, pathRandom);
            training.add(buildSample(
                    s,
                    trainingWeight,
                    state.historyLatestFirst,
                    response,
                    settings.observedLagPeriods,
                    laneNames));
        }

        Random queryRandom = stream(settings.replicationSeed, 20_000L);
        PathState queryState = simulateCurrentState(baseline, settings, queryRandom);
        double[] conditionalMean = conditionalMean(queryState.historyLatestFirst, baseline, settings);
        CovariateVector thetaNow = buildSample(
                0,
                1.0,
                queryState.historyLatestFirst,
                conditionalMean,
                settings.observedLagPeriods,
                laneNames).theta.copy();

        Random oosRandom = stream(settings.replicationSeed, 30_000L);
        List<Sample> oos = new ArrayList<>(settings.oosSampleCount);
        double oosWeight = 1.0 / settings.oosSampleCount;
        for (int m = 0; m < settings.oosSampleCount; m++) {
            double[] demand = multiply(
                    conditionalMean,
                    drawMeanOneInnovations(settings.laneCount, settings, oosRandom));
            PeriodData period = period(settings.observedLagPeriods, demand);
            oos.add(new Sample(m, period, thetaNow.copy(), oosWeight));
        }

        return new ReplicationData(
                laneNames,
                baseline,
                training,
                thetaNow,
                oos,
                conditionalMean,
                queryState.historyLatestFirst);
    }

    private static PathState simulateCurrentState(double[] baseline, Settings settings, Random random) {
        double[][] history = new double[TRUE_LAG_PERIODS][baseline.length];
        for (int lag = 0; lag < TRUE_LAG_PERIODS; lag++) history[lag] = baseline.clone();

        for (int t = 0; t < settings.warmupPeriods; t++) {
            double[] next = multiply(
                    conditionalMean(history, baseline, settings),
                    drawMeanOneInnovations(baseline.length, settings, random));
            shiftHistory(history, next);
        }
        return new PathState(history);
    }

    private static double[] drawNextDemand(PathState state,
                                           double[] baseline,
                                           Settings settings,
                                           Random random) {
        return multiply(
                conditionalMean(state.historyLatestFirst, baseline, settings),
                drawMeanOneInnovations(baseline.length, settings, random));
    }

    /**
     * Uses a convex combination of the long-run level, the lagged global state,
     * and the lagged lane state. Nonnegative weights summing to one make the
     * conditional mean nonnegative by construction.
     */
    private static double[] conditionalMean(double[][] historyLatestFirst,
                                            double[] baseline,
                                            Settings settings) {
        int J = baseline.length;
        double baselineTotal = sum(baseline);
        double[] weightedGlobal = new double[TRUE_LAG_PERIODS];
        for (int lag = 0; lag < TRUE_LAG_PERIODS; lag++) {
            weightedGlobal[lag] = sum(historyLatestFirst[lag]) / baselineTotal;
        }

        double globalState = dot(LAG_WEIGHTS, weightedGlobal);
        double[] mean = new double[J];
        for (int j = 0; j < J; j++) {
            double laneState = 0.0;
            for (int lag = 0; lag < TRUE_LAG_PERIODS; lag++) {
                laneState += LAG_WEIGHTS[lag] * historyLatestFirst[lag][j] / baseline[j];
            }
            mean[j] = baseline[j] * (
                    settings.longRunWeight
                            + settings.globalHistoryWeight * globalState
                            + settings.laneHistoryWeight * laneState);
        }
        return mean;
    }

    private static double[] drawMeanOneInnovations(int J, Settings settings, Random random) {
        double[] out = new double[J];
        double commonNormal = random.nextGaussian();
        double commonScale = Math.sqrt(settings.latentCrossLaneCorrelation);
        double idiosyncraticScale = Math.sqrt(1.0 - settings.latentCrossLaneCorrelation);

        if (settings.innovationDistribution == InnovationDistribution.LOGNORMAL) {
            double sigma = Math.sqrt(Math.log1p(settings.innovationCv * settings.innovationCv));
            for (int j = 0; j < J; j++) {
                double z = commonScale * commonNormal + idiosyncraticScale * random.nextGaussian();
                out[j] = Math.exp(-0.5 * sigma * sigma + sigma * z);
            }
        } else {
            double halfWidth = Math.sqrt(3.0) * settings.innovationCv;
            for (int j = 0; j < J; j++) {
                double z = commonScale * commonNormal + idiosyncraticScale * random.nextGaussian();
                double uniform01 = normalCdf(z);
                out[j] = 1.0 + halfWidth * (2.0 * uniform01 - 1.0);
            }
        }
        return out;
    }

    private static double[] buildBaselineDemand(Settings settings) {
        Random random = new Random(settings.baselineSeed);
        double[] scale = new double[settings.laneCount];
        double scaleMean = 0.0;
        for (int j = 0; j < scale.length; j++) {
            scale[j] = settings.laneScaleLower
                    + (settings.laneScaleUpper - settings.laneScaleLower) * random.nextDouble();
            scaleMean += scale[j];
        }
        scaleMean /= scale.length;

        double[] baseline = new double[scale.length];
        for (int j = 0; j < baseline.length; j++) {
            baseline[j] = settings.meanDemandPerLane * scale[j] / scaleMean;
        }
        return baseline;
    }

    private static Sample buildSample(int id,
                                      double weight,
                                      double[][] historyLatestFirst,
                                      double[] response,
                                      int observedLagPeriods,
                                      List<String> laneNames) {
        List<PeriodData> periods = new ArrayList<>(observedLagPeriods + 1);
        for (int position = 0; position < observedLagPeriods; position++) {
            int lag = observedLagPeriods - 1 - position;
            periods.add(period(position, historyLatestFirst[lag]));
        }
        periods.add(period(observedLagPeriods, response));

        Config config = sampleBuilderConfig(observedLagPeriods);
        SampleBuilder.BuildResult built = SampleBuilder.buildFromPeriods(periods, laneNames, config);
        if (built.samples.size() != 1) {
            throw new IllegalStateException("Expected one sample, got " + built.samples.size());
        }
        Sample source = built.samples.get(0);
        return new Sample(id, source.period, source.theta.copy(), weight);
    }

    private static Config sampleBuilderConfig(int observedLagPeriods) {
        Config config = new Config();
        config.k1LagPeriods = observedLagPeriods;
        config.demandAgg = false;
        config.lagDemandAsShare = false;
        config.featureFlags.includeLagDemand = true;
        config.featureFlags.includeHolidayCount = false;
        config.featureFlags.includeFreightIndex = false;
        config.featureFlags.includeConsumptionIndex = false;
        config.featureFlags.includeWEIIndex = false;
        return config;
    }

    private static PeriodData period(int t, double[] demand) {
        LocalDate start = BASE_DATE.plusWeeks(t);
        return new PeriodData(
                t,
                start,
                start.plusDays(6),
                demand.clone(),
                0,
                0.0,
                0.0,
                0.0);
    }

    private static List<String> buildLaneNames(int laneCount) {
        List<String> out = new ArrayList<>(laneCount);
        for (int j = 0; j < laneCount; j++) out.add(String.format("lane_%03d", j + 1));
        return out;
    }

    private static Random stream(long replicationSeed, long streamId) {
        return new Random(mix64(replicationSeed + 0x9E3779B97F4A7C15L * streamId));
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static void shiftHistory(double[][] historyLatestFirst, double[] next) {
        for (int lag = TRUE_LAG_PERIODS - 1; lag >= 1; lag--) {
            historyLatestFirst[lag] = historyLatestFirst[lag - 1];
        }
        historyLatestFirst[0] = next;
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] * b[i];
        return out;
    }

    private static double dot(double[] a, double[] b) {
        double out = 0.0;
        for (int i = 0; i < a.length; i++) out += a[i] * b[i];
        return out;
    }

    private static double sum(double[] x) {
        double out = 0.0;
        for (double v : x) out += v;
        return out;
    }

    private static double normalCdf(double x) {
        // Abramowitz-Stegun 7.1.26 approximation; sufficient for inverse-free
        // Gaussian-copula generation of a uniform marginal.
        double sign = x < 0.0 ? -1.0 : 1.0;
        double a = Math.abs(x) / Math.sqrt(2.0);
        double t = 1.0 / (1.0 + 0.3275911 * a);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t)
                + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-a * a);
        double erf = sign * y;
        return 0.5 * (1.0 + erf);
    }

    private static double[][] deepCopy(double[][] x) {
        double[][] out = new double[x.length][];
        for (int i = 0; i < x.length; i++) out[i] = x[i].clone();
        return out;
    }

    private static final class PathState {
        final double[][] historyLatestFirst;

        PathState(double[][] historyLatestFirst) {
            this.historyLatestFirst = deepCopy(historyLatestFirst);
        }
    }
}
