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
 * Level-method regularization of the exact aggregate single-cut algorithm.
 *
 * <p>The Benders cuts and the certified lower bound are unchanged.  The
 * regularized master only selects the next oracle query inside a level set,
 * so regularization changes the iteration path, not the W1-DRO model.</p>
 */
public final class ContextualWassersteinBoxRegularizedSingleCutBendersSolver {
    public Result solve(WassersteinBoxInput input,
                        Config config,
                        double levelFraction) throws Exception {
        if (config == null || !config.enforceDemandEquality) {
            throw new IllegalArgumentException("Wasserstein solver requires demand equality.");
        }
        if (!Double.isFinite(levelFraction)
                || !(levelFraction > 0.0 && levelFraction < 1.0)) {
            throw new IllegalArgumentException("levelFraction must be in (0,1).");
        }

        double tolerance = Math.max(1e-7, config.tol);
        List<AggregateCut> cuts = new ArrayList<>();
        double[] seedY = feasibleSeed(input.params);
        OracleAggregate seed = evaluate(input, seedY, input.lipschitzBound(), config);
        cuts.add(seed.cut);

        long start = System.nanoTime();
        double bestUpper = input.radius * input.lipschitzBound() + seed.value;
        double[] bestY = seedY.clone();
        double bestEta = input.lipschitzBound();
        double[] centerY = seedY.clone();
        double centerEta = input.lipschitzBound();
        MasterResult boundMaster = null;
        int iterations = 0;
        int oracleSolves = seed.oracleSolves;
        boolean converged = false;

        while (iterations < Math.max(1, config.maxBendersIter)) {
            iterations++;
            long iterationStart = System.nanoTime();
            System.out.printf(java.util.Locale.ROOT,
                    "W1-REG iter=%d start cuts=%d bestUB=%.6f totalSec=%.3f%n",
                    iterations, cuts.size(), bestUpper, secondsSince(start));
            long boundStart = System.nanoTime();
            boundMaster = solveBoundMaster(input, config, cuts);
            double boundSeconds = (System.nanoTime()-boundStart)/1.0e9;
            double relativeGap = relativeGap(bestUpper, boundMaster.objective);
            if (relativeGap <= tolerance) {
                System.out.printf(java.util.Locale.ROOT,
                        "W1-REG iter=%d certified LB=%.6f bestUB=%.6f gap=%.4f%% boundSec=%.3f totalSec=%.3f%n",
                        iterations,boundMaster.objective,bestUpper,100.0*relativeGap,boundSeconds,secondsSince(start));
                converged = true;
                break;
            }

            double level = boundMaster.objective
                    + levelFraction * (bestUpper - boundMaster.objective);
            long levelStart=System.nanoTime();
            MasterResult query = solveLevelMaster(input, config, cuts,
                    level, centerY, centerEta);
            double levelSeconds=(System.nanoTime()-levelStart)/1.0e9;
            long oracleStart=System.nanoTime();
            OracleAggregate exact = evaluate(input, query.y, query.eta, config);
            double oracleSeconds=(System.nanoTime()-oracleStart)/1.0e9;
            oracleSolves += exact.oracleSolves;
            double candidateUpper = input.radius * query.eta + exact.value;
            if (candidateUpper < bestUpper) {
                bestUpper = candidateUpper;
                bestY = query.y.clone();
                bestEta = query.eta;
            }
            centerY = query.y.clone();
            centerEta = query.eta;

            System.out.printf(java.util.Locale.ROOT,
                    "W1-REG iter=%d query LB=%.6f bestUB=%.6f gap=%.4f%% level=%.6f eta=%.6f selected=%d cuts=%d boundSec=%.3f levelSec=%.3f oracleSec=%.3f iterSec=%.3f totalSec=%.3f%n",
                    iterations,boundMaster.objective,bestUpper,
                    100.0*relativeGap(bestUpper,boundMaster.objective),level,query.eta,
                    selectedCount(query.y),cuts.size(),boundSeconds,levelSeconds,oracleSeconds,
                    (System.nanoTime()-iterationStart)/1.0e9,secondsSince(start));

            if (!contains(cuts, exact.cut)) {
                cuts.add(exact.cut);
                continue;
            }

            // A level point can sit on an old cut while the ordinary master
            // still has a gap.  Querying the bound-master point guarantees
            // progress without changing the level-method lower bound.
            OracleAggregate fallback = evaluate(input, boundMaster.y,
                    boundMaster.eta, config);
            oracleSolves += fallback.oracleSolves;
            double fallbackUpper = input.radius * boundMaster.eta + fallback.value;
            if (fallbackUpper < bestUpper) {
                bestUpper = fallbackUpper;
                bestY = boundMaster.y.clone();
                bestEta = boundMaster.eta;
            }
            if (!contains(cuts, fallback.cut)) {
                cuts.add(fallback.cut);
            } else if (relativeGap(bestUpper, boundMaster.objective) <= tolerance) {
                converged = true;
                break;
            } else {
                throw new IllegalStateException(
                        "Regularized single-cut stalled with a positive certified gap.");
            }
        }

        if (boundMaster == null) {
            throw new IllegalStateException("Regularized single-cut produced no master solution.");
        }
        double relativeGap = relativeGap(bestUpper, boundMaster.objective);
        Solution solution = new Solution(bestUpper, bestY,
                (System.nanoTime() - start) / 1.0e9);
        solution.bestBound = boundMaster.objective;
        solution.relativeGap = relativeGap;
        solution.iterationCount = iterations;
        solution.cutCount = cuts.size();
        solution.candidateCount = oracleSolves;
        solution.certifiedOptimal = converged && relativeGap <= config.tol;
        solution.solverStatus = solution.certifiedOptimal
                ? "OPTIMAL_W1_REGULARIZED_SINGLE_CUT"
                : "ITERATION_LIMIT_W1_REGULARIZED_SINGLE_CUT";
        return new Result(solution, bestEta, iterations, cuts.size(), oracleSolves,
                levelFraction);
    }

    private static double secondsSince(long start) { return (System.nanoTime()-start)/1.0e9; }
    private static int selectedCount(double[] y) { int n=0; for(double v:y)if(v>0.5)n++; return n; }

    private static MasterResult solveBoundMaster(WassersteinBoxInput input,
                                                   Config config,
                                                   List<AggregateCut> cuts) throws Exception {
        return solveMaster(input, config, cuts, null);
    }

    private static MasterResult solveLevelMaster(WassersteinBoxInput input,
                                                   Config config,
                                                   List<AggregateCut> cuts,
                                                   double level,
                                                   double[] centerY,
                                                   double centerEta) throws Exception {
        return solveMaster(input, config, cuts,
                new Level(level, centerY, centerEta));
    }

    private static MasterResult solveMaster(WassersteinBoxInput input,
                                             Config config,
                                             List<AggregateCut> cuts,
                                             Level level) throws Exception {
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

            IloLinearNumExpr modelObjective = cplex.linearNumExpr();
            modelObjective.addTerm(input.radius, eta);
            modelObjective.addTerm(1.0, beta);
            if (level == null) {
                cplex.addMinimize(modelObjective);
            } else {
                cplex.addLe(modelObjective, level.value);
                IloQuadNumExpr distance = cplex.quadNumExpr();
                IloLinearNumExpr distanceLinear = cplex.linearNumExpr();
                for (int i = 0; i < params.I; i++) {
                    // y_i^2=y_i because y is binary; constants are irrelevant.
                    distanceLinear.addTerm(1.0 - 2.0 * level.centerY[i], y[i]);
                }
                double etaScale = Math.max(1.0, input.lipschitzBound());
                distance.addTerm(1.0 / (etaScale * etaScale), eta, eta);
                distanceLinear.addTerm(
                        -2.0 * level.centerEta / (etaScale * etaScale), eta);
                cplex.addMinimize(cplex.sum(distance, distanceLinear));
            }

            if (!cplex.solve() || cplex.getStatus() != IloCplex.Status.Optimal) {
                throw new IllegalStateException("Regularized single-cut master failed: "
                        + cplex.getStatus());
            }
            double[] yValue = new double[params.I];
            for (int i = 0; i < params.I; i++) {
                yValue[i] = cplex.getValue(y[i]) > 0.5 ? 1.0 : 0.0;
            }
            return new MasterResult(cplex.getValue(modelObjective),
                    cplex.getValue(eta), cplex.getValue(beta), yValue);
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
        int oracleSolves = 0;
        for (int s = 0; s < input.sampleCount(); s++) {
            if (input.probability[s] == 0.0) continue;
            WassersteinBoxOracle.Result oracle = WassersteinBoxOracle.solve(
                    input, s, y, eta, config.threads, config.timeLimitSeconds);
            oracleSolves++;
            double probability = input.probability[s];
            value += probability * oracle.value();
            constant += probability * oracle.affineConstant();
            etaCoefficient += probability * oracle.etaCoefficient();
            for (int i = 0; i < yCoefficient.length; i++) {
                yCoefficient[i] += probability * oracle.yCoefficient()[i];
            }
        }
        return new OracleAggregate(value,
                new AggregateCut(constant, yCoefficient, etaCoefficient), oracleSolves);
    }

    private static double[] feasibleSeed(ProcurementParams params) {
        double[] y = new double[params.I];
        for (int i = 0; i < params.alpha; i++) y[i] = 1.0;
        return y;
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

    private static double relativeGap(double upper, double lower) {
        return Math.max(0.0, upper - lower) / Math.max(1.0, Math.abs(upper));
    }

    private record AggregateCut(double constant,
                                double[] yCoefficient,
                                double etaCoefficient) {
        private AggregateCut {
            yCoefficient = yCoefficient.clone();
        }
    }

    private record OracleAggregate(double value,
                                   AggregateCut cut,
                                   int oracleSolves) {
    }

    private record MasterResult(double objective,
                                double eta,
                                double beta,
                                double[] y) {
        private MasterResult {
            y = y.clone();
        }
    }

    private record Level(double value, double[] centerY, double centerEta) {
        private Level {
            centerY = centerY.clone();
        }
    }

    public record Result(Solution solution,
                         double eta,
                         int iterations,
                         int cuts,
                         int oracleSolves,
                         double levelFraction) {
    }
}
