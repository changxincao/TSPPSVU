package Model;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact aggregate single-cut Benders/Kelley method for the same W1 model as
 * {@link ContextualWassersteinBoxCcgSolver}.
 *
 * <p>Each cut aggregates the sample-wise oracle affine pieces using the
 * supplied center probabilities. This is the weighted analogue of the
 * single-cut formulation in Gamboa et al. (2021).</p>
 */
public final class ContextualWassersteinBoxSingleCutBendersSolver {
    public Result solve(WassersteinBoxInput input, Config config) throws Exception {
        if (config == null || !config.enforceDemandEquality) {
            throw new IllegalArgumentException("Wasserstein solver requires demand equality.");
        }
        double tolerance = Math.max(1e-7, config.tol);
        List<AggregateCut> cuts = new ArrayList<>();
        double[] seedY = feasibleSeed(input.params);
        OracleAggregate initial = evaluate(input, seedY, input.lipschitzBound(), config);
        cuts.add(initial.cut);

        long start = System.nanoTime();
        double bestUpper = Double.POSITIVE_INFINITY;
        double[] bestY = null;
        double bestEta = Double.NaN;
        MasterResult master = null;
        int iterations = 0;
        int oracleSolves = positiveSampleCount(input);
        boolean converged = false;

        while (iterations < Math.max(1, config.maxBendersIter)) {
            iterations++;
            long iterationStart = System.nanoTime();
            System.out.printf(java.util.Locale.ROOT,
                    "W1-SINGLE iter=%d start cuts=%d bestUB=%s totalSec=%.3f%n",
                    iterations, cuts.size(), format(bestUpper), secondsSince(start));
            long masterStart = System.nanoTime();
            master = solveMaster(input, config, cuts);
            double masterSeconds = (System.nanoTime() - masterStart) / 1.0e9;
            long oracleStart = System.nanoTime();
            OracleAggregate exact = evaluate(input, master.y, master.eta, config);
            double oracleSeconds = (System.nanoTime() - oracleStart) / 1.0e9;
            oracleSolves += positiveSampleCount(input);
            double candidateUpper = input.radius * master.eta + exact.value;
            if (candidateUpper < bestUpper) {
                bestUpper = candidateUpper;
                bestY = master.y.clone();
                bestEta = master.eta;
            }

            double cutTolerance = tolerance * Math.max(1.0, Math.abs(exact.value));
            boolean violated = exact.value > master.beta + cutTolerance;
            if (violated) {
                if (contains(cuts, exact.cut)) {
                    throw new IllegalStateException("Single-cut Benders repeated a violated cut.");
                }
                cuts.add(exact.cut);
            }
            double currentGap = Math.max(0.0, bestUpper - master.objective)
                    / Math.max(1.0, Math.abs(bestUpper));
            System.out.printf(java.util.Locale.ROOT,
                    "W1-SINGLE iter=%d done LB=%.6f bestUB=%.6f gap=%.4f%% eta=%.6f selected=%d violated=%s cuts=%d masterSec=%.3f oracleSec=%.3f iterSec=%.3f totalSec=%.3f%n",
                    iterations, master.objective, bestUpper, 100.0 * currentGap,
                    master.eta, selectedCount(master.y), violated, cuts.size(), masterSeconds,
                    oracleSeconds, (System.nanoTime()-iterationStart)/1.0e9, secondsSince(start));
            double gapTolerance = tolerance * Math.max(1.0, Math.abs(bestUpper));
            if (!violated && bestUpper - master.objective <= gapTolerance) {
                converged = true;
                break;
            }
        }

        if (master == null || bestY == null) {
            throw new IllegalStateException("Single-cut Benders did not produce an incumbent.");
        }
        double relativeGap = Math.max(0.0, bestUpper - master.objective)
                / Math.max(1.0, Math.abs(bestUpper));
        Solution solution = new Solution(bestUpper, bestY,
                (System.nanoTime() - start) / 1.0e9);
        solution.bestBound = master.objective;
        solution.relativeGap = relativeGap;
        solution.iterationCount = iterations;
        solution.cutCount = cuts.size();
        solution.candidateCount = oracleSolves;
        solution.certifiedOptimal = converged && relativeGap <= config.tol;
        solution.solverStatus = solution.certifiedOptimal
                ? "OPTIMAL_W1_SINGLE_CUT" : "ITERATION_LIMIT_W1_SINGLE_CUT";
        return new Result(solution, bestEta, iterations, cuts.size(), oracleSolves);
    }

    private static double secondsSince(long start) { return (System.nanoTime()-start)/1.0e9; }
    private static String format(double value) { return Double.isFinite(value)
            ? String.format(java.util.Locale.ROOT,"%.6f",value) : "NA"; }
    private static int selectedCount(double[] y) { int n=0; for(double v:y)if(v>0.5)n++; return n; }

    private static MasterResult solveMaster(WassersteinBoxInput input,
                                             Config config,
                                             List<AggregateCut> cuts) throws Exception {
        IloCplex cplex = new IloCplex();
        try {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, config.threads);
            cplex.setParam(IloCplex.Param.TimeLimit, config.timeLimitSeconds);
            ProcurementParams params = input.params;
            IloNumVar[] y = cplex.boolVarArray(params.I);
            IloNumVar eta = cplex.numVar(0.0, input.lipschitzBound(), "eta");
            IloNumVar beta = cplex.numVar(Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY, "beta");

            IloLinearNumExpr selected = cplex.linearNumExpr();
            for (IloNumVar variable : y) selected.addTerm(1.0, variable);
            cplex.addGe(selected, params.alpha);
            cplex.addLe(selected, params.beta);

            for (AggregateCut cut : cuts) {
                IloLinearNumExpr rhs = cplex.linearNumExpr(cut.constant);
                for (int i = 0; i < params.I; i++) {
                    rhs.addTerm(cut.yCoefficient[i], y[i]);
                }
                rhs.addTerm(cut.etaCoefficient, eta);
                cplex.addGe(beta, rhs);
            }

            IloLinearNumExpr objective = cplex.linearNumExpr();
            objective.addTerm(input.radius, eta);
            objective.addTerm(1.0, beta);
            cplex.addMinimize(objective);
            if (!cplex.solve() || cplex.getStatus() != IloCplex.Status.Optimal) {
                throw new IllegalStateException(
                        "Single-cut Benders master failed: " + cplex.getStatus());
            }
            double[] yValue = new double[params.I];
            for (int i = 0; i < params.I; i++) {
                yValue[i] = cplex.getValue(y[i]) > 0.5 ? 1.0 : 0.0;
            }
            return new MasterResult(cplex.getObjValue(), cplex.getValue(eta),
                    cplex.getValue(beta), yValue);
        } finally {
            cplex.end();
        }
    }

    private static OracleAggregate evaluate(WassersteinBoxInput input,
                                            double[] y,
                                            double eta,
                                            Config config) throws Exception {
        double value = 0.0;
        double constant = 0.0;
        double etaCoefficient = 0.0;
        double[] yCoefficient = new double[input.params.I];
        for (int s = 0; s < input.sampleCount(); s++) {
            if (input.probability[s] == 0.0) continue;
            WassersteinBoxOracle.Result oracle = WassersteinBoxOracle.solve(
                    input, s, y, eta, config.threads, config.timeLimitSeconds);
            double probability = input.probability[s];
            value += probability * oracle.value();
            constant += probability * oracle.affineConstant();
            etaCoefficient += probability * oracle.etaCoefficient();
            for (int i = 0; i < yCoefficient.length; i++) {
                yCoefficient[i] += probability * oracle.yCoefficient()[i];
            }
        }
        return new OracleAggregate(value,
                new AggregateCut(constant, yCoefficient, etaCoefficient));
    }

    private static double[] feasibleSeed(ProcurementParams params) {
        double[] y = new double[params.I];
        for (int i = 0; i < params.alpha; i++) y[i] = 1.0;
        return y;
    }

    private static int positiveSampleCount(WassersteinBoxInput input) {
        int count = 0;
        for (double probability : input.probability) {
            if (probability > 0.0) count++;
        }
        return count;
    }

    private static boolean contains(List<AggregateCut> cuts, AggregateCut candidate) {
        for (AggregateCut cut : cuts) {
            if (Math.abs(cut.constant - candidate.constant) > 1e-7
                    || Math.abs(cut.etaCoefficient - candidate.etaCoefficient) > 1e-7) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < cut.yCoefficient.length; i++) {
                if (Math.abs(cut.yCoefficient[i] - candidate.yCoefficient[i]) > 1e-7) {
                    same = false;
                    break;
                }
            }
            if (same) return true;
        }
        return false;
    }

    private record AggregateCut(double constant,
                                double[] yCoefficient,
                                double etaCoefficient) {
        private AggregateCut {
            yCoefficient = yCoefficient.clone();
        }
    }

    private record OracleAggregate(double value, AggregateCut cut) {
    }

    private record MasterResult(double objective, double eta, double beta, double[] y) {
        private MasterResult {
            y = y.clone();
        }
    }

    public record Result(Solution solution,
                         double eta,
                         int iterations,
                         int cuts,
                         int oracleSolves) {
    }
}
