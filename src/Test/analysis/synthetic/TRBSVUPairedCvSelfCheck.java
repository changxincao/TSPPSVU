package Test.analysis.synthetic;

import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.*;
import java.util.Arrays;
import java.util.SplittableRandom;

/** No solver: explicit CV pairing, old seed expansion, and bounded tuning grids. */
public final class TRBSVUPairedCvSelfCheck {
    private TRBSVUPairedCvSelfCheck() { }

    public static void main(String[] args) {
        var random = new SplittableRandom(20261020L);
        long[] seeds = {random.nextLong(), random.nextLong(), random.nextLong(),
                random.nextLong(), random.nextLong()};
        require(Arrays.equals(seeds, new long[]{6136304632546739453L, 3026303308496171157L,
                -7216831492882876861L, -1033455150551128321L, 5449940633683987580L}),
                "Previous sequential case seeds were not reproduced.");
        Parameters p = TRBSVUSyntheticDemandGenerator.sampleParameters(50, 75, seeds[0], 10,
                ContextStructure.DENSE_INDEPENDENT_UNIFORM_POSITIVE, BaseStructure.UNIFORM_10_100,
                0.3, 0.5);
        var old = TRBSVUSyntheticDemandGenerator.generateMultiQueryWithLinearTrend(p,
                Distribution.NORMAL, Volatility.MEDIUM, 3, 1000, seeds[2], seeds[3], seeds[4],
                ContextDistribution.UNIFORM);
        var exact = generate(p, p.volatilityParameters(0.4, 0.6), seeds);
        var lower = generate(p, p.volatilityParameters(0.3, 0.5), seeds);
        var low = generate(p, p.volatilityParameters(0.1, 0.3), seeds);
        require(Arrays.equals(p.volatilityParameters(Volatility.MEDIUM), p.volatilityParameters(0.4, 0.6)),
                "Explicit interval changed the old CV values.");
        for (int s = 0; s < 75; s++) {
            require(Arrays.equals(old.history.get(s).demand(), exact.history.get(s).demand()),
                    "Old history demand sequence changed.");
            for (var cell : Arrays.asList(exact, lower, low))
                require(Arrays.equals(old.history.get(s).theta.values(), cell.history.get(s).theta.values())
                        && cell.history.get(s).theta.values()[1] == (double) s / 75,
                        "Historical context pairing failed.");
        }
        for (int q = 0; q < 3; q++) {
            for (var cell : Arrays.asList(exact, lower, low))
                require(Arrays.equals(old.queries.get(q).context.values(), cell.queries.get(q).context.values())
                        && cell.queries.get(q).context.values()[1] == 1,
                        "Query pairing failed.");
            for (int s = 0; s < 1000; s++)
                require(Arrays.equals(old.queries.get(q).oos.get(s).demand(), exact.queries.get(q).oos.get(s).demand()),
                        "Old OOS demand sequence changed.");
        }
        var grid = new double[]{0.8, 0.9, 1, 2};
        var copied = TRBSVUExperiment1Runner.checkedGrid(grid, false);
        grid[0] = 99;
        require(copied[0] == 0.8, "Caller can mutate the frozen grid.");
        for (double[] invalid : new double[][]{{}, {0}, {1, 1}, {Double.NaN}, {2, 1}}) {
            boolean rejected = false;
            try { TRBSVUExperiment1Runner.checkedGrid(invalid, false); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "Invalid grid accepted.");
        }
        System.out.println("TRBSVUPairedCvSelfCheck PASS: old draws exact; cross-CV contexts paired; grids frozen.");
    }

    private static MultiQueryReplication generate(Parameters p, double[] cv, long[] seeds) {
        return TRBSVUSyntheticDemandGenerator.generateMultiQueryWithLinearTrend(p, Distribution.NORMAL,
                cv, 3, 1000, seeds[2], seeds[3], seeds[4], ContextDistribution.UNIFORM);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
