package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Sample;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
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
        checkParameters(p);
        Replication normal = generate(p, Distribution.NORMAL, Volatility.LOW, oosCount);
        Replication repeat = generate(p, Distribution.NORMAL, Volatility.LOW, oosCount);
        Replication lognormal = generate(p, Distribution.LOGNORMAL, Volatility.HIGH, oosCount);

        require(normal.history.size() == h && normal.oos.size() == oosCount,
                "History/OOS sample count differs from the experiment protocol.");
        require(normal.history.get(0).theta.values()[1] == 0.0
                        && normal.history.get(h - 1).theta.values()[1] == 0.99
                        && normal.testContext.values()[1] == 1.0,
                "Time context does not follow T_t=(t-1)/H.");
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
        }
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
                    + attention[j] - 3.0 * base[j])
                    + (horizonAverageTrend(p) * (trend[j] - base[j]));
            require(Math.abs(typical[j] - expected) < 1e-10,
                    "Typical demand does not match the historical context average.");
        }
        for (Volatility regime : Volatility.values()) {
            double lower = regime == Volatility.LOW ? 0.1
                    : regime == Volatility.MEDIUM ? 0.4 : 0.7;
            double upper = lower + 0.2;
            for (double c : p.volatilityParameters(regime)) {
                require(inRange(c, lower, upper), "Volatility range mismatch.");
            }
        }
        for (double loading : p.commonLoading()) {
            require(inRange(loading, 0.2, 0.6), "Common-factor loading range mismatch.");
        }
    }

    private static double horizonAverageTrend(Parameters p) {
        return (p.historicalPeriods() - 1.0) / (2.0 * p.historicalPeriods());
    }

    private static boolean inRange(double value, double lower, double upper) {
        return value >= lower - 1e-12 && value <= upper + 1e-12;
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
