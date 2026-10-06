package Model;

/** Pure arithmetic certificate checks, independent of solver tolerance or model size. */
public final class WassersteinCcgBoundSelfCheck {
    public static void main(String[] args) throws Exception {
        require(Double.isNaN(ContextualWassersteinBoxCcgSolver.certifiedGap(10, 11)), "LB>UB clipped to zero");
        require(Double.isNaN(ContextualWassersteinBoxCcgSolver.certifiedGap(10, Double.NaN)), "Missing bound certified");
        require(Double.isNaN(ContextualWassersteinBoxCcgSolver.certifiedGap(Double.POSITIVE_INFINITY, 10)), "Infinite UB certified");
        require(Math.abs(ContextualWassersteinBoxCcgSolver.certifiedGap(100, 90) - .1) < 1e-12, "Wrong gap");
        require(ContextualWassersteinBoxCcgSolver.certifiedGap(100, 100) == 0, "Exact bounds rejected");
        double stalledGap = ContextualWassersteinBoxCcgSolver.certifiedGap(100.009, 99.991);
        require(ContextualWassersteinBoxCcgSolver.stalledWithoutCuts(false, stalledGap, 1e-4),
                "Unchanged model with unresolved gap would repeat");
        require(!ContextualWassersteinBoxCcgSolver.stalledWithoutCuts(true, stalledGap, 1e-4),
                "New support points must permit another iteration");
        require(!ContextualWassersteinBoxCcgSolver.stalledWithoutCuts(false, 5e-5, 1e-4),
                "Converged pass classified as stalled");
        require(ContextualWassersteinBoxCcgSolver.stalledWithoutCuts(false, Double.NaN, 1e-4),
                "Unchanged model with unavailable bound would repeat");
        if (args.length > 0 && args[0].equals("--native")) {
            var params = new Basic.ProcurementParams(java.util.List.of("carrier"), 1,
                    new double[]{3}, new double[]{2}, new double[]{1},
                    new double[][]{{5}}, new double[][]{{1}}, new boolean[][]{{true}}, 1, 1);
            var input = new WassersteinBoxInput(params, new double[][]{{1}, {4}}, new double[]{.5, .5},
                    new double[]{8}, new double[]{8}, .01);
            double bound = ContextualWassersteinBoxCcgSolver.allSpotUpperBound(input, new double[]{1});
            require(bound == 26, "Wrong all-spot robust bound");
            boolean exhausted = false;
            try { WassersteinBoxOracle.solve(input, 0, new double[]{1}, 0, 1, 1e-12, true); }
            catch (WassersteinBoxOracle.TimeLimitException expected) { exhausted = true; }
            require(exhausted, "Oracle started optimization after modeling exhausted its budget");
            var config = new Helper.basicHelper.Config();
            config.enforceDemandEquality = true; config.threads = 1;
            config.timeLimitSeconds = 30; config.maxBendersIter = 1;
            Solution limited = new ContextualWassersteinBoxCcgSolver().solve(input, config).solution();
            require(limited.y != null && Double.isFinite(limited.objValue)
                    && limited.objValue <= bound + 1e-8 && Double.isFinite(limited.bestBound),
                    "Iteration-limited CCG lost its incumbent/bounds");
            require(!limited.certifiedOptimal && limited.solverStatus.startsWith("ITERATION_LIMIT"),
                    "Limited CCG invented optimality");
        }
        System.out.println("WassersteinCcgBoundSelfCheck PASS");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
