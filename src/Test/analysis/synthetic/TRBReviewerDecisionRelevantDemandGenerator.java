package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.SampleBuilder;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mechanism-screening demand generator for the TRB revision.
 *
 * <p>The generator keeps the existing lag-demand interface but supports two
 * decision-relevant dynamics: a smooth group autoregression and a persistent
 * group-share regime.  Lane groups are supplied by the caller, so the same
 * demand process can be tested with cost-independent or diagnostic
 * cost-aware group definitions.</p>
 */
public final class TRBReviewerDecisionRelevantDemandGenerator {
    private static final int TRUE_LAGS = 3;
    private static final int TOTAL_REGIMES = 3;
    private static final double[] LAG_WEIGHTS = {0.50, 0.30, 0.20};
    private static final LocalDate BASE_DATE = LocalDate.of(2000, 1, 3);

    private TRBReviewerDecisionRelevantDemandGenerator() {
    }

    public enum Mode {
        LINEAR_GROUP_AR,
        MARKOV_GROUP_SHARE
    }

    public static final class Settings {
        public Mode mode = Mode.LINEAR_GROUP_AR;
        public int trainingSampleCount = 50;
        public int observedLagPeriods = 3;
        public int warmupPeriods = 60;
        public int oosSampleCount = 200;
        public double innovationCv = 0.30;
        public double latentCrossLaneCorrelation = 0.30;

        /** LINEAR_GROUP_AR conditional-mean weights; they must sum to one. */
        public double longRunWeight = 0.20;
        public double globalHistoryWeight = 0.30;
        public double groupHistoryWeight = 0.35;
        public double laneHistoryWeight = 0.15;

        /** MARKOV_GROUP_SHARE parameters. */
        public double regimePersistence = 0.85;
        public double activeGroupMultiplier = 2.00;
        public double inactiveGroupMultiplier = 0.65;
        public double regimeShareWeight = 0.75;
        /** Persistence of the observable lagged total-demand state. */
        public double markovTotalHistoryWeight = 0.80;
        /** Optional low/normal/high total-demand regime; zero disables it. */
        public double totalRegimeLogStep = 0.0;
        public double totalRegimePersistence = 0.85;
        public boolean orderedTotalRegimeTransitions = false;
        /** Uses one three-state chain for both total level and lane mix. */
        public boolean coupledTotalAndShareRegime = false;

        void validate(int laneCount, int[] laneGroups) {
            if (mode == null) throw new IllegalArgumentException("mode is required.");
            if (trainingSampleCount <= 0 || oosSampleCount <= 0 || warmupPeriods < 0) {
                throw new IllegalArgumentException("Invalid sample or warm-up count.");
            }
            if (observedLagPeriods < 1 || observedLagPeriods > TRUE_LAGS) {
                throw new IllegalArgumentException("observedLagPeriods must be 1, 2, or 3.");
            }
            if (!(innovationCv > 0.0)
                    || latentCrossLaneCorrelation < 0.0
                    || latentCrossLaneCorrelation >= 1.0) {
                throw new IllegalArgumentException("Invalid innovation settings.");
            }
            if (laneGroups == null || laneGroups.length != laneCount) {
                throw new IllegalArgumentException("laneGroups length mismatch.");
            }
            int groupCount = groupCount(laneGroups);
            if (groupCount < 2) throw new IllegalArgumentException("At least two lane groups are required.");
            if (coupledTotalAndShareRegime
                    && (mode != Mode.MARKOV_GROUP_SHARE || groupCount != TOTAL_REGIMES
                    || !(totalRegimeLogStep > 0.0))) {
                throw new IllegalArgumentException(
                        "Coupled total/share regimes require three groups, Markov mode, and positive level step.");
            }
            boolean[] seen = new boolean[groupCount];
            for (int group : laneGroups) {
                if (group < 0 || group >= groupCount) {
                    throw new IllegalArgumentException("Lane groups must be contiguous from zero.");
                }
                seen[group] = true;
            }
            for (boolean value : seen) {
                if (!value) throw new IllegalArgumentException("Every lane group must be nonempty.");
            }
            double sum = longRunWeight + globalHistoryWeight
                    + groupHistoryWeight + laneHistoryWeight;
            if (longRunWeight < 0.0 || globalHistoryWeight < 0.0
                    || groupHistoryWeight < 0.0 || laneHistoryWeight < 0.0
                    || Math.abs(sum - 1.0) > 1e-12) {
                throw new IllegalArgumentException("Linear AR weights must be nonnegative and sum to one.");
            }
            if (regimePersistence < 0.0 || regimePersistence >= 1.0
                    || !(activeGroupMultiplier > 0.0)
                    || !(inactiveGroupMultiplier > 0.0)
                    || regimeShareWeight < 0.0 || regimeShareWeight > 1.0
                    || markovTotalHistoryWeight < 0.0
                    || markovTotalHistoryWeight >= 1.0
                    || totalRegimeLogStep < 0.0
                    || !Double.isFinite(totalRegimeLogStep)
                    || totalRegimePersistence < 0.0
                    || totalRegimePersistence >= 1.0) {
                throw new IllegalArgumentException("Invalid Markov regime settings.");
            }
        }
    }

    /**
     * Generates one replication.  Training and query seeds are separated so a
     * fixed training sample can be evaluated at several independent queries.
     */
    public static ReplicationData generate(Settings settings,
                                           double[] baselineDemand,
                                           int[] laneGroups,
                                           long trainingSeed,
                                           long querySeed) {
        if (baselineDemand == null || baselineDemand.length == 0) {
            throw new IllegalArgumentException("baselineDemand is required.");
        }
        settings.validate(baselineDemand.length, laneGroups);
        double[] baseline = baselineDemand.clone();
        for (double value : baseline) {
            if (!(value > 0.0) || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Baseline demand must be finite and positive.");
            }
        }

        List<String> laneNames = laneNames(baseline.length);
        List<Sample> training = new ArrayList<>(settings.trainingSampleCount);
        double trainingWeight = 1.0 / settings.trainingSampleCount;
        for (int sample = 0; sample < settings.trainingSampleCount; sample++) {
            Random random = stream(trainingSeed, 10_000L + sample);
            State state = simulateState(settings, baseline, laneGroups, random);
            double[] response = drawNextDemand(settings, baseline, laneGroups, state, random);
            training.add(buildSample(sample, trainingWeight, state.history,
                    response, settings.observedLagPeriods, laneNames));
        }

        Random queryRandom = stream(querySeed, 20_000L);
        State queryState = simulateState(settings, baseline, laneGroups, queryRandom);
        double[] conditionalMean = conditionalMean(
                settings, baseline, laneGroups, queryState);
        CovariateVector thetaNow = buildSample(0, 1.0, queryState.history,
                conditionalMean, settings.observedLagPeriods, laneNames).theta.copy();

        Random oosRandom = stream(querySeed, 30_000L);
        List<Sample> oos = new ArrayList<>(settings.oosSampleCount);
        double oosWeight = 1.0 / settings.oosSampleCount;
        for (int draw = 0; draw < settings.oosSampleCount; draw++) {
            double[] demand = drawNextDemand(
                    settings, baseline, laneGroups, queryState, oosRandom);
            oos.add(new Sample(draw, period(settings.observedLagPeriods, demand),
                    thetaNow.copy(), oosWeight));
        }

        return new ReplicationData(laneNames, baseline, training, thetaNow,
                oos, conditionalMean, queryState.history);
    }

    /**
     * Generates consecutive lag-response pairs from one dynamic path.  The
     * terminal history of that path is the single query context; OOS draws
     * resample only the next transition conditional on this fixed history.
     */
    public static ReplicationData generateContinuousPath(Settings settings,
                                                         double[] baselineDemand,
                                                         int[] laneGroups,
                                                         long pathSeed,
                                                         long oosSeed) {
        return generateContinuousPath(settings, baselineDemand, laneGroups,
                pathSeed, oosSeed, settings.trainingSampleCount);
    }

    /**
     * Generates the final training window from a fixed-length continuous path.
     * Using the same path length with different training sample counts keeps
     * the terminal query and OOS distribution strictly paired.
     */
    public static ReplicationData generateContinuousPath(Settings settings,
                                                         double[] baselineDemand,
                                                         int[] laneGroups,
                                                         long pathSeed,
                                                         long oosSeed,
                                                         int pathTransitionCount) {
        if (baselineDemand == null || baselineDemand.length == 0) {
            throw new IllegalArgumentException("baselineDemand is required.");
        }
        settings.validate(baselineDemand.length, laneGroups);
        if (pathTransitionCount < settings.trainingSampleCount) {
            throw new IllegalArgumentException(
                    "pathTransitionCount must be at least trainingSampleCount.");
        }
        double[] baseline = baselineDemand.clone();
        for (double value : baseline) {
            if (!(value > 0.0) || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Baseline demand must be finite and positive.");
            }
        }

        List<String> laneNames = laneNames(baseline.length);
        List<Sample> training = new ArrayList<>(settings.trainingSampleCount);
        double trainingWeight = 1.0 / settings.trainingSampleCount;
        Random pathRandom = stream(pathSeed, 40_000L);
        State state = simulateState(settings, baseline, laneGroups, pathRandom);
        int firstRetained = pathTransitionCount - settings.trainingSampleCount;
        for (int sample = 0; sample < pathTransitionCount; sample++) {
            Transition transition = advanceOnePeriod(
                    settings, baseline, laneGroups, state, pathRandom);
            if (sample >= firstRetained) {
                training.add(buildSample(sample - firstRetained, trainingWeight,
                        state.history, transition.demand,
                        settings.observedLagPeriods, laneNames));
            }
            state = transition.nextState;
        }

        double[] conditionalMean = conditionalMean(settings, baseline, laneGroups, state);
        CovariateVector thetaNow = buildSample(0, 1.0, state.history,
                conditionalMean, settings.observedLagPeriods, laneNames).theta.copy();

        Random oosRandom = stream(oosSeed, 30_000L);
        List<Sample> oos = new ArrayList<>(settings.oosSampleCount);
        double oosWeight = 1.0 / settings.oosSampleCount;
        for (int draw = 0; draw < settings.oosSampleCount; draw++) {
            double[] demand = drawNextDemand(
                    settings, baseline, laneGroups, state, oosRandom);
            oos.add(new Sample(draw, period(settings.observedLagPeriods, demand),
                    thetaNow.copy(), oosWeight));
        }

        return new ReplicationData(laneNames, baseline, training, thetaNow,
                oos, conditionalMean, state.history);
    }

    /**
     * Paired control: independent-path training rows evaluated at exactly the
     * same terminal context and OOS draws as the continuous-path protocol.
     */
    public static ReplicationData generateIndependentTrainingAtContinuousQuery(
            Settings settings,
            double[] baselineDemand,
            int[] laneGroups,
            long trainingAndPathSeed,
            long oosSeed) {
        ReplicationData independent = generate(settings, baselineDemand, laneGroups,
                trainingAndPathSeed, oosSeed);
        ReplicationData continuous = generateContinuousPath(settings, baselineDemand,
                laneGroups, trainingAndPathSeed, oosSeed);
        return new ReplicationData(continuous.laneNames, continuous.baselineDemand,
                independent.trainingSamples, continuous.thetaNow, continuous.oosSamples,
                continuous.conditionalMean, continuous.queryHistoryLatestFirst);
    }

    private static State simulateState(Settings settings,
                                       double[] baseline,
                                       int[] laneGroups,
                                       Random random) {
        double[][] history = new double[TRUE_LAGS][baseline.length];
        for (int lag = 0; lag < TRUE_LAGS; lag++) history[lag] = baseline.clone();
        int regime = random.nextInt(groupCount(laneGroups));
        int totalRegime = usesIndependentTotalRegime(settings)
                ? random.nextInt(TOTAL_REGIMES) : -1;
        State state = new State(history, regime, totalRegime);
        for (int period = 0; period < settings.warmupPeriods; period++) {
            int nextRegime = settings.mode == Mode.MARKOV_GROUP_SHARE
                    ? drawNextLaneRegime(settings, regime,
                    groupCount(laneGroups), random) : regime;
            int nextTotalRegime = usesIndependentTotalRegime(settings)
                    ? drawNextTotalRegime(settings, totalRegime, random) : -1;
            double[] mean = settings.mode == Mode.LINEAR_GROUP_AR
                    ? linearMean(settings, baseline, laneGroups, history)
                    : markovMean(settings, baseline, laneGroups, history,
                    nextRegime, nextTotalRegime);
            double[] next = multiply(mean, innovations(settings, baseline.length, random));
            shift(history, next);
            regime = nextRegime;
            totalRegime = nextTotalRegime;
            state = new State(history, regime, totalRegime);
        }
        return state;
    }

    private static double[] drawNextDemand(Settings settings,
                                           double[] baseline,
                                           int[] laneGroups,
                                           State state,
                                           Random random) {
        double[] mean;
        if (settings.mode == Mode.LINEAR_GROUP_AR) {
            mean = linearMean(settings, baseline, laneGroups, state.history);
        } else {
            int nextRegime = drawNextLaneRegime(settings, state.regime,
                    groupCount(laneGroups), random);
            int nextTotalRegime = usesIndependentTotalRegime(settings)
                    ? drawNextTotalRegime(settings, state.totalRegime, random) : -1;
            mean = markovMean(settings, baseline, laneGroups,
                    state.history, nextRegime, nextTotalRegime);
        }
        return multiply(mean, innovations(settings, baseline.length, random));
    }

    private static Transition advanceOnePeriod(Settings settings,
                                               double[] baseline,
                                               int[] laneGroups,
                                               State state,
                                               Random random) {
        int nextRegime = settings.mode == Mode.MARKOV_GROUP_SHARE
                ? drawNextLaneRegime(settings, state.regime,
                groupCount(laneGroups), random) : state.regime;
        int nextTotalRegime = usesIndependentTotalRegime(settings)
                ? drawNextTotalRegime(settings, state.totalRegime, random)
                : state.totalRegime;
        double[] mean = settings.mode == Mode.LINEAR_GROUP_AR
                ? linearMean(settings, baseline, laneGroups, state.history)
                : markovMean(settings, baseline, laneGroups, state.history,
                nextRegime, nextTotalRegime);
        double[] demand = multiply(mean, innovations(settings, baseline.length, random));
        double[][] nextHistory = copy(state.history);
        shift(nextHistory, demand);
        return new Transition(demand,
                new State(nextHistory, nextRegime, nextTotalRegime));
    }

    private static double[] conditionalMean(Settings settings,
                                            double[] baseline,
                                            int[] laneGroups,
                                            State state) {
        if (settings.mode == Mode.LINEAR_GROUP_AR) {
            return linearMean(settings, baseline, laneGroups, state.history);
        }
        int groups = groupCount(laneGroups);
        double[] out = new double[baseline.length];
        for (int next = 0; next < groups; next++) {
            double probability = laneRegimeTransitionProbability(
                    settings, state.regime, next, groups);
            if (!usesIndependentTotalRegime(settings)) {
                double[] mean = markovMean(
                        settings, baseline, laneGroups, state.history, next, -1);
                for (int j = 0; j < out.length; j++) out[j] += probability * mean[j];
                continue;
            }
            for (int nextTotal = 0; nextTotal < TOTAL_REGIMES; nextTotal++) {
                double totalProbability = totalRegimeTransitionProbability(
                        settings, state.totalRegime, nextTotal);
                if (totalProbability == 0.0) continue;
                double[] mean = markovMean(settings, baseline, laneGroups,
                        state.history, next, nextTotal);
                for (int j = 0; j < out.length; j++) {
                    out[j] += probability * totalProbability * mean[j];
                }
            }
        }
        return out;
    }

    private static double[] linearMean(Settings settings,
                                       double[] baseline,
                                       int[] laneGroups,
                                       double[][] history) {
        int groups = groupCount(laneGroups);
        double baselineTotal = sum(baseline);
        double[] baselineGroup = groupTotals(baseline, laneGroups, groups);
        double[] groupState = new double[groups];
        double globalState = 0.0;
        for (int lag = 0; lag < TRUE_LAGS; lag++) {
            globalState += LAG_WEIGHTS[lag] * sum(history[lag]) / baselineTotal;
            double[] total = groupTotals(history[lag], laneGroups, groups);
            for (int group = 0; group < groups; group++) {
                groupState[group] += LAG_WEIGHTS[lag] * total[group] / baselineGroup[group];
            }
        }

        double[] mean = new double[baseline.length];
        for (int j = 0; j < mean.length; j++) {
            double laneState = 0.0;
            for (int lag = 0; lag < TRUE_LAGS; lag++) {
                laneState += LAG_WEIGHTS[lag] * history[lag][j] / baseline[j];
            }
            mean[j] = baseline[j] * (settings.longRunWeight
                    + settings.globalHistoryWeight * globalState
                    + settings.groupHistoryWeight * groupState[laneGroups[j]]
                    + settings.laneHistoryWeight * laneState);
        }
        return mean;
    }

    private static double[] markovMean(Settings settings,
                                       double[] baseline,
                                       int[] laneGroups,
                                       double[][] history,
                                       int nextRegime,
                                       int nextTotalRegime) {
        double baselineTotal = sum(baseline);
        double totalMean;
        if (settings.coupledTotalAndShareRegime) {
            totalMean = baselineTotal * totalRegimeMultiplier(settings, nextRegime);
        } else if (usesIndependentTotalRegime(settings)) {
            totalMean = baselineTotal * totalRegimeMultiplier(
                    settings, nextTotalRegime);
        } else {
            double globalState = 0.0;
            for (int lag = 0; lag < TRUE_LAGS; lag++) {
                globalState += LAG_WEIGHTS[lag] * sum(history[lag]) / baselineTotal;
            }
            totalMean = baselineTotal * (
                    1.0 - settings.markovTotalHistoryWeight
                            + settings.markovTotalHistoryWeight * globalState);
        }

        double[] regimeShare = new double[baseline.length];
        double normalizer = 0.0;
        for (int j = 0; j < baseline.length; j++) {
            double multiplier = laneGroups[j] == nextRegime
                    ? settings.activeGroupMultiplier : settings.inactiveGroupMultiplier;
            regimeShare[j] = baseline[j] * multiplier;
            normalizer += regimeShare[j];
        }

        double[] mean = new double[baseline.length];
        for (int j = 0; j < mean.length; j++) {
            double baseShare = baseline[j] / baselineTotal;
            double activeShare = regimeShare[j] / normalizer;
            double share = (1.0 - settings.regimeShareWeight) * baseShare
                    + settings.regimeShareWeight * activeShare;
            mean[j] = totalMean * share;
        }
        return mean;
    }

    private static boolean usesIndependentTotalRegime(Settings settings) {
        return settings.totalRegimeLogStep > 0.0
                && !settings.coupledTotalAndShareRegime;
    }

    private static int drawNextLaneRegime(Settings settings,
                                          int current,
                                          int groupCount,
                                          Random random) {
        return settings.coupledTotalAndShareRegime
                ? drawNextTotalRegime(settings, current, random)
                : drawNextRegime(current, groupCount,
                settings.regimePersistence, random);
    }

    private static double laneRegimeTransitionProbability(Settings settings,
                                                           int current,
                                                           int next,
                                                           int groupCount) {
        if (settings.coupledTotalAndShareRegime) {
            return totalRegimeTransitionProbability(settings, current, next);
        }
        return next == current
                ? settings.regimePersistence
                : (1.0 - settings.regimePersistence) / (groupCount - 1);
    }

    private static double totalRegimeMultiplier(Settings settings, int regime) {
        if (regime < 0 || regime >= TOTAL_REGIMES) {
            throw new IllegalArgumentException("Invalid total-demand regime: " + regime);
        }
        double lowWeight = settings.orderedTotalRegimeTransitions ? 0.25 : 1.0 / 3.0;
        double normalWeight = settings.orderedTotalRegimeTransitions ? 0.50 : 1.0 / 3.0;
        double highWeight = lowWeight;
        double low = Math.exp(-settings.totalRegimeLogStep);
        double high = Math.exp(settings.totalRegimeLogStep);
        double denominator = lowWeight * low + normalWeight + highWeight * high;
        return Math.exp(settings.totalRegimeLogStep * (regime - 1)) / denominator;
    }

    private static int drawNextTotalRegime(Settings settings,
                                           int current,
                                           Random random) {
        if (!settings.orderedTotalRegimeTransitions) {
            return drawNextRegime(current, TOTAL_REGIMES,
                    settings.totalRegimePersistence, random);
        }
        double draw = random.nextDouble();
        if (draw < settings.totalRegimePersistence) return current;
        if (current == 0) return 1;
        if (current == 2) return 1;
        return random.nextBoolean() ? 0 : 2;
    }

    private static double totalRegimeTransitionProbability(Settings settings,
                                                           int current,
                                                           int next) {
        if (!settings.orderedTotalRegimeTransitions) {
            return next == current
                    ? settings.totalRegimePersistence
                    : (1.0 - settings.totalRegimePersistence) / (TOTAL_REGIMES - 1);
        }
        if (next == current) return settings.totalRegimePersistence;
        if (current == 0) return next == 1 ? 1.0 - settings.totalRegimePersistence : 0.0;
        if (current == 2) return next == 1 ? 1.0 - settings.totalRegimePersistence : 0.0;
        return next == 0 || next == 2
                ? (1.0 - settings.totalRegimePersistence) / 2.0 : 0.0;
    }

    private static int drawNextRegime(int current,
                                      int groupCount,
                                      double persistence,
                                      Random random) {
        if (random.nextDouble() < persistence) return current;
        int other = random.nextInt(groupCount - 1);
        return other >= current ? other + 1 : other;
    }

    private static double[] innovations(Settings settings, int laneCount, Random random) {
        double variance = Math.log1p(settings.innovationCv * settings.innovationCv);
        double sigma = Math.sqrt(variance);
        double common = random.nextGaussian();
        double commonScale = Math.sqrt(settings.latentCrossLaneCorrelation);
        double idiosyncraticScale = Math.sqrt(1.0 - settings.latentCrossLaneCorrelation);
        double[] out = new double[laneCount];
        for (int j = 0; j < laneCount; j++) {
            double normal = commonScale * common + idiosyncraticScale * random.nextGaussian();
            out[j] = Math.exp(-0.5 * variance + sigma * normal);
        }
        return out;
    }

    private static Sample buildSample(int id,
                                      double weight,
                                      double[][] history,
                                      double[] response,
                                      int observedLags,
                                      List<String> laneNames) {
        List<PeriodData> periods = new ArrayList<>(observedLags + 1);
        for (int position = 0; position < observedLags; position++) {
            int lag = observedLags - 1 - position;
            periods.add(period(position, history[lag]));
        }
        periods.add(period(observedLags, response));

        Config config = new Config();
        config.k1LagPeriods = observedLags;
        config.demandAgg = false;
        config.lagDemandAsShare = false;
        config.featureFlags.includeLagDemand = true;
        config.featureFlags.includeHolidayCount = false;
        config.featureFlags.includeFreightIndex = false;
        config.featureFlags.includeConsumptionIndex = false;
        config.featureFlags.includeWEIIndex = false;
        SampleBuilder.BuildResult built = SampleBuilder.buildFromPeriods(periods, laneNames, config);
        if (built.samples.size() != 1) {
            throw new IllegalStateException("Expected exactly one lag-response sample.");
        }
        Sample source = built.samples.get(0);
        return new Sample(id, source.period, source.theta.copy(), weight);
    }

    private static PeriodData period(int index, double[] demand) {
        LocalDate start = BASE_DATE.plusWeeks(index);
        return new PeriodData(index, start, start.plusDays(6), demand.clone(),
                0, 0.0, 0.0, 0.0);
    }

    private static List<String> laneNames(int laneCount) {
        List<String> names = new ArrayList<>(laneCount);
        for (int j = 0; j < laneCount; j++) names.add(String.format("lane_%03d", j + 1));
        return names;
    }

    private static int groupCount(int[] groups) {
        int max = -1;
        for (int group : groups) max = Math.max(max, group);
        return max + 1;
    }

    private static double[] groupTotals(double[] values, int[] groups, int groupCount) {
        double[] out = new double[groupCount];
        for (int j = 0; j < values.length; j++) out[groups[j]] += values[j];
        return out;
    }

    private static double[] multiply(double[] left, double[] right) {
        double[] out = new double[left.length];
        for (int j = 0; j < out.length; j++) out[j] = left[j] * right[j];
        return out;
    }

    private static double[][] copy(double[][] values) {
        double[][] out = new double[values.length][];
        for (int row = 0; row < values.length; row++) out[row] = values[row].clone();
        return out;
    }

    private static double sum(double[] values) {
        double out = 0.0;
        for (double value : values) out += value;
        return out;
    }

    private static void shift(double[][] history, double[] next) {
        for (int lag = TRUE_LAGS - 1; lag >= 1; lag--) history[lag] = history[lag - 1];
        history[0] = next;
    }

    private static Random stream(long seed, long streamId) {
        return new Random(mix64(seed + 0x9E3779B97F4A7C15L * streamId));
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private record State(double[][] history, int regime, int totalRegime) {
    }

    private record Transition(double[] demand, State nextState) {
    }
}
