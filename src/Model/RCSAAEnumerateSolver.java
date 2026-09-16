package Model;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;

import java.util.List;

final class RCSAAEnumerateSolver {

    Solution solve(Data data, Config cfg) throws Exception {
        ProcurementParams p = data.params;
        List<Sample> samples = data.samples;
        int wSize = samples.size();
        int iSize = p.I;

        if (iSize > 30) {
            throw new IllegalArgumentException(
                    "RCSAA enumerate solver only supports I <= 30, current I=" + iSize);
        }

        long feasibleCount = countFeasibleSelections(iSize, p.alpha, p.beta);
        if (feasibleCount > 5_000_000L) {
            throw new IllegalArgumentException(
                    "RCSAA enumerate solver refuses to scan " + feasibleCount + " feasible first-stage solutions.");
        }

        double[] pi = RCSAADecompositionSupport.validateAndNormalizeWeights(samples);

        long t0 = System.nanoTime();
        double bestObj = Double.POSITIVE_INFINITY;
        double[] bestY = null;
        long evaluated = 0L;

        System.out.println(String.format(
                "RCSAA-ENUM start I=%d alpha=%d beta=%d feasibleY=%d W=%d",
                iSize, p.alpha, p.beta, feasibleCount, wSize));

        int totalMasks = 1 << iSize;
        for (int mask = 0; mask < totalMasks; mask++) {
            int selected = Integer.bitCount(mask);
            if (selected < p.alpha || selected > p.beta) {
                continue;
            }

            double[] y = decodeMask(mask, iSize);
            double[] qTrue = new double[wSize];
            for (int w = 0; w < wSize; w++) {
                RCSAADecompositionSupport.ScenarioCut cut =
                        RCSAADecompositionSupport.solveScenarioCut(
                                p, samples.get(w).demand(), y, w, cfg.enforceDemandEquality);
                qTrue[w] = cut.qValue;
            }

            double obj = RCSAADecompositionSupport.evaluateObjective(qTrue, pi, cfg.lambda);
            evaluated++;

            if (obj + cfg.tol < bestObj) {
                bestObj = obj;
                bestY = y;
                System.out.println(String.format(
                        "RCSAA-ENUM improve eval=%d/%d obj=%.6f sel=%d elapsedSec=%.3f",
                        evaluated,
                        feasibleCount,
                        bestObj,
                        selected,
                        secondsBetween(t0, System.nanoTime())));
            } else if (evaluated % 100 == 0 || evaluated == feasibleCount) {
                System.out.println(String.format(
                        "RCSAA-ENUM progress eval=%d/%d bestObj=%.6f elapsedSec=%.3f",
                        evaluated,
                        feasibleCount,
                        bestObj,
                        secondsBetween(t0, System.nanoTime())));
            }
        }

        if (bestY == null) {
            throw new IllegalStateException("No feasible first-stage solution found by enumeration.");
        }

        long t1 = System.nanoTime();
        System.out.println(String.format(
                "RCSAA-ENUM done eval=%d bestObj=%.6f totalSec=%.3f",
                evaluated,
                bestObj,
                secondsBetween(t0, t1)));
        Solution solution = new Solution(bestObj, bestY, secondsBetween(t0, t1));
        solution.solverStatus = "OPTIMAL_ENUMERATION";
        solution.bestBound = bestObj;
        solution.relativeGap = 0.0;
        solution.iterationCount = 1;
        solution.cutCount = 0;
        solution.candidateCount = evaluated;
        solution.certifiedOptimal = true;
        return solution;
    }

    private static double[] decodeMask(int mask, int iSize) {
        double[] y = new double[iSize];
        for (int i = 0; i < iSize; i++) {
            y[i] = ((mask >>> i) & 1) == 1 ? 1.0 : 0.0;
        }
        return y;
    }

    private static long countFeasibleSelections(int n, int alpha, int beta) {
        long total = 0L;
        for (int k = alpha; k <= beta; k++) {
            total += binom(n, k);
        }
        return total;
    }

    private static long binom(int n, int k) {
        if (k < 0 || k > n) {
            return 0L;
        }
        int use = Math.min(k, n - k);
        long value = 1L;
        for (int i = 1; i <= use; i++) {
            value = value * (n - use + i) / i;
        }
        return value;
    }

    private static double secondsBetween(long start, long end) {
        return (end - start) / 1e9;
    }
}
