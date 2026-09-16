package Test.analysis.synthetic;

import Basic.PeriodData;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Olist-calibrated dynamic DGP v2 frozen by the pre-solve statistical audit. */
public final class TRBReviewerOlistDynamicDgpV2 {
    public static final double PHI = 0.8368;
    public static final double RHO = 0.205;
    public static final double ACTIVE_COMMON_FACTOR = 0.50;
    public static final double INNOVATION_COMMON_FACTOR = 0.10;
    private static final int LAGS = 3;
    private static final LocalDate BASE_DATE = LocalDate.of(2000, 1, 3);

    private TRBReviewerOlistDynamicDgpV2() {
    }

    public record Calibration(List<String> laneNames,
                              double[] baselineDemand,
                              double[] activeRate,
                              double[] activeP01,
                              double[] activeP11,
                              int[] archetypeIndex,
                              int sourceLaneCount,
                              int stableWeeks) {
    }

    /** Calibrates only demand-side quantities and expands empirical archetypes. */
    public static Calibration calibrate(Path weeklyCsv, int targetLaneCount) throws Exception {
        WeeklyWideLoader.Result loaded = WeeklyWideLoader.load(
                weeklyCsv.toAbsolutePath().normalize());
        if (targetLaneCount < loaded.laneNames.size()) {
            throw new IllegalArgumentException(
                    "targetLaneCount must be at least the observed lane count.");
        }
        int sourceJ = loaded.laneNames.size();
        int weeks = loaded.periods.size();
        double[][] demand = new double[weeks][sourceJ];
        int stableStart = 0;
        for (int t = 0; t < weeks; t++) {
            demand[t] = loaded.periods.get(t).demandSum.clone();
            if (sum(demand[t]) <= 1e-12) stableStart = t + 1;
        }
        if (stableStart >= weeks - 2) {
            throw new IllegalArgumentException("Insufficient stable Olist weeks.");
        }

        double[] sourceMean = new double[sourceJ];
        for (double[] week : demand) {
            for (int j = 0; j < sourceJ; j++) sourceMean[j] += week[j] / weeks;
        }
        double[] sourceActiveRate = new double[sourceJ];
        double[] sourceP01 = new double[sourceJ];
        double[] sourceP11 = new double[sourceJ];
        int stableWeeks = weeks - stableStart;
        for (int j = 0; j < sourceJ; j++) {
            double activeCount = 0.0;
            for (int t = stableStart; t < weeks; t++) {
                if (demand[t][j] > 0.0) activeCount++;
            }
            double activeRate = activeCount / stableWeeks;
            double persistence;
            if (activeRate <= 0.0) persistence = 0.0;
            else if (activeRate >= 1.0) persistence = 1.0;
            else persistence = Math.max(0.0, Math.min(0.95,
                    binaryLagCorrelation(demand, stableStart, j)));
            sourceActiveRate[j] = activeRate;
            sourceP01[j] = activeRate * (1.0 - persistence);
            sourceP11[j] = activeRate + (1.0 - activeRate) * persistence;
        }

        int[] archetype = balancedArchetypes(sourceMean, targetLaneCount);
        double[] baseline = new double[targetLaneCount];
        double[] activeRate = new double[targetLaneCount];
        double[] p01 = new double[targetLaneCount];
        double[] p11 = new double[targetLaneCount];
        List<String> laneNames = new ArrayList<>(targetLaneCount);
        int[] copyNumber = new int[sourceJ];
        for (int j = 0; j < targetLaneCount; j++) {
            int source = archetype[j];
            baseline[j] = sourceMean[source];
            activeRate[j] = sourceActiveRate[source];
            p01[j] = sourceP01[source];
            p11[j] = sourceP11[source];
            copyNumber[source]++;
            laneNames.add(loaded.laneNames.get(source) + "_copy" + copyNumber[source]);
        }
        double targetTotal = sum(sourceMean) * targetLaneCount / sourceJ;
        double scale = targetTotal / sum(baseline);
        for (int j = 0; j < baseline.length; j++) baseline[j] *= scale;
        return new Calibration(List.copyOf(laneNames), baseline, activeRate,
                p01, p11, archetype, sourceJ, stableWeeks);
    }

    public static ReplicationData generate(Calibration calibration,
                                           int trainingSamples,
                                           int warmupPeriods,
                                           int oosDraws,
                                           long seed,
                                           InnovationDistribution distribution,
                                           double innovationCv) {
        return generate(calibration, trainingSamples, warmupPeriods, oosDraws,
                seed, distribution, innovationCv, RHO);
    }

    /** Generates one replication under a predeclared lane-share persistence. */
    public static ReplicationData generate(Calibration calibration,
                                           int trainingSamples,
                                           int warmupPeriods,
                                           int oosDraws,
                                           long seed,
                                           InnovationDistribution distribution,
                                           double innovationCv,
                                           double sharePersistence) {
        return generate(calibration, trainingSamples, warmupPeriods, oosDraws,
                seed, distribution, innovationCv, sharePersistence, 0.0, 0.0);
    }

    /** Generates one replication with optional trend/season stress after burn-in. */
    public static ReplicationData generate(Calibration calibration,
                                           int trainingSamples,
                                           int warmupPeriods,
                                           int oosDraws,
                                           long seed,
                                           InnovationDistribution distribution,
                                           double innovationCv,
                                           double sharePersistence,
                                           double logTrendPerPeriod,
                                           double seasonalAmplitude) {
        if (trainingSamples <= 0 || warmupPeriods < 0 || oosDraws <= 0
                || !(innovationCv > 0.0) || sharePersistence < 0.0
                || sharePersistence >= 1.0 || !Double.isFinite(sharePersistence)
                || !Double.isFinite(logTrendPerPeriod)
                || seasonalAmplitude < 0.0 || seasonalAmplitude >= 1.0
                || !Double.isFinite(seasonalAmplitude)) {
            throw new IllegalArgumentException("Invalid Olist-v2 generation settings.");
        }
        if (distribution == InnovationDistribution.UNIFORM
                && innovationCv >= 1.0 / Math.sqrt(3.0)) {
            throw new IllegalArgumentException("Uniform CV must be below 1/sqrt(3).");
        }
        Random pathRandom = stream(seed, 1L);
        int J = calibration.baselineDemand.length;
        double[] current = calibration.baselineDemand.clone();
        boolean[] active = new boolean[J];
        for (int j = 0; j < J; j++) {
            active[j] = pathRandom.nextDouble() < calibration.activeRate[j];
        }

        int retainedLength = LAGS + trainingSamples;
        List<double[]> retained = new ArrayList<>(retainedLength);
        for (int t = 0; t < warmupPeriods + retainedLength; t++) {
            Draw draw = drawNext(calibration, current, active,
                    distribution, innovationCv, sharePersistence,
                    nonstationaryScale(t, warmupPeriods, logTrendPerPeriod,
                            seasonalAmplitude), pathRandom);
            current = draw.demand;
            active = draw.active;
            if (t >= warmupPeriods) retained.add(current.clone());
        }

        List<Sample> training = new ArrayList<>(trainingSamples);
        for (int s = 0; s < trainingSamples; s++) {
            int responseIndex = LAGS + s;
            double[][] history = latestFirst(retained, responseIndex);
            training.add(buildSample(s, 1.0 / trainingSamples, history,
                    retained.get(responseIndex), calibration.laneNames));
        }
        double[][] queryHistory = latestFirst(retained, retained.size());
        double[] conditionalMean = conditionalMean(
                calibration.baselineDemand, queryHistory[0], sharePersistence,
                nonstationaryScale(warmupPeriods + retainedLength, warmupPeriods,
                        logTrendPerPeriod, seasonalAmplitude));
        Sample querySample = buildSample(0, 1.0, queryHistory,
                conditionalMean, calibration.laneNames);

        Random oosRandom = stream(seed, 2L);
        List<Sample> oos = new ArrayList<>(oosDraws);
        for (int drawIndex = 0; drawIndex < oosDraws; drawIndex++) {
            Draw draw = drawNext(calibration, queryHistory[0], active,
                    distribution, innovationCv, sharePersistence,
                    nonstationaryScale(warmupPeriods + retainedLength, warmupPeriods,
                            logTrendPerPeriod, seasonalAmplitude), oosRandom);
            oos.add(buildSample(drawIndex, 1.0 / oosDraws, queryHistory,
                    draw.demand, calibration.laneNames));
        }
        log1pContexts(training, querySample, oos);
        return new ReplicationData(calibration.laneNames,
                calibration.baselineDemand, training, querySample.theta, oos,
                conditionalMean, queryHistory);
    }

    private static Draw drawNext(Calibration calibration,
                                 double[] previous,
                                 boolean[] previousActive,
                                 InnovationDistribution distribution,
                                 double innovationCv,
                                 double sharePersistence,
                                 double baselineScale,
                                 Random random) {
        int J = previous.length;
        double[] mean = conditionalMean(
                calibration.baselineDemand, previous, sharePersistence, baselineScale);
        boolean[] active = new boolean[J];
        double occurrenceCommon = random.nextGaussian();
        double occurrenceCommonScale = Math.sqrt(ACTIVE_COMMON_FACTOR);
        double occurrenceIdiosyncraticScale = Math.sqrt(1.0 - ACTIVE_COMMON_FACTOR);
        double innovationCommon = random.nextGaussian();
        double innovationCommonScale = Math.sqrt(INNOVATION_COMMON_FACTOR);
        double innovationIdiosyncraticScale = Math.sqrt(1.0 - INNOVATION_COMMON_FACTOR);
        double logVariance = Math.log1p(innovationCv * innovationCv);
        double logSigma = Math.sqrt(logVariance);
        double[] demand = new double[J];
        for (int j = 0; j < J; j++) {
            double probability = previousActive[j]
                    ? calibration.activeP11[j] : calibration.activeP01[j];
            double occurrenceNormal = occurrenceCommonScale * occurrenceCommon
                    + occurrenceIdiosyncraticScale * random.nextGaussian();
            active[j] = probability >= 1.0
                    || probability > 0.0 && normalCdf(occurrenceNormal) < probability;
            if (!active[j]) continue;
            double innovationNormal = innovationCommonScale * innovationCommon
                    + innovationIdiosyncraticScale * random.nextGaussian();
            double epsilon = distribution == InnovationDistribution.LOGNORMAL
                    ? Math.exp(logSigma * innovationNormal - 0.5 * logVariance)
                    : 1.0 + Math.sqrt(3.0) * innovationCv
                    * (2.0 * normalCdf(innovationNormal) - 1.0);
            demand[j] = mean[j] / probability * epsilon;
        }
        return new Draw(demand, active);
    }

    private static double[] conditionalMean(double[] baseline,
                                            double[] previous,
                                            double sharePersistence) {
        return conditionalMean(baseline, previous, sharePersistence, 1.0);
    }

    private static double[] conditionalMean(double[] baseline,
                                            double[] previous,
                                            double sharePersistence,
                                            double baselineScale) {
        double baselineTotal = sum(baseline);
        double previousTotal = sum(previous);
        double conditionalTotal = (1.0 - PHI) * baselineTotal * baselineScale
                + PHI * previousTotal;
        double[] mean = new double[baseline.length];
        double shareTotal = 0.0;
        for (int j = 0; j < baseline.length; j++) {
            double baselineShare = baseline[j] / baselineTotal;
            double previousShare = previousTotal > 0.0
                    ? previous[j] / previousTotal : baselineShare;
            mean[j] = (1.0 - sharePersistence) * baselineShare
                    + sharePersistence * previousShare;
            shareTotal += mean[j];
        }
        for (int j = 0; j < mean.length; j++) {
            mean[j] = conditionalTotal * mean[j] / shareTotal;
        }
        return mean;
    }

    private static double nonstationaryScale(int absolutePeriod,
                                             int warmupPeriods,
                                             double logTrendPerPeriod,
                                             double seasonalAmplitude) {
        int retainedPeriod = Math.max(0, absolutePeriod - warmupPeriods);
        double trend = Math.exp(logTrendPerPeriod * retainedPeriod);
        double season = 1.0 + seasonalAmplitude
                * Math.sin(2.0 * Math.PI * absolutePeriod / 52.0);
        return trend * season;
    }

    private static int[] balancedArchetypes(double[] sourceMean, int targetJ) {
        int sourceJ = sourceMean.length;
        List<Integer> mapping = new ArrayList<>(targetJ);
        int fullCopies = targetJ / sourceJ;
        for (int copy = 0; copy < fullCopies; copy++) {
            for (int source = 0; source < sourceJ; source++) mapping.add(source);
        }
        int remainder = targetJ - mapping.size();
        List<Integer> ranked = new ArrayList<>(sourceJ);
        for (int source = 0; source < sourceJ; source++) ranked.add(source);
        ranked.sort(Comparator.comparingDouble((Integer j) -> sourceMean[j]).reversed());
        for (int r = 0; r < remainder; r++) {
            int rank = Math.min(sourceJ - 1,
                    (int) Math.floor((r + 0.5) * sourceJ / remainder));
            mapping.add(ranked.get(rank));
        }
        int[] out = new int[targetJ];
        for (int j = 0; j < targetJ; j++) out[j] = mapping.get(j);
        return out;
    }

    private static double binaryLagCorrelation(double[][] demand, int start, int lane) {
        int n = demand.length - start - 1;
        double xMean = 0.0;
        double yMean = 0.0;
        for (int t = start; t < demand.length - 1; t++) {
            xMean += demand[t][lane] > 0.0 ? 1.0 : 0.0;
            yMean += demand[t + 1][lane] > 0.0 ? 1.0 : 0.0;
        }
        xMean /= n;
        yMean /= n;
        double covariance = 0.0;
        double xVariance = 0.0;
        double yVariance = 0.0;
        for (int t = start; t < demand.length - 1; t++) {
            double x = (demand[t][lane] > 0.0 ? 1.0 : 0.0) - xMean;
            double y = (demand[t + 1][lane] > 0.0 ? 1.0 : 0.0) - yMean;
            covariance += x * y;
            xVariance += x * x;
            yVariance += y * y;
        }
        return xVariance > 0.0 && yVariance > 0.0
                ? covariance / Math.sqrt(xVariance * yVariance) : 0.0;
    }

    private static double[][] latestFirst(List<double[]> path, int responseIndex) {
        double[][] history = new double[LAGS][];
        for (int lag = 0; lag < LAGS; lag++) {
            history[lag] = path.get(responseIndex - 1 - lag).clone();
        }
        return history;
    }

    private static Sample buildSample(int id,
                                      double weight,
                                      double[][] historyLatestFirst,
                                      double[] response,
                                      List<String> laneNames) {
        List<PeriodData> periods = new ArrayList<>(LAGS + 1);
        for (int position = 0; position < LAGS; position++) {
            int lag = LAGS - 1 - position;
            periods.add(period(position, historyLatestFirst[lag]));
        }
        periods.add(period(LAGS, response));
        Config config = new Config();
        config.k1LagPeriods = LAGS;
        config.demandAgg = false;
        config.lagDemandAsShare = false;
        config.featureFlags.includeLagDemand = true;
        config.featureFlags.includeHolidayCount = false;
        config.featureFlags.includeFreightIndex = false;
        config.featureFlags.includeConsumptionIndex = false;
        config.featureFlags.includeWEIIndex = false;
        SampleBuilder.BuildResult built = SampleBuilder.buildFromPeriods(
                periods, laneNames, config);
        if (built.samples.size() != 1) {
            throw new IllegalStateException("Expected one lag-response sample.");
        }
        Sample source = built.samples.get(0);
        return new Sample(id, source.period, source.theta.copy(), weight);
    }

    private static PeriodData period(int index, double[] demand) {
        LocalDate start = BASE_DATE.plusWeeks(index);
        return new PeriodData(index, start, start.plusDays(6), demand.clone(),
                0, 0.0, 0.0, 0.0);
    }

    private static void log1pContexts(List<Sample> training,
                                      Sample query,
                                      List<Sample> oos) {
        for (Sample sample : training) log1p(sample.theta.values());
        log1p(query.theta.values());
        for (Sample sample : oos) log1p(sample.theta.values());
    }

    private static void log1p(double[] values) {
        for (int index = 0; index < values.length; index++) {
            values[index] = Math.log1p(values[index]);
        }
    }

    /** Abramowitz-Stegun normal-CDF approximation; max error below 7.5e-8. */
    private static double normalCdf(double value) {
        double absolute = Math.abs(value);
        double t = 1.0 / (1.0 + 0.2316419 * absolute);
        double density = Math.exp(-0.5 * absolute * absolute) / Math.sqrt(2.0 * Math.PI);
        double tail = density * t * (0.319381530 + t * (-0.356563782
                + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return value >= 0.0 ? 1.0 - tail : tail;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static Random stream(long seed, long streamId) {
        return new Random(mix64(seed + 0x9E3779B97F4A7C15L * streamId));
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private record Draw(double[] demand, boolean[] active) {
    }
}
