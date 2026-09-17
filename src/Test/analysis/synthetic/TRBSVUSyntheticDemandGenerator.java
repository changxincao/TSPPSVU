package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.Sample;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Demand input for synthetic Experiments 1--3 in the revised numerical plan.
 * This class generates only contexts and shipment volumes; procurement-market
 * parameters and optimization methods are deliberately outside its scope.
 */
public final class TRBSVUSyntheticDemandGenerator {
    private static final LocalDate FIRST_WEEK = LocalDate.of(2000, 1, 3);
    private static final long COMMON_LOADING_SALT = 0x6A09E667F3BCC909L;
    private static final long COMMON_NOISE_SALT = 0xBB67AE8584CAA73BL;
    private static final long CONTEXT_STRUCTURE_SALT = 0x3C6EF372FE94F82BL;
    private static final long BASE_STRUCTURE_SALT = 0xA54FF53A5F1D36F1L;

    private TRBSVUSyntheticDemandGenerator() {
    }

    public enum Distribution {
        NORMAL, LOGNORMAL
    }

    public enum ContextStructure {
        DENSE_PROPORTIONAL, DENSE_WIDE_POSITIVE, DENSE_INDEPENDENT_LEVELS,
        DENSE_WIDE_SAME_MEAN,
        DENSE_WIDE_SIGNED_SAME_MEAN,
        DENSE_RANDOM_SHARES, DENSE_POSITIVE_CENTERED,
        TWO_ACTIVE, ONE_ACTIVE, SIGNED_CENTERED, GROUPED_CENTERED
    }

    public enum BaseStructure {
        UNIFORM_10_30, THREE_LEVEL_WIDE
    }

    public enum Volatility {
        LOW(0.1, 0.3), MEDIUM(0.4, 0.6), HIGH(0.7, 0.9);

        private final double lower;
        private final double upper;

        Volatility(double lower, double upper) {
            this.lower = lower;
            this.upper = upper;
        }

        double fromQuantile(double u) {
            return lower + (upper - lower) * u;
        }
    }

    /**
     * One replication's fixed lane coefficients. Reuse the same object across
     * all six distribution/volatility cells to keep nominal demand paired.
     */
    public static final class Parameters {
        private final int historicalPeriods;
        private final double contextCoefficientScale;
        private final ContextStructure contextStructure;
        private final BaseStructure baseStructure;
        private final double[] base;
        private final double[] market;
        private final double[] trend;
        private final double[] promotion;
        private final double[] attention;
        private final double[] volatilityQuantile;
        private final double[] commonLoading;

        private Parameters(int historicalPeriods, int lanes, double contextCoefficientScale,
                           ContextStructure contextStructure, BaseStructure baseStructure) {
            this.historicalPeriods = historicalPeriods;
            this.contextCoefficientScale = contextCoefficientScale;
            this.contextStructure = contextStructure;
            this.baseStructure = baseStructure;
            base = new double[lanes];
            market = new double[lanes];
            trend = new double[lanes];
            promotion = new double[lanes];
            attention = new double[lanes];
            volatilityQuantile = new double[lanes];
            commonLoading = new double[lanes];
        }

        public int laneCount() {
            return base.length;
        }

        public int historicalPeriods() {
            return historicalPeriods;
        }

        public double contextCoefficientScale() { return contextCoefficientScale; }
        public ContextStructure contextStructure() { return contextStructure; }
        public BaseStructure baseStructure() { return baseStructure; }

        public double[] base() { return base.clone(); }
        public double[] market() { return market.clone(); }
        public double[] trend() { return trend.clone(); }
        public double[] promotion() { return promotion.clone(); }
        public double[] attention() { return attention.clone(); }
        public double[] volatilityQuantile() { return volatilityQuantile.clone(); }
        public double[] commonLoading() { return commonLoading.clone(); }

        /** E[mu_j(theta)] under the synthetic context distribution; procurement scale only. */
        public double[] typicalDemand() {
            double[] result = new double[laneCount()];
            double averageContext = 0.5;
            for (int j = 0; j < result.length; j++) {
                if (isCentered(contextStructure)) {
                    result[j] = base[j];
                } else {
                    result[j] = base[j] + 0.5 * (market[j] + promotion[j] + attention[j])
                            + averageContext * trend[j];
                }
            }
            return result;
        }

        /** The nominal level, not the mean after rejecting negative Normal draws. */
        public double[] nominalDemand(CovariateVector context) {
            double[] x = context.values();
            if (x.length != 4) throw new IllegalArgumentException("Context must have M,T,A,C.");
            double[] result = new double[laneCount()];
            for (int j = 0; j < result.length; j++) {
                if (isCentered(contextStructure)) {
                    result[j] = base[j] + market[j] * (x[0] - 0.5)
                            + trend[j] * (x[1] - 0.5)
                            + promotion[j] * (x[2] - 0.5)
                            + attention[j] * (x[3] - 0.5);
                } else {
                    result[j] = base[j] + market[j] * x[0] + trend[j] * x[1]
                            + promotion[j] * x[2] + attention[j] * x[3];
                }
            }
            return result;
        }

        public double[] volatilityParameters(Volatility regime) {
            if (regime == null) throw new IllegalArgumentException("Volatility regime is required.");
            double[] result = new double[laneCount()];
            for (int j = 0; j < result.length; j++) {
                result[j] = regime.fromQuantile(volatilityQuantile[j]);
            }
            return result;
        }
    }

    public static final class Replication {
        public final Parameters parameters;
        public final List<Sample> history;
        public final CovariateVector testContext;
        public final List<Sample> oos;

        private Replication(Parameters parameters, List<Sample> history,
                            CovariateVector testContext, List<Sample> oos) {
            this.parameters = parameters;
            this.history = List.copyOf(history);
            this.testContext = testContext;
            this.oos = List.copyOf(oos);
        }
    }

    public static final class ConditionalQuery {
        public final CovariateVector context;
        public final List<Sample> oos;

        private ConditionalQuery(CovariateVector context, List<Sample> oos) {
            this.context = context;
            this.oos = List.copyOf(oos);
        }
    }

    public static final class MultiQueryReplication {
        public final Parameters parameters;
        public final List<Sample> history;
        public final List<ConditionalQuery> queries;

        private MultiQueryReplication(Parameters parameters, List<Sample> history,
                                      List<ConditionalQuery> queries) {
            this.parameters = parameters;
            this.history = List.copyOf(history);
            this.queries = List.copyOf(queries);
        }
    }

    public static Parameters sampleParameters(int laneCount, int historicalPeriods, long seed) {
        return sampleParameters(laneCount, historicalPeriods, seed, 1.0);
    }

    public static Parameters sampleParameters(int laneCount, int historicalPeriods, long seed,
                                              double contextCoefficientScale) {
        return sampleParameters(laneCount, historicalPeriods, seed, contextCoefficientScale,
                ContextStructure.DENSE_PROPORTIONAL);
    }

    public static Parameters sampleParameters(int laneCount, int historicalPeriods, long seed,
                                              double contextCoefficientScale,
                                              ContextStructure contextStructure) {
        return sampleParameters(laneCount, historicalPeriods, seed, contextCoefficientScale,
                contextStructure, BaseStructure.UNIFORM_10_30, 0.2, 0.6);
    }

    public static Parameters sampleParameters(int laneCount, int historicalPeriods, long seed,
                                              double contextCoefficientScale,
                                              ContextStructure contextStructure,
                                              BaseStructure baseStructure) {
        return sampleParameters(laneCount, historicalPeriods, seed, contextCoefficientScale,
                contextStructure, baseStructure, 0.2, 0.6);
    }

    public static Parameters sampleParameters(int laneCount, int historicalPeriods, long seed,
                                              double contextCoefficientScale,
                                              ContextStructure contextStructure,
                                              double commonLoadingLower,
                                              double commonLoadingUpper) {
        return sampleParameters(laneCount, historicalPeriods, seed, contextCoefficientScale,
                contextStructure, BaseStructure.UNIFORM_10_30,
                commonLoadingLower, commonLoadingUpper);
    }

    public static Parameters sampleParameters(int laneCount, int historicalPeriods, long seed,
                                              double contextCoefficientScale,
                                              ContextStructure contextStructure,
                                              BaseStructure baseStructure,
                                              double commonLoadingLower,
                                              double commonLoadingUpper) {
        if (laneCount <= 0 || historicalPeriods <= 0) {
            throw new IllegalArgumentException("Lane and history counts must be positive.");
        }
        if (!(contextCoefficientScale > 0.0) || !Double.isFinite(contextCoefficientScale)) {
            throw new IllegalArgumentException("Context coefficient scale must be finite and positive.");
        }
        if (contextStructure == null) throw new IllegalArgumentException("Context structure is required.");
        if (baseStructure == null) throw new IllegalArgumentException("Base structure is required.");
        if (isCentered(contextStructure) && contextCoefficientScale >= 2.0) {
            throw new IllegalArgumentException("Centered context scale must be below 2 to keep demand positive.");
        }
        if (!(commonLoadingLower >= 0.0 && commonLoadingLower <= commonLoadingUpper
                && commonLoadingUpper < 1.0)) {
            throw new IllegalArgumentException("Common loading interval must lie in [0,1). ");
        }
        Parameters p = new Parameters(historicalPeriods, laneCount, contextCoefficientScale,
                contextStructure, baseStructure);
        Random random = new Random(seed);
        Random loadingRandom = new Random(seed ^ COMMON_LOADING_SALT);
        Random structureRandom = new Random(seed ^ CONTEXT_STRUCTURE_SALT);
        int[] baseLevels = baseLevels(laneCount, seed ^ BASE_STRUCTURE_SALT);
        for (int j = 0; j < laneCount; j++) {
            p.base[j] = base(baseStructure, baseLevels[j], random.nextDouble());
            double[] ratios = {uniform(random, 0.3, 0.6), uniform(random, 0.2, 0.4),
                    uniform(random, 0.3, 0.6), uniform(random, 0.3, 0.6)};
            restructure(ratios, contextStructure, structureRandom, j);
            p.market[j] = contextCoefficientScale * p.base[j] * ratios[0];
            p.trend[j] = contextCoefficientScale * p.base[j] * ratios[1];
            p.promotion[j] = contextCoefficientScale * p.base[j] * ratios[2];
            p.attention[j] = contextCoefficientScale * p.base[j] * ratios[3];
            p.volatilityQuantile[j] = random.nextDouble();
            p.commonLoading[j] = uniform(loadingRandom, commonLoadingLower, commonLoadingUpper);
        }
        return p;
    }

    private static void restructure(double[] ratios, ContextStructure structure, Random random,
                                    int lane) {
        if (structure == ContextStructure.DENSE_PROPORTIONAL) return;
        if (structure == ContextStructure.DENSE_WIDE_POSITIVE) {
            ratios[0] = 0.1 + (0.8 / 0.3) * (ratios[0] - 0.3);
            ratios[1] = 0.1 + (0.8 / 0.2) * (ratios[1] - 0.2);
            ratios[2] = 0.1 + (0.8 / 0.3) * (ratios[2] - 0.3);
            ratios[3] = 0.1 + (0.8 / 0.3) * (ratios[3] - 0.3);
            return;
        }
        if (structure == ContextStructure.DENSE_INDEPENDENT_LEVELS) {
            for (int k = 0; k < ratios.length; k++) {
                int level = random.nextInt(3);
                ratios[k] = switch (level) {
                    case 0 -> uniform(random, 0.1, 0.3);
                    case 1 -> uniform(random, 0.4, 0.6);
                    default -> uniform(random, 0.7, 0.9);
                };
            }
            return;
        }
        if (structure == ContextStructure.DENSE_WIDE_SAME_MEAN) {
            ratios[0] = 3.0 * (ratios[0] - 0.3);
            ratios[1] = 3.0 * (ratios[1] - 0.2);
            ratios[2] = 3.0 * (ratios[2] - 0.3);
            ratios[3] = 3.0 * (ratios[3] - 0.3);
            return;
        }
        if (structure == ContextStructure.DENSE_WIDE_SIGNED_SAME_MEAN) {
            ratios[0] = -0.2 + (1.3 / 0.3) * (ratios[0] - 0.3);
            ratios[1] = -0.2 + (1.0 / 0.2) * (ratios[1] - 0.2);
            ratios[2] = -0.2 + (1.3 / 0.3) * (ratios[2] - 0.3);
            ratios[3] = -0.2 + (1.3 / 0.3) * (ratios[3] - 0.3);
            return;
        }
        double total = 0.0;
        for (double ratio : ratios) total += ratio;
        if (structure == ContextStructure.DENSE_POSITIVE_CENTERED) {
            for (int k = 0; k < ratios.length; k++) ratios[k] /= total;
            return;
        }
        java.util.Arrays.fill(ratios, 0.0);
        if (structure == ContextStructure.GROUPED_CENTERED) {
            ratios[lane % ratios.length] = 1.0;
            return;
        }
        if (structure == ContextStructure.SIGNED_CENTERED) {
            double absoluteSum = 0.0;
            for (int k = 0; k < ratios.length; k++) {
                ratios[k] = random.nextGaussian();
                absoluteSum += Math.abs(ratios[k]);
            }
            for (int k = 0; k < ratios.length; k++) ratios[k] /= absoluteSum;
            return;
        }
        if (structure == ContextStructure.TWO_ACTIVE) {
            int first = random.nextInt(ratios.length);
            int second = random.nextInt(ratios.length - 1);
            if (second >= first) second++;
            double share = uniform(random, 0.35, 0.65);
            ratios[first] = total * share;
            ratios[second] = total * (1.0 - share);
            return;
        }
        if (structure == ContextStructure.ONE_ACTIVE) {
            ratios[random.nextInt(ratios.length)] = total;
            return;
        }
        double[] draws = new double[ratios.length];
        double sum = 0.0;
        for (int k = 0; k < draws.length; k++) {
            draws[k] = -Math.log(1.0 - random.nextDouble());
            sum += draws[k];
        }
        for (int k = 0; k < ratios.length; k++) ratios[k] = total * draws[k] / sum;
    }

    private static boolean isCentered(ContextStructure structure) {
        return structure == ContextStructure.SIGNED_CENTERED
                || structure == ContextStructure.GROUPED_CENTERED
                || structure == ContextStructure.DENSE_POSITIVE_CENTERED;
    }

    private static int[] baseLevels(int lanes, long seed) {
        List<Integer> levels = new ArrayList<>(lanes);
        for (int j = 0; j < lanes; j++) levels.add(j % 3);
        Collections.shuffle(levels, new Random(seed));
        int[] result = new int[lanes];
        for (int j = 0; j < lanes; j++) result[j] = levels.get(j);
        return result;
    }

    private static double base(BaseStructure structure, int level, double quantile) {
        if (structure == BaseStructure.UNIFORM_10_30) return 10.0 + 20.0 * quantile;
        return switch (level) {
            case 0 -> 5.0 + 10.0 * quantile;
            case 1 -> 25.0 + 25.0 * quantile;
            default -> 75.0 + 50.0 * quantile;
        };
    }

    /**
     * Generate H consecutive observations and N draws at one fixed test context.
     * Reusing parameter/context seeds across DGP cells pairs their nominal paths;
     * using the same complete replication across methods pairs their OOS draws.
     */
    public static Replication generate(Parameters parameters, Distribution distribution,
                                       Volatility regime, int oosCount,
                                       long contextSeed, long historyNoiseSeed, long oosNoiseSeed) {
        return generate(parameters, distribution, regime, oosCount, contextSeed,
                historyNoiseSeed, oosNoiseSeed, true);
    }

    static Replication generate(Parameters parameters, Distribution distribution,
                                Volatility regime, int oosCount,
                                long contextSeed, long historyNoiseSeed, long oosNoiseSeed,
                                boolean useCommonFactor) {
        if (parameters == null || distribution == null || regime == null || oosCount <= 0) {
            throw new IllegalArgumentException("Parameters, DGP cell and OOS count are required.");
        }
        int h = parameters.historicalPeriods();
        Random contextRandom = new Random(contextSeed);
        Random historyRandom = new Random(historyNoiseSeed);
        Random oosRandom = new Random(oosNoiseSeed);
        Random historyCommonRandom = new Random(historyNoiseSeed ^ COMMON_NOISE_SALT);
        Random oosCommonRandom = new Random(oosNoiseSeed ^ COMMON_NOISE_SALT);
        double[] cv = parameters.volatilityParameters(regime);
        List<Sample> history = new ArrayList<>(h);
        for (int t = 0; t < h; t++) {
            CovariateVector context = context(contextRandom);
            double[] demand = drawDemand(parameters.nominalDemand(context), cv,
                    parameters.commonLoading, distribution, historyRandom,
                    historyCommonRandom, useCommonFactor);
            history.add(sample(t, t, context, demand, 1.0 / h));
        }

        CovariateVector testContext = context(contextRandom);
        double[] testNominal = parameters.nominalDemand(testContext);
        List<Sample> oos = new ArrayList<>(oosCount);
        for (int draw = 0; draw < oosCount; draw++) {
            double[] demand = drawDemand(testNominal, cv, parameters.commonLoading,
                    distribution, oosRandom, oosCommonRandom, useCommonFactor);
            oos.add(sample(draw, h, testContext.copy(), demand, 1.0 / oosCount));
        }
        return new Replication(parameters, history, testContext, oos);
    }

    /** Generate one shared training sample and multiple independent query contexts. */
    public static MultiQueryReplication generateMultiQuery(
            Parameters parameters, Distribution distribution, Volatility regime,
            int queryCount, int oosCount, long contextSeed,
            long historyNoiseSeed, long oosNoiseSeed) {
        if (parameters == null || distribution == null || regime == null
                || queryCount <= 0 || oosCount <= 0) {
            throw new IllegalArgumentException(
                    "Parameters, DGP cell, query count and OOS count are required.");
        }
        int h = parameters.historicalPeriods();
        Random contextRandom = new Random(contextSeed);
        Random historyRandom = new Random(historyNoiseSeed);
        Random oosRandom = new Random(oosNoiseSeed);
        Random historyCommonRandom = new Random(historyNoiseSeed ^ COMMON_NOISE_SALT);
        Random oosCommonRandom = new Random(oosNoiseSeed ^ COMMON_NOISE_SALT);
        double[] cv = parameters.volatilityParameters(regime);
        List<Sample> history = new ArrayList<>(h);
        for (int t = 0; t < h; t++) {
            CovariateVector context = context(contextRandom);
            double[] demand = drawDemand(parameters.nominalDemand(context), cv,
                    parameters.commonLoading, distribution, historyRandom,
                    historyCommonRandom, true);
            history.add(sample(t, t, context, demand, 1.0 / h));
        }

        List<ConditionalQuery> queries = new ArrayList<>(queryCount);
        for (int query = 0; query < queryCount; query++) {
            CovariateVector queryContext = context(contextRandom);
            double[] nominal = parameters.nominalDemand(queryContext);
            List<Sample> oos = new ArrayList<>(oosCount);
            for (int draw = 0; draw < oosCount; draw++) {
                double[] demand = drawDemand(nominal, cv, parameters.commonLoading,
                        distribution, oosRandom, oosCommonRandom, true);
                oos.add(sample(query * oosCount + draw, h, queryContext.copy(), demand,
                        1.0 / oosCount));
            }
            queries.add(new ConditionalQuery(queryContext, oos));
        }
        return new MultiQueryReplication(parameters, history, queries);
    }

    private static CovariateVector context(Random random) {
        return new CovariateVector(new double[] {
                random.nextDouble(), random.nextDouble(),
                random.nextDouble(), random.nextDouble()
        });
    }

    private static double[] drawDemand(double[] nominal, double[] cv, double[] commonLoading,
                                       Distribution distribution, Random idiosyncraticRandom,
                                       Random commonRandom, boolean useCommonFactor) {
        double[] demand = new double[nominal.length];
        double commonShock = useCommonFactor ? commonRandom.nextGaussian() : 0.0;
        for (int j = 0; j < demand.length; j++) {
            double loading = useCommonFactor ? commonLoading[j] : 0.0;
            double idiosyncraticLoading = Math.sqrt(1.0 - loading * loading);
            if (distribution == Distribution.NORMAL) {
                do {
                    double standardizedShock = loading * commonShock
                            + idiosyncraticLoading * idiosyncraticRandom.nextGaussian();
                    demand[j] = nominal[j] * (1.0 + cv[j] * standardizedShock);
                } while (demand[j] < 0.0);
            } else {
                double logVariance = Math.log1p(cv[j] * cv[j]);
                double standardizedShock = loading * commonShock
                        + idiosyncraticLoading * idiosyncraticRandom.nextGaussian();
                demand[j] = nominal[j] * Math.exp(-0.5 * logVariance
                        + Math.sqrt(logVariance) * standardizedShock);
            }
        }
        return demand;
    }

    private static Sample sample(int id, int period, CovariateVector context,
                                 double[] demand, double weight) {
        LocalDate start = FIRST_WEEK.plusWeeks(period);
        return new Sample(id, new PeriodData(period, start, start.plusDays(6),
                demand, 0, 0.0, 0.0, 0.0), context, weight);
    }

    private static double uniform(Random random, double lower, double upper) {
        return lower + (upper - lower) * random.nextDouble();
    }
}
