package Model;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import ilog.concert.IloException;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

/** Exact branch-and-Benders-cut solver with sample-wise lazy cuts. */
public final class ContextualWassersteinBoxCallbackMultiCutSolver {
    public Result solve(WassersteinBoxInput input, Config config) throws Exception {
        if (config == null || !config.enforceDemandEquality) {
            throw new IllegalArgumentException("Wasserstein solver requires demand equality.");
        }
        ProcurementParams params = input.params;
        IloCplex cplex = new IloCplex();
        long start = System.nanoTime();
        try {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, 1);
            cplex.setParam(IloCplex.Param.TimeLimit, config.timeLimitSeconds);
            cplex.setParam(IloCplex.Param.MIP.Strategy.Search,
                    IloCplex.MIPSearch.Traditional);

            IloNumVar[] y = cplex.boolVarArray(params.I);
            IloNumVar eta = cplex.numVar(0.0, input.lipschitzBound(), "eta");
            IloNumVar[] t = cplex.numVarArray(input.sampleCount(),
                    0.0, Double.POSITIVE_INFINITY);

            IloLinearNumExpr selected = cplex.linearNumExpr();
            for (IloNumVar variable : y) selected.addTerm(1.0, variable);
            cplex.addGe(selected, params.alpha);
            cplex.addLe(selected, params.beta);
            for (int s = 0; s < input.sampleCount(); s++) {
                if (input.probability[s] == 0.0) cplex.addEq(t[s], 0.0);
            }

            IloLinearNumExpr objective = cplex.linearNumExpr();
            objective.addTerm(input.radius, eta);
            for (int s = 0; s < input.sampleCount(); s++) {
                objective.addTerm(input.probability[s], t[s]);
            }
            cplex.addMinimize(objective);

            Stats stats = new Stats();
            addSeedCuts(cplex, input, config, y, eta, t, stats);
            LazyCuts callback = new LazyCuts(cplex, input, config,
                    y, eta, t, stats, start);
            cplex.use(callback);

            System.out.printf(java.util.Locale.ROOT,
                    "W1-CALLBACK start seedCuts=%d totalSec=%.3f%n",
                    stats.cuts, secondsSince(start));
            boolean solved = cplex.solve();
            if (stats.failure != null) throw stats.failure;
            if (!solved || !cplex.isPrimalFeasible()) {
                throw new IllegalStateException(
                        "Wasserstein callback master failed: " + cplex.getStatus());
            }

            double[] yValue = binaryValues(cplex.getValues(y));
            double etaValue = cplex.getValue(eta);
            double exactObjective = input.radius * etaValue;
            for (int s = 0; s < input.sampleCount(); s++) {
                if (input.probability[s] == 0.0) continue;
                WassersteinBoxOracle.Result oracle = WassersteinBoxOracle.solve(
                        input, s, yValue, etaValue, 1, config.timeLimitSeconds);
                stats.oracles++;
                exactObjective += input.probability[s] * oracle.value();
            }

            double bestBound = cplex.getBestObjValue();
            double gap = Math.max(0.0, exactObjective - bestBound)
                    / Math.max(1.0, Math.abs(exactObjective));
            boolean certified = cplex.getStatus() == IloCplex.Status.Optimal
                    && Math.abs(exactObjective - cplex.getObjValue())
                    <= Math.max(1e-6, config.tol * Math.max(1.0, Math.abs(exactObjective)));

            Solution solution = new Solution(exactObjective, yValue,
                    secondsSince(start));
            solution.bestBound = bestBound;
            solution.relativeGap = gap;
            solution.iterationCount = stats.calls;
            solution.cutCount = stats.cuts;
            solution.candidateCount = stats.oracles;
            solution.certifiedOptimal = certified;
            solution.solverStatus = certified
                    ? "OPTIMAL_W1_CALLBACK_MULTI_CUT"
                    : cplex.getStatus() + "_W1_CALLBACK_MULTI_CUT";
            System.out.printf(java.util.Locale.ROOT,
                    "W1-CALLBACK done status=%s objective=%.6f bound=%.6f gap=%.4f%% callbacks=%d cuts=%d oracles=%d nodes=%d totalSec=%.3f%n",
                    cplex.getStatus(), exactObjective, bestBound, 100.0 * gap,
                    stats.calls, stats.cuts, stats.oracles,
                    cplex.getNnodes64(), secondsSince(start));
            return new Result(solution, etaValue, stats.calls,
                    stats.cuts, stats.oracles, cplex.getNnodes64());
        } finally {
            cplex.end();
        }
    }

    private static void addSeedCuts(IloCplex cplex,
                                    WassersteinBoxInput input,
                                    Config config,
                                    IloNumVar[] y,
                                    IloNumVar eta,
                                    IloNumVar[] t,
                                    Stats stats) throws Exception {
        double[] seedY = new double[input.params.I];
        for (int i = 0; i < input.params.alpha; i++) seedY[i] = 1.0;
        double seedEta = input.lipschitzBound();
        for (int s = 0; s < input.sampleCount(); s++) {
            if (input.probability[s] == 0.0) continue;
            WassersteinBoxOracle.Result oracle = WassersteinBoxOracle.solve(
                    input, s, seedY, seedEta, 1, config.timeLimitSeconds);
            stats.oracles++;
            cplex.addGe(t[s], cutExpression(cplex, y, eta, oracle));
            stats.cuts++;
        }
    }

    private static IloLinearNumExpr cutExpression(
            IloCplex cplex,
            IloNumVar[] y,
            IloNumVar eta,
            WassersteinBoxOracle.Result oracle) throws IloException {
        IloLinearNumExpr rhs = cplex.linearNumExpr(oracle.affineConstant());
        for (int i = 0; i < y.length; i++) {
            rhs.addTerm(oracle.yCoefficient()[i], y[i]);
        }
        rhs.addTerm(oracle.etaCoefficient(), eta);
        return rhs;
    }

    private static double[] binaryValues(double[] values) {
        double[] result = values.clone();
        for (int i = 0; i < result.length; i++) {
            result[i] = result[i] > 0.5 ? 1.0 : 0.0;
        }
        return result;
    }

    private static double secondsSince(long start) {
        return (System.nanoTime() - start) / 1.0e9;
    }

    private static final class Stats {
        int calls;
        int cuts;
        int oracles;
        Exception failure;
    }

    private static final class LazyCuts extends IloCplex.LazyConstraintCallback {
        private final IloCplex cplex;
        private final WassersteinBoxInput input;
        private final Config config;
        private final IloNumVar[] y;
        private final IloNumVar eta;
        private final IloNumVar[] t;
        private final Stats stats;
        private final long start;

        private LazyCuts(IloCplex cplex,
                         WassersteinBoxInput input,
                         Config config,
                         IloNumVar[] y,
                         IloNumVar eta,
                         IloNumVar[] t,
                         Stats stats,
                         long start) {
            this.cplex = cplex;
            this.input = input;
            this.config = config;
            this.y = y;
            this.eta = eta;
            this.t = t;
            this.stats = stats;
            this.start = start;
        }

        @Override
        protected void main() throws IloException {
            stats.calls++;
            try {
                double[] yValue = binaryValues(getValues(y));
                double etaValue = getValue(eta);
                double[] tValue = getValues(t);
                int added = 0;
                for (int s = 0; s < input.sampleCount(); s++) {
                    if (input.probability[s] == 0.0) continue;
                    WassersteinBoxOracle.Result oracle = WassersteinBoxOracle.solve(
                            input, s, yValue, etaValue, 1,
                            config.timeLimitSeconds);
                    stats.oracles++;
                    double tolerance = Math.max(1e-7, config.tol)
                            * Math.max(1.0, Math.abs(oracle.value()));
                    if (oracle.value() > tValue[s] + tolerance) {
                        add(cplex.ge(cplex.diff(t[s],
                                cutExpression(cplex, y, eta, oracle)), 0.0));
                        stats.cuts++;
                        added++;
                    }
                }
                System.out.printf(java.util.Locale.ROOT,
                        "W1-CALLBACK candidate=%d obj=%.6f eta=%.6f selected=%d added=%d cuts=%d oracles=%d nodeLB=%.6f depth=%d totalSec=%.3f%n",
                        stats.calls, getObjValue(), etaValue,
                        selectedCount(yValue), added, stats.cuts, stats.oracles,
                        getBestObjValue(), getCurrentNodeDepth(), secondsSince(start));
            } catch (Exception ex) {
                stats.failure = ex;
                abort();
            }
        }
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    public record Result(Solution solution,
                         double eta,
                         int callbackCalls,
                         int cuts,
                         int oracleSolves,
                         long nodes) {
    }
}
