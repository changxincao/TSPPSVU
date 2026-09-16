package Test.analysis.synthetic;

/** Deterministic checks for the frozen empirical quantile and tail conventions. */
public final class TRBSVUStatisticsSelfCheck {
    private TRBSVUStatisticsSelfCheck() { }

    public static void main(String[] args) {
        double[] sequence = new double[1000];
        for (int k = 0; k < sequence.length; k++) sequence[k] = k + 1.0;
        TRBSVUStatistics.Summary first = TRBSVUStatistics.summarize(sequence);
        requireClose(first.mean(), 500.5, "mean");
        requireClose(first.q95(), 950.05, "linearly interpolated q95");
        requireClose(first.cvar95(), 975.5, "largest-50 CVaR95");
        requireClose(first.maximum(), 1000.0, "maximum");

        double[] tied = new double[1000];
        for (int k = 960; k < tied.length; k++) tied[k] = 10.0;
        TRBSVUStatistics.Summary second = TRBSVUStatistics.summarize(tied);
        requireClose(second.q95(), 0.0, "tied q95");
        requireClose(second.cvar95(), 8.0, "strict five-percent tail with ties");
        if (!TRBSVUStatistics.better(100.0, 4.0, 2.0, 100.0, 5.0, 1.0)
                || !TRBSVUStatistics.better(100.0, 4.0, 1.0, 100.0, 4.0, 2.0)
                || TRBSVUStatistics.better(101.0, 1.0, 1.0, 100.0, 5.0, 2.0))
            throw new AssertionError("Validation tie-break order changed.");
        System.out.println("TRBSVUStatisticsSelfCheck PASS");
    }

    private static void requireClose(double actual, double expected, String name) {
        if (Math.abs(actual - expected) > 1e-10)
            throw new AssertionError(name + ": expected=" + expected + ", actual=" + actual);
    }
}
