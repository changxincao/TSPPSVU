package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.Sample;

import java.time.LocalDate;
import java.util.ArrayList;
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

    private TRBSVUSyntheticDemandGenerator() {
    }

    public enum Distribution {
        NORMAL, LOGNORMAL
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
        private final double[] base;
        private final double[] market;
        private final double[] trend;
        private final double[] promotion;
        private final double[] attention;
        private final double[] volatilityQuantile;
        private final double[] commonLoading;

        private Parameters(int historicalPeriods, int lanes, double contextCoefficientScale) {
            this.historicalPeriods = historicalPeriods;
            this.contextCoefficientScale = contextCoefficientScale;
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

        public double[] base() { return base.clone(); }
        public double[] market() { return market.clone(); }
        public double[] trend() { return trend.clone(); }
        public double[] promotion() { return promotion.clone(); }
        public double[] attention() { return attention.clone(); }
        public double[] volatilityQuantile() { return volatilityQuantile.clone(); }
        public double[] commonLoading() { return commonLoading.clone(); }

        /** E[mu_j(theta)] over the historical horizon; procurement scale only. */
        public double[] typicalDemand() {
            double[] result = new double[laneCount()];
            double averageTrend = (historicalPeriods - 1.0) / (2.0 * historicalPeriods);
            for (int j = 0; j < result.length; j++) {
                result[j] = base[j] + 0.5 * (market[j] + promotion[j] + attention[j])
                        + averageTrend * trend[j];
            }
            return result;
        }

        /** The nominal level, not the mean after rejecting negative Normal draws. */
        public double[] nominalDemand(CovariateVector context) {
            double[] x = context.values();
            if (x.length != 4) throw new IllegalArgumentException("Context must have M,T,A,C.");
            double[] result = new double[laneCount()];
            for (int j = 0; j < result.length; j++) {
                result[j] = base[j] + market[j] * x[0] + trend[j] * x[1]
                        + promotion[j] * x[2] + attention[j] * x[3];
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

    public static Parameters sampleParameters(int laneCount, int historicalPeriods, long seed) {
        return sampleParameters(laneCount, historicalPeriods, seed, 1.0);
    }

    public static Parameters sampleParameters(int laneCount, int historicalPeriods, long seed,
                                              double contextCoefficientScale) {
        if (laneCount <= 0 || historicalPeriods <= 0) {
            throw new IllegalArgumentException("Lane and history counts must be positive.");
        }
        if (!(contextCoefficientScale > 0.0) || !Double.isFinite(contextCoefficientScale)) {
            throw new IllegalArgumentException("Context coefficient scale must be finite and positive.");
        }
        Parameters p = new Parameters(historicalPeriods, laneCount, contextCoefficientScale);
        Random random = new Random(seed);
        Random loadingRandom = new Random(seed ^ COMMON_LOADING_SALT);
        for (int j = 0; j < laneCount; j++) {
            p.base[j] = uniform(random, 10.0, 30.0);
            p.market[j] = contextCoefficientScale * p.base[j] * uniform(random, 0.3, 0.6);
            p.trend[j] = contextCoefficientScale * p.base[j] * uniform(random, 0.2, 0.4);
            p.promotion[j] = contextCoefficientScale * p.base[j] * uniform(random, 0.3, 0.6);
            p.attention[j] = contextCoefficientScale * p.base[j] * uniform(random, 0.3, 0.6);
            p.volatilityQuantile[j] = random.nextDouble();
            p.commonLoading[j] = uniform(loadingRandom, 0.2, 0.6);
        }
        return p;
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
            CovariateVector context = context(t, h, contextRandom);
            double[] demand = drawDemand(parameters.nominalDemand(context), cv,
                    parameters.commonLoading, distribution, historyRandom,
                    historyCommonRandom, useCommonFactor);
            history.add(sample(t, t, context, demand, 1.0 / h));
        }

        CovariateVector testContext = context(h, h, contextRandom);
        double[] testNominal = parameters.nominalDemand(testContext);
        List<Sample> oos = new ArrayList<>(oosCount);
        for (int draw = 0; draw < oosCount; draw++) {
            double[] demand = drawDemand(testNominal, cv, parameters.commonLoading,
                    distribution, oosRandom, oosCommonRandom, useCommonFactor);
            oos.add(sample(draw, h, testContext.copy(), demand, 1.0 / oosCount));
        }
        return new Replication(parameters, history, testContext, oos);
    }

    private static CovariateVector context(int zeroBasedPeriod, int h, Random random) {
        return new CovariateVector(new double[] {
                random.nextDouble(), (double) zeroBasedPeriod / h,
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
