package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Sample;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextDistribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.MultiQueryReplication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Replication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.util.Arrays;

/** Generation-only checks; no optimizer or procurement-market code is invoked. */
public final class TRBSVUSyntheticDemandGeneratorSelfCheck {
    private TRBSVUSyntheticDemandGeneratorSelfCheck() {
    }

    public static void main(String[] args) {
        int h = 100;
        int oosCount = 1000;
        Parameters p = TRBSVUSyntheticDemandGenerator.sampleParameters(60, h, 17L);
        require(p.contextCoefficientScale() == 1.0, "Default context coefficient scale changed.");
        Parameters explicitDefault = TRBSVUSyntheticDemandGenerator.sampleParameters(
                60, h, 17L, 1.0, ContextStructure.DENSE_PROPORTIONAL);
        require(Arrays.equals(p.base(), explicitDefault.base())
                        && Arrays.equals(p.market(), explicitDefault.market())
                        && Arrays.equals(p.commonLoading(), explicitDefault.commonLoading()),
                "Explicit default structure changed the historical DGP.");
        checkWideSameMeanPairing(h);
        checkThreeLevelWidePositive(h);
        checkThreeLevelIndependentEffects(h);
        checkIndependentUniformPositive(h);
        checkIndependentUniform02Centered(h);
        checkParameters(p);
        checkAlternativeStructures(h);
        checkContextDistributions(p, oosCount);
        checkRegionalFactorPairing(p, oosCount);
        checkPromotionCvPairing(p, oosCount);
        checkShiftedLognormalContexts(p);
        Replication normal = generate(p, Distribution.NORMAL, Volatility.LOW, oosCount);
        Replication repeat = generate(p, Distribution.NORMAL, Volatility.LOW, oosCount);
        Replication lognormal = generate(p, Distribution.LOGNORMAL, Volatility.HIGH, oosCount);
        MultiQueryReplication multi = TRBSVUSyntheticDemandGenerator.generateMultiQuery(
                p, Distribution.NORMAL, Volatility.LOW, 3, oosCount, 23L, 29L, 31L);
        MultiQueryReplication trend =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithLinearTrend(
                        p, Distribution.NORMAL, Volatility.LOW, 3, oosCount,
                        23L, 29L, 31L, ContextDistribution.UNIFORM);

        require(normal.history.size() == h && normal.oos.size() == oosCount,
                "History/OOS sample count differs from the experiment protocol.");
        require(multi.history.size() == h && multi.queries.size() == 3,
                "Multi-query sample count differs from the requested protocol.");
        for (int t = 0; t < h; t++) {
            double[] iid = multi.history.get(t).theta.values();
            double[] linear = trend.history.get(t).theta.values();
            require(linear[1] == (double) t / h,
                    "Linear-trend history has the wrong time coordinate at " + t);
            require(iid[0] == linear[0] && iid[2] == linear[2] && iid[3] == linear[3],
                    "Linear-trend protocol changed a non-trend context coordinate at " + t);
        }
        for (int query = 0; query < trend.queries.size(); query++) {
            double[] iid = multi.queries.get(query).context.values();
            double[] linear = trend.queries.get(query).context.values();
            require(linear[1] == 1.0,
                    "Linear-trend query must use the next-period endpoint.");
            require(iid[0] == linear[0] && iid[2] == linear[2] && iid[3] == linear[3],
                    "Linear-trend protocol changed a non-trend query coordinate.");
        }
        for (Sample sample : normal.history)
            checkUnitCube(sample.theta.values(), "Historical context");
        checkUnitCube(normal.testContext.values(), "Test context");
        require(Arrays.equals(normal.testContext.values(), lognormal.testContext.values()),
                "DGP cells must share the final context.");
        require(Arrays.equals(normal.parameters.typicalDemand(), lognormal.parameters.typicalDemand()),
                "DGP cells must share the procurement demand scale.");
        for (int t = 0; t < h; t++) {
            Sample a = normal.history.get(t);
            Sample b = repeat.history.get(t);
            Sample c = lognormal.history.get(t);
            require(Arrays.equals(a.theta.values(), b.theta.values())
                            && Arrays.equals(a.demand(), b.demand()),
                    "Same seeds did not reproduce history at period " + t);
            require(Arrays.equals(a.theta.values(), c.theta.values()),
                    "DGP cells lost their paired historical contexts at period " + t);
            checkDemand(a, 60);
            checkDemand(c, 60);
            require(Arrays.equals(a.theta.values(), multi.history.get(t).theta.values())
                            && Arrays.equals(a.demand(), multi.history.get(t).demand()),
                    "Multi-query generation changed the shared training sample.");
        }
        require(Arrays.equals(normal.testContext.values(), multi.queries.get(0).context.values()),
                "First multi-query context does not match the single-query protocol.");
        for (int s = 0; s < oosCount; s++) {
            require(Arrays.equals(normal.oos.get(s).demand(),
                            multi.queries.get(0).oos.get(s).demand()),
                    "First multi-query OOS block does not match the single-query protocol.");
        }
        require(!Arrays.equals(multi.queries.get(0).context.values(),
                        multi.queries.get(1).context.values()),
                "Multi-query contexts must be independently generated.");
        for (int query = 0; query < multi.queries.size(); query++)
            checkUnitCube(multi.queries.get(query).context.values(), "Multi-query context");
        for (int s = 0; s < oosCount; s++) {
            Sample a = normal.oos.get(s);
            Sample b = repeat.oos.get(s);
            Sample c = lognormal.oos.get(s);
            require(Arrays.equals(a.demand(), b.demand()),
                    "Same seeds did not reproduce OOS draw " + s);
            require(Arrays.equals(a.theta.values(), normal.testContext.values())
                            && Arrays.equals(c.theta.values(), normal.testContext.values()),
                    "OOS draws must condition on one fixed test context.");
            checkDemand(a, 60);
            checkDemand(c, 60);
        }
        require(!Arrays.equals(normal.history.get(0).demand(),
                        lognormal.history.get(0).demand()),
                "Distinct DGP cells unexpectedly produced identical demands.");
        for (Distribution family : Distribution.values()) {
            for (Volatility regime : Volatility.values()) {
                Replication cell = generate(p, family, regime, oosCount);
                require(Arrays.equals(cell.testContext.values(), normal.testContext.values()),
                        "Six-cell test contexts are not paired.");
                for (Sample sample : cell.history) checkDemand(sample, 60);
                for (Sample sample : cell.oos) checkDemand(sample, 60);
            }
        }
        System.out.println("PASS synthetic DGP: 60 lanes, 100 historical periods, "
                + "1000 fixed-context OOS draws; six cells, paired contexts, reproducible demands.");
    }

    private static void checkUnitCube(double[] context, String label) {
        require(context.length == 4, label + " dimension changed.");
        for (double value : context)
            require(value >= 0.0 && value < 1.0, label + " left [0,1). ");
    }

    private static void checkShiftedLognormalContexts(Parameters parameters) {
        MultiQueryReplication original =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithRegionalFactors(
                        parameters, Distribution.LOGNORMAL, Volatility.MEDIUM,
                        2, 10, 41L, 43L, 47L, ContextDistribution.UNIFORM,
                        5, 0.5);
        require(TRBSVUSyntheticDemandGenerator.shiftLognormalContexts(original, 0.0)
                        == original, "Zero context shift changed the paired baseline.");
        MultiQueryReplication shifted =
                TRBSVUSyntheticDemandGenerator.shiftLognormalContexts(original, 0.4);
        MultiQueryReplication highHigh =
                TRBSVUSyntheticDemandGenerator.rewindowLognormalContexts(
                        original, 0.4, true, true);
        MultiQueryReplication lowLow =
                TRBSVUSyntheticDemandGenerator.rewindowLognormalContexts(
                        original, 0.4, false, false);
        for (int t = 0; t < original.history.size(); t++) {
            checkShiftedSample(parameters, original.history.get(t),
                    shifted.history.get(t), 0.0, 0.6);
            checkShiftedSample(parameters, original.history.get(t),
                    highHigh.history.get(t), 0.4, 0.6);
            checkShiftedSample(parameters, original.history.get(t),
                    lowLow.history.get(t), 0.0, 0.6);
        }
        for (int q = 0; q < original.queries.size(); q++) {
            for (int s = 0; s < original.queries.get(q).oos.size(); s++) {
                checkShiftedSample(parameters, original.queries.get(q).oos.get(s),
                        shifted.queries.get(q).oos.get(s), 0.4, 0.6);
                checkShiftedSample(parameters, original.queries.get(q).oos.get(s),
                        highHigh.queries.get(q).oos.get(s), 0.4, 0.6);
                checkShiftedSample(parameters, original.queries.get(q).oos.get(s),
                        lowLow.queries.get(q).oos.get(s), 0.0, 0.6);
                double[] lowHighDemand = shifted.queries.get(q).oos.get(s).demand();
                double[] highHighDemand = highHigh.queries.get(q).oos.get(s).demand();
                require(java.util.Arrays.equals(lowHighDemand, highHighDemand),
                        "Low/high and high/high must use identical paired OOS draws.");
            }
        }
    }

    private static void checkShiftedSample(Parameters parameters, Sample original,
                                           Sample shifted, double offset, double scale) {
        double[] oldMean = parameters.nominalDemand(original.theta);
        double[] newMean = parameters.nominalDemand(shifted.theta);
        for (int k = 0; k < 4; k++) {
            require(Math.abs(shifted.theta.values()[k]
                            - (offset + scale * original.theta.values()[k])) < 1e-12,
                    "Context shift changed the latent draw.");
        }
        for (int j = 0; j < oldMean.length; j++) {
            double expected = original.demand()[j] * newMean[j] / oldMean[j];
            require(Math.abs(shifted.demand()[j] - expected)
                            <= 1e-10 * Math.max(1.0, expected),
                    "Context shift changed the conditional lognormal innovation.");
        }
    }

    private static void checkAlternativeStructures(int h) {
        for (ContextStructure structure : ContextStructure.values()) {
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    20, h, 41L, 1.3, structure, 0.6, 0.8);
            for (double loading : parameters.commonLoading())
                require(inRange(loading, 0.6, 0.8), "Custom common-loading range mismatch.");
            for (int corner = 0; corner < 16; corner++) {
                double[] x = new double[4];
                for (int k = 0; k < x.length; k++) x[k] = (corner >>> k) & 1;
                for (double value : parameters.nominalDemand(new CovariateVector(x)))
                    require(Double.isFinite(value) && value > 0.0,
                            "Alternative context structure produced nonpositive nominal demand.");
            }
        }
    }

    private static void checkContextDistributions(Parameters parameters, int oosCount) {
        for (ContextDistribution distribution : ContextDistribution.values()) {
            MultiQueryReplication replication = TRBSVUSyntheticDemandGenerator.generateMultiQuery(
                    parameters, Distribution.LOGNORMAL, Volatility.LOW, 3, oosCount,
                    23L, 29L, 31L, distribution);
            for (Sample sample : replication.history) {
                checkUnitCubeClosed(sample.theta.values(), "Historical context");
                if (distribution == ContextDistribution.BINARY)
                    for (double value : sample.theta.values())
                        require(value == 0.0 || value == 1.0,
                                "Binary context left {0,1}.");
            }
        }
    }

    private static void checkRegionalFactorPairing(Parameters parameters, int oosCount) {
        MultiQueryReplication baseline = TRBSVUSyntheticDemandGenerator.generateMultiQuery(
                parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, 3, oosCount,
                23L, 29L, 31L, ContextDistribution.UNIFORM);
        MultiQueryReplication tauOne =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithRegionalFactors(
                        parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, 3, oosCount,
                        23L, 29L, 31L, ContextDistribution.UNIFORM, 5, 1.0);
        MultiQueryReplication regional =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithRegionalFactors(
                        parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, 3, oosCount,
                        23L, 29L, 31L, ContextDistribution.UNIFORM, 5, 0.5);
        for (int t = 0; t < baseline.history.size(); t++) {
            require(Arrays.equals(baseline.history.get(t).demand(),
                            tauOne.history.get(t).demand()),
                    "tau=1 regional hierarchy did not reproduce baseline history.");
            require(Arrays.equals(baseline.history.get(t).theta.values(),
                            regional.history.get(t).theta.values()),
                    "Regional hierarchy changed historical contexts.");
        }
        boolean changed = false;
        for (int query = 0; query < baseline.queries.size(); query++) {
            require(Arrays.equals(baseline.queries.get(query).context.values(),
                            regional.queries.get(query).context.values()),
                    "Regional hierarchy changed query contexts.");
            for (int draw = 0; draw < oosCount; draw++) {
                double[] original = baseline.queries.get(query).oos.get(draw).demand();
                require(Arrays.equals(original,
                                tauOne.queries.get(query).oos.get(draw).demand()),
                        "tau=1 regional hierarchy did not reproduce baseline OOS.");
                changed |= !Arrays.equals(original,
                        regional.queries.get(query).oos.get(draw).demand());
            }
        }
        require(changed, "tau=0.5 regional hierarchy did not change joint draws.");
    }

    private static void checkPromotionCvPairing(Parameters parameters, int oosCount) {
        MultiQueryReplication baseline =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithRegionalFactors(
                        parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, 3, oosCount,
                        23L, 29L, 31L, ContextDistribution.UNIFORM, 5, 0.5);
        MultiQueryReplication zero =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithRegionalFactors(
                        parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, 3, oosCount,
                        23L, 29L, 31L, ContextDistribution.UNIFORM, 5, 0.5, 0.0);
        MultiQueryReplication varied =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithRegionalFactors(
                        parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, 3, oosCount,
                        23L, 29L, 31L, ContextDistribution.UNIFORM, 5, 0.5, 0.8);
        MultiQueryReplication demandLevel =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithRegionalFactors(
                        parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, 3, oosCount,
                        23L, 29L, 31L, ContextDistribution.UNIFORM, 5, 0.5, 1.0, true);
        for (int t = 0; t < baseline.history.size(); t++) {
            require(Arrays.equals(baseline.history.get(t).demand(), zero.history.get(t).demand()),
                    "Zero CV slope changed baseline history.");
            require(Arrays.equals(baseline.history.get(t).theta.values(),
                            varied.history.get(t).theta.values()),
                    "CV slope changed historical contexts.");
            require(Arrays.equals(baseline.history.get(t).theta.values(),
                            demandLevel.history.get(t).theta.values()),
                    "Demand-level CV changed historical contexts.");
        }
        for (int q = 0; q < baseline.queries.size(); q++) {
            require(Arrays.equals(baseline.queries.get(q).context.values(),
                            varied.queries.get(q).context.values()),
                    "CV slope changed query contexts.");
            require(Arrays.equals(baseline.queries.get(q).context.values(),
                            demandLevel.queries.get(q).context.values()),
                    "Demand-level CV changed query contexts.");
            double[] nominal = parameters.nominalDemand(demandLevel.queries.get(q).context);
            for (int j = 0; j < nominal.length; j++) {
                double mean = 0.0;
                for (Sample draw : demandLevel.queries.get(q).oos)
                    mean += draw.demand()[j] / oosCount;
                require(Math.abs(mean / nominal[j] - 1.0) < 0.15,
                        "Conditional-CV lognormal draws lost their nominal mean.");
            }
            for (int s = 0; s < oosCount; s++) {
                require(Arrays.equals(baseline.queries.get(q).oos.get(s).demand(),
                                zero.queries.get(q).oos.get(s).demand()),
                        "Zero CV slope changed baseline OOS draws.");
            }
        }
    }

    private static void checkUnitCubeClosed(double[] context, String label) {
        require(context.length == 4, label + " dimension changed.");
        for (double value : context)
            require(value >= 0.0 && value <= 1.0, label + " left [0,1]. ");
    }

    private static void checkWideSameMeanPairing(int h) {
        Parameters original = TRBSVUSyntheticDemandGenerator.sampleParameters(
                60, h, 43L, 1.0, ContextStructure.DENSE_PROPORTIONAL);
        Parameters wide = TRBSVUSyntheticDemandGenerator.sampleParameters(
                60, h, 43L, 1.0, ContextStructure.DENSE_WIDE_SAME_MEAN);
        Parameters signed = TRBSVUSyntheticDemandGenerator.sampleParameters(
                60, h, 43L, 1.0, ContextStructure.DENSE_WIDE_SIGNED_SAME_MEAN);
        require(Arrays.equals(original.base(), wide.base())
                        && Arrays.equals(original.volatilityQuantile(), wide.volatilityQuantile())
                        && Arrays.equals(original.commonLoading(), wide.commonLoading())
                        && Arrays.equals(original.base(), signed.base())
                        && Arrays.equals(original.volatilityQuantile(), signed.volatilityQuantile())
                        && Arrays.equals(original.commonLoading(), signed.commonLoading()),
                "Wide same-mean structure changed a non-context random stream.");
        for (int j = 0; j < original.laneCount(); j++) {
            require(close(wide.market()[j] / wide.base()[j],
                            3.0 * (original.market()[j] / original.base()[j] - 0.3))
                            && close(wide.trend()[j] / wide.base()[j],
                            3.0 * (original.trend()[j] / original.base()[j] - 0.2))
                            && close(wide.promotion()[j] / wide.base()[j],
                            3.0 * (original.promotion()[j] / original.base()[j] - 0.3))
                            && close(wide.attention()[j] / wide.base()[j],
                            3.0 * (original.attention()[j] / original.base()[j] - 0.3)),
                    "Wide same-mean coefficients are not quantile-paired with the baseline.");
            require(close(signed.market()[j] / signed.base()[j],
                            -0.2 + (1.3 / 0.3)
                                    * (original.market()[j] / original.base()[j] - 0.3))
                            && close(signed.trend()[j] / signed.base()[j],
                            -0.2 + (1.0 / 0.2)
                                    * (original.trend()[j] / original.base()[j] - 0.2))
                            && close(signed.promotion()[j] / signed.base()[j],
                            -0.2 + (1.3 / 0.3)
                                    * (original.promotion()[j] / original.base()[j] - 0.3))
                            && close(signed.attention()[j] / signed.base()[j],
                            -0.2 + (1.3 / 0.3)
                                    * (original.attention()[j] / original.base()[j] - 0.3)),
                    "Signed wide coefficients are not quantile-paired with the baseline.");
        }
    }

    private static void checkThreeLevelWidePositive(int h) {
        Parameters original = TRBSVUSyntheticDemandGenerator.sampleParameters(
                50, h, 47L, 1.0, ContextStructure.DENSE_PROPORTIONAL,
                BaseStructure.UNIFORM_10_30);
        Parameters candidate = TRBSVUSyntheticDemandGenerator.sampleParameters(
                50, h, 47L, 1.0, ContextStructure.DENSE_WIDE_POSITIVE,
                BaseStructure.THREE_LEVEL_WIDE);
        require(Arrays.equals(original.volatilityQuantile(), candidate.volatilityQuantile())
                        && Arrays.equals(original.commonLoading(), candidate.commonLoading()),
                "Three-level candidate changed the paired volatility streams.");
        int low = 0, medium = 0, high = 0;
        for (int j = 0; j < candidate.laneCount(); j++) {
            double base = candidate.base()[j];
            if (inRange(base, 5.0, 15.0)) low++;
            else if (inRange(base, 25.0, 50.0)) medium++;
            else if (inRange(base, 75.0, 125.0)) high++;
            else throw new AssertionError("Three-level base outside all declared intervals.");
            require(inRange(candidate.market()[j] / base, 0.1, 0.9)
                            && inRange(candidate.trend()[j] / base, 0.1, 0.9)
                            && inRange(candidate.promotion()[j] / base, 0.1, 0.9)
                            && inRange(candidate.attention()[j] / base, 0.1, 0.9),
                    "Wide-positive coefficient outside U(0.1,0.9).");
        }
        require(low == 17 && medium == 17 && high == 16,
                "Three-level base allocation is not balanced.");
    }

    private static void checkThreeLevelIndependentEffects(int h) {
        Parameters candidate = TRBSVUSyntheticDemandGenerator.sampleParameters(
                50, h, 53L, 1.0, ContextStructure.DENSE_INDEPENDENT_LEVELS,
                BaseStructure.THREE_LEVEL_WIDE);
        int[] counts = new int[3];
        double[][] coefficients = {candidate.market(), candidate.trend(),
                candidate.promotion(), candidate.attention()};
        for (int j = 0; j < candidate.laneCount(); j++) {
            for (double[] coefficient : coefficients) {
                double ratio = coefficient[j] / candidate.base()[j];
                if (inRange(ratio, 0.1, 0.3)) counts[0]++;
                else if (inRange(ratio, 0.4, 0.6)) counts[1]++;
                else if (inRange(ratio, 0.7, 0.9)) counts[2]++;
                else throw new AssertionError("Independent effect outside all declared tiers.");
            }
        }
        for (int count : counts) require(count > 0, "An independent effect tier was never used.");
    }

    private static void checkIndependentUniformPositive(int h) {
        Parameters p = TRBSVUSyntheticDemandGenerator.sampleParameters(
                50, h, 59L, 1.8, ContextStructure.DENSE_INDEPENDENT_UNIFORM_POSITIVE,
                BaseStructure.THREE_LEVEL_10_30_50_70);
        int[] counts = new int[3];
        double[][] coefficients = {p.market(), p.trend(), p.promotion(), p.attention()};
        for (int j = 0; j < p.laneCount(); j++) {
            double base = p.base()[j];
            int tier = base < 30.0 ? 0 : base < 50.0 ? 1 : 2;
            counts[tier]++;
            require(base >= 10.0 && base < 70.0, "Independent-uniform base outside tiers.");
            double sum = 0.0;
            for (double[] coefficient : coefficients) {
                require(coefficient[j] >= 0.0 && coefficient[j] < 1.8 * base,
                        "Independent-uniform coefficient outside its declared range.");
                sum += coefficient[j];
            }
            require(Math.abs(p.typicalDemand()[j] - (base + 0.5 * sum)) < 1e-9,
                    "Independent-uniform procurement demand scale is wrong.");
        }
        require(counts[0] == 17 && counts[1] == 17 && counts[2] == 16,
                "Independent-uniform base tiers are not balanced.");
    }

    private static void checkIndependentUniform02Centered(int h) {
        Parameters p = TRBSVUSyntheticDemandGenerator.sampleParameters(
                50, h, 59L, 1.5, ContextStructure.DENSE_INDEPENDENT_UNIFORM_02_CENTERED,
                BaseStructure.THREE_LEVEL_10_30_50_70);
        double[][] coefficients = {p.market(), p.trend(), p.promotion(), p.attention()};
        for (int j = 0; j < p.laneCount(); j++) {
            double base = p.base()[j];
            double sum = 0.0;
            for (double[] coefficient : coefficients) {
                require(coefficient[j] >= 0.0 && coefficient[j] < 0.3 * base,
                        "Unnormalized centered coefficient outside U(0,0.2) range.");
                sum += coefficient[j];
            }
            require(Math.abs(p.typicalDemand()[j] - base) < 1e-9,
                    "Centered procurement demand scale must equal base.");
            require(base - 0.5 * sum >= 0.4 * base - 1e-9,
                    "Centered conditional demand lost positivity guarantee.");
        }
    }

    private static Replication generate(Parameters p, Distribution family,
                                        Volatility regime, int oosCount) {
        return TRBSVUSyntheticDemandGenerator.generate(p, family, regime,
                oosCount, 23L, 29L, 31L);
    }

    private static void checkParameters(Parameters p) {
        double[] base = p.nominalDemand(new CovariateVector(new double[4]));
        double[] market = p.nominalDemand(new CovariateVector(new double[] {1, 0, 0, 0}));
        double[] trend = p.nominalDemand(new CovariateVector(new double[] {0, 1, 0, 0}));
        double[] promotion = p.nominalDemand(new CovariateVector(new double[] {0, 0, 1, 0}));
        double[] attention = p.nominalDemand(new CovariateVector(new double[] {0, 0, 0, 1}));
        double[] typical = p.typicalDemand();
        for (int j = 0; j < p.laneCount(); j++) {
            require(base[j] >= 10.0 && base[j] <= 30.0, "Baseline range mismatch.");
            require(inRange((market[j] - base[j]) / base[j], 0.3, 0.6)
                            && inRange((promotion[j] - base[j]) / base[j], 0.3, 0.6)
                            && inRange((attention[j] - base[j]) / base[j], 0.3, 0.6)
                            && inRange((trend[j] - base[j]) / base[j], 0.2, 0.4),
                    "Context-effect range mismatch.");
            double expected = base[j] + 0.5 * (market[j] + promotion[j]
                    + trend[j] + attention[j] - 4.0 * base[j]);
            require(Math.abs(typical[j] - expected) < 1e-10,
                    "Typical demand does not match the context-distribution mean.");
        }
        for (Volatility regime : Volatility.values()) {
            double lower = regime == Volatility.VERY_LOW ? 0.02
                    : regime == Volatility.LOW ? 0.1
                    : regime == Volatility.MEDIUM ? 0.4 : 0.7;
            double upper = regime == Volatility.VERY_LOW ? 0.10 : lower + 0.2;
            for (double c : p.volatilityParameters(regime)) {
                require(inRange(c, lower, upper), "Volatility range mismatch.");
            }
        }
        for (double loading : p.commonLoading()) {
            require(inRange(loading, 0.2, 0.6), "Common-factor loading range mismatch.");
        }
    }

    private static boolean inRange(double value, double lower, double upper) {
        return value >= lower - 1e-12 && value <= upper + 1e-12;
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) <= 1e-12;
    }

    private static void checkDemand(Sample sample, int lanes) {
        require(sample.demand().length == lanes, "Wrong lane count.");
        for (double value : sample.demand()) {
            require(Double.isFinite(value) && value >= 0.0,
                    "Demand must be finite and nonnegative.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
