package Model;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloQuadNumExpr;
import ilog.cplex.IloCplex;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact sample-wise multi-cut Benders with level regularization for the
 * bounded-box, scaled-L1 W1 model. This is the network-oriented variant of
 * Gamboa et al. (2021): regularization changes query points, not cuts or bounds.
 */
public final class ContextualWassersteinBoxRegularizedMultiCutBendersSolver {
    public Result solve(WassersteinBoxInput input, Config config,
                        double levelFraction) throws Exception {
        if (config == null || !config.enforceDemandEquality) {
            throw new IllegalArgumentException("Wasserstein solver requires demand equality.");
        }
        if (!Double.isFinite(levelFraction)
                || !(levelFraction > 0.0 && levelFraction < 1.0)) {
            throw new IllegalArgumentException("levelFraction must be in (0,1).");
        }

        List<List<SampleCut>> cuts = emptyCutLists(input.sampleCount());
        double[] seedY = feasibleSeed(input.params);
        double seedEta = input.lipschitzBound();
        OracleEvaluation seed = evaluate(input, seedY, seedEta, config);
        addNewCuts(cuts, seed.cuts);

        long start = System.nanoTime();
        double bestUpper = input.radius * seedEta + seed.weightedValue;
        double[] bestY = seedY.clone();
        double bestEta = seedEta;
        double[] centerY = seedY.clone();
        double centerEta = seedEta;
        MasterResult boundMaster = null;
        int iterations = 0;
        int oracleSolves = seed.oracleSolves;
        boolean converged = false;
        double tolerance = Math.max(1e-7, config.tol);

        while (iterations < Math.max(1, config.maxBendersIter)) {
            iterations++;
            long iterationStart = System.nanoTime();
            System.out.printf(java.util.Locale.ROOT,
                    "W1-REG-MULTI iter=%d start cuts=%d bestUB=%.6f totalSec=%.3f%n",
                    iterations, cutCount(cuts), bestUpper, secondsSince(start));

            long boundStart = System.nanoTime();
            boundMaster = solveMaster(input, config, cuts, null);
            double boundSeconds = secondsSince(boundStart);
            double gap = relativeGap(bestUpper, boundMaster.objective);
            if (gap <= tolerance) {
                converged = true;
                System.out.printf(java.util.Locale.ROOT,
                        "W1-REG-MULTI iter=%d certified LB=%.6f bestUB=%.6f gap=%.4f%% boundSec=%.3f totalSec=%.3f%n",
                        iterations, boundMaster.objective, bestUpper, 100.0 * gap,
                        boundSeconds, secondsSince(start));
                break;
            }

            double level = boundMaster.objective
                    + levelFraction * (bestUpper - boundMaster.objective);
            long levelStart = System.nanoTime();
            MasterResult query = solveMaster(input, config, cuts,
                    new Level(level, centerY, centerEta));
            double levelSeconds = secondsSince(levelStart);
            long oracleStart = System.nanoTime();
            OracleEvaluation exact = evaluate(input, query.y, query.eta, config);
            double oracleSeconds = secondsSince(oracleStart);
            oracleSolves += exact.oracleSolves;
            double candidateUpper = input.radius * query.eta + exact.weightedValue;
            if (candidateUpper < bestUpper) {
                bestUpper = candidateUpper;
                bestY = query.y.clone();
                bestEta = query.eta;
            }
            centerY = query.y.clone();
            centerEta = query.eta;
            int added = addNewCuts(cuts, exact.cuts);

            System.out.printf(java.util.Locale.ROOT,
                    "W1-REG-MULTI iter=%d query LB=%.6f bestUB=%.6f gap=%.4f%% level=%.6f eta=%.6f selected=%d added=%d cuts=%d boundSec=%.3f levelSec=%.3f oracleSec=%.3f iterSec=%.3f totalSec=%.3f%n",
                    iterations, boundMaster.objective, bestUpper,
                    100.0 * relativeGap(bestUpper, boundMaster.objective), level,
                    query.eta, selectedCount(query.y), added, cutCount(cuts),
                    boundSeconds, levelSeconds, oracleSeconds,
                    secondsSince(iterationStart), secondsSince(start));

            if (added == 0) {
                OracleEvaluation fallback = evaluate(input, boundMaster.y,
                        boundMaster.eta, config);
                oracleSolves += fallback.oracleSolves;
                double fallbackUpper = input.radius * boundMaster.eta
                        + fallback.weightedValue;
                if (fallbackUpper < bestUpper) {
                    bestUpper = fallbackUpper;
                    bestY = boundMaster.y.clone();
                    bestEta = boundMaster.eta;
                }
                int fallbackAdded = addNewCuts(cuts, fallback.cuts);
                if (fallbackAdded == 0) {
                    if (relativeGap(bestUpper, boundMaster.objective) <= tolerance) {
                        converged = true;
                        break;
                    }
                    throw new IllegalStateException(
                            "Regularized multi-cut stalled with a positive certified gap.");
                }
            }
        }

        if (boundMaster == null) {
            throw new IllegalStateException("Regularized multi-cut produced no master solution.");
        }
        double gap = relativeGap(bestUpper, boundMaster.objective);
        Solution solution = new Solution(bestUpper, bestY, secondsSince(start));
        solution.bestBound = boundMaster.objective;
        solution.relativeGap = gap;
        solution.iterationCount = iterations;
        solution.cutCount = cutCount(cuts);
        solution.candidateCount = oracleSolves;
        solution.certifiedOptimal = converged && gap <= config.tol;
        solution.solverStatus = solution.certifiedOptimal
                ? "OPTIMAL_W1_REGULARIZED_MULTI_CUT"
                : "ITERATION_LIMIT_W1_REGULARIZED_MULTI_CUT";
        return new Result(solution, bestEta, iterations, cutCount(cuts),
                oracleSolves, levelFraction);
    }

    private static MasterResult solveMaster(WassersteinBoxInput input,
                                             Config config,
                                             List<List<SampleCut>> cuts,
                                             Level level) throws Exception {
        IloCplex cplex = new IloCplex();
        try {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, config.threads);
            cplex.setParam(IloCplex.Param.TimeLimit, config.timeLimitSeconds);
            ProcurementParams params = input.params;
            IloNumVar[] y = cplex.boolVarArray(params.I);
            IloNumVar eta = cplex.numVar(0.0, input.lipschitzBound(), "eta");
            IloNumVar[] t = cplex.numVarArray(input.sampleCount(),
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

            IloLinearNumExpr selected = cplex.linearNumExpr();
            for (IloNumVar variable : y) selected.addTerm(1.0, variable);
            cplex.addGe(selected, params.alpha);
            cplex.addLe(selected, params.beta);

            for (int s = 0; s < input.sampleCount(); s++) {
                if (input.probability[s] == 0.0) {
                    cplex.addEq(t[s], 0.0);
                    continue;
                }
                for (SampleCut cut : cuts.get(s)) {
                    IloLinearNumExpr rhs = cplex.linearNumExpr(cut.constant);
                    for (int i = 0; i < params.I; i++) {
                        rhs.addTerm(cut.yCoefficient[i], y[i]);
                    }
                    rhs.addTerm(cut.etaCoefficient, eta);
                    cplex.addGe(t[s], rhs);
                }
            }

            IloLinearNumExpr modelObjective = cplex.linearNumExpr();
            modelObjective.addTerm(input.radius, eta);
            for (int s = 0; s < input.sampleCount(); s++) {
                modelObjective.addTerm(input.probability[s], t[s]);
            }
            if (level == null) {
                cplex.addMinimize(modelObjective);
            } else {
                cplex.addLe(modelObjective, level.value);
                IloQuadNumExpr distance = cplex.quadNumExpr();
                IloLinearNumExpr linear = cplex.linearNumExpr();
                for (int i = 0; i < params.I; i++) {
                    linear.addTerm(1.0 - 2.0 * level.centerY[i], y[i]);
                }
                double etaScale = Math.max(1.0, input.lipschitzBound());
                distance.addTerm(1.0 / (etaScale * etaScale), eta, eta);
                linear.addTerm(-2.0 * level.centerEta
                        / (etaScale * etaScale), eta);
                cplex.addMinimize(cplex.sum(distance, linear));
            }

            if (!cplex.solve() || cplex.getStatus() != IloCplex.Status.Optimal) {
                throw new IllegalStateException(
                        "Regularized multi-cut master failed: " + cplex.getStatus());
            }
            double[] yValue = new double[params.I];
            for (int i = 0; i < params.I; i++) {
                yValue[i] = cplex.getValue(y[i]) > 0.5 ? 1.0 : 0.0;
            }
            return new MasterResult(cplex.getValue(modelObjective),
                    cplex.getValue(eta), yValue);
        } finally {
            cplex.end();
        }
    }

    private static OracleEvaluation evaluate(WassersteinBoxInput input,
                                              double[] y,
                                              double eta,
                                              Config config) throws Exception {
        SampleCut[] cuts = new SampleCut[input.sampleCount()];
        double weightedValue = 0.0;
        int oracleSolves = 0;
        for (int s = 0; s < input.sampleCount(); s++) {
            if (input.probability[s] == 0.0) continue;
            WassersteinBoxOracle.Result oracle = WassersteinBoxOracle.solve(
                    input, s, y, eta, config.threads, config.timeLimitSeconds);
            oracleSolves++;
            weightedValue += input.probability[s] * oracle.value();
            cuts[s] = new SampleCut(oracle.affineConstant(),
                    oracle.yCoefficient(), oracle.etaCoefficient());
        }
        return new OracleEvaluation(weightedValue, cuts, oracleSolves);
    }

    private static List<List<SampleCut>> emptyCutLists(int count) {
        List<List<SampleCut>> lists = new ArrayList<>(count);
        for (int s = 0; s < count; s++) lists.add(new ArrayList<>());
        return lists;
    }

    private static int addNewCuts(List<List<SampleCut>> cuts,
                                  SampleCut[] candidates) {
        int added = 0;
        for (int s = 0; s < candidates.length; s++) {
            SampleCut candidate = candidates[s];
            if (candidate != null && !contains(cuts.get(s), candidate)) {
                cuts.get(s).add(candidate);
                added++;
            }
        }
        return added;
    }

    private static boolean contains(List<SampleCut> cuts, SampleCut candidate) {
        for (SampleCut cut : cuts) {
            if (Math.abs(cut.constant - candidate.constant) > 1e-7
                    || Math.abs(cut.etaCoefficient - candidate.etaCoefficient) > 1e-7) continue;
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

    private static double[] feasibleSeed(ProcurementParams params) {
        double[] y = new double[params.I];
        for (int i = 0; i < params.alpha; i++) y[i] = 1.0;
        return y;
    }

    private static int cutCount(List<List<SampleCut>> cuts) {
        return cuts.stream().mapToInt(List::size).sum();
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static double relativeGap(double upper, double lower) {
        return Math.max(0.0, upper - lower) / Math.max(1.0, Math.abs(upper));
    }

    private static double secondsSince(long start) {
        return (System.nanoTime() - start) / 1.0e9;
    }

    private record SampleCut(double constant, double[] yCoefficient,
                             double etaCoefficient) {
        private SampleCut { yCoefficient = yCoefficient.clone(); }
    }
    private record OracleEvaluation(double weightedValue, SampleCut[] cuts,
                                    int oracleSolves) {
        private OracleEvaluation { cuts = cuts.clone(); }
    }
    private record MasterResult(double objective, double eta, double[] y) {
        private MasterResult { y = y.clone(); }
    }
    private record Level(double value, double[] centerY, double centerEta) {
        private Level { centerY = centerY.clone(); }
    }
    public record Result(Solution solution, double eta, int iterations,
                         int cuts, int oracleSolves, double levelFraction) {}
}
