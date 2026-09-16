package Model;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact primal-block column-and-constraint generation for weighted,
 * box-supported, scaled-L1 1-Wasserstein two-stage procurement.
 */
public final class ContextualWassersteinBoxCcgSolver {
    public Result solve(WassersteinBoxInput input, Config config) throws Exception {
        requireEquality(config);
        List<List<double[]>> points = new ArrayList<>(input.sampleCount());
        for (int s = 0; s < input.sampleCount(); s++) {
            List<double[]> samplePoints = new ArrayList<>();
            samplePoints.add(input.demand[s].clone());
            points.add(samplePoints);
        }

        long start = System.nanoTime();
        double bestUpper = Double.POSITIVE_INFINITY;
        double[] bestY = null;
        double bestEta = Double.NaN;
        MasterResult master = null;
        int oracleSolves = 0;
        int iterations = 0;
        boolean converged = false;
        boolean timedOut = false;
        double tolerance = Math.max(1e-7, config.tol);

        while (iterations < Math.max(1, config.maxBendersIter)) {
            double remaining = remainingSeconds(start, config.timeLimitSeconds);
            if (remaining <= 0.0) {
                timedOut = true;
                break;
            }
            iterations++;
            int pointsBefore = points.stream().mapToInt(List::size).sum();
            double elapsedBefore = secondsSince(start);
            System.out.printf(java.util.Locale.ROOT,
                    "W1-CCG iter=%d start points=%d bestUB=%s totalSec=%.3f%n",
                    iterations, pointsBefore, format(bestUpper), elapsedBefore);
            long masterStart = System.nanoTime();
            try {
                master = solveMaster(input, config, points, remaining);
            } catch (IllegalStateException ex) {
                if (remainingSeconds(start, config.timeLimitSeconds) <= 0.0 && bestY != null) {
                    timedOut = true;
                    break;
                }
                throw ex;
            }
            double masterSeconds = (System.nanoTime() - masterStart) / 1.0e9;
            System.out.printf(java.util.Locale.ROOT,
                    "W1-CCG iter=%d masterSolved LB=%.6f eta=%.6f selected=%d masterSec=%.3f totalSec=%.3f%n",
                    iterations, master.objective, master.eta, selectedCount(master.y),
                    masterSeconds, secondsSince(start));
            double exact = input.radius * master.eta;
            boolean added = false;
            int addedThisIteration = 0;
            int positiveProcessed = 0;
            int positiveTotal = positiveSampleCount(input);
            long oracleStart = System.nanoTime();
            boolean completedOraclePass = true;

            for (int s = 0; s < input.sampleCount(); s++) {
                if (input.probability[s] == 0.0) continue;
                remaining = remainingSeconds(start, config.timeLimitSeconds);
                if (remaining <= 0.0) {
                    timedOut = true;
                    completedOraclePass = false;
                    break;
                }
                WassersteinBoxOracle.Result oracle;
                try {
                    oracle = WassersteinBoxOracle.solve(input, s, master.y, master.eta,
                            config.threads, remaining);
                } catch (IllegalStateException ex) {
                    if (remainingSeconds(start, config.timeLimitSeconds) <= 0.0 && bestY != null) {
                        timedOut = true;
                        completedOraclePass = false;
                        break;
                    }
                    throw ex;
                }
                oracleSolves++;
                exact += input.probability[s] * oracle.value();
                double violationTolerance = tolerance * Math.max(1.0, Math.abs(oracle.value()));
                if (oracle.value() > master.t[s] + violationTolerance) {
                    if (contains(points.get(s), oracle.worstDemand())) {
                        throw new IllegalStateException(
                                "CCG repeated a violated support point at sample " + s);
                    }
                    points.get(s).add(oracle.worstDemand());
                    added = true;
                    addedThisIteration++;
                }
                positiveProcessed++;
                if (positiveProcessed % 10 == 0 || positiveProcessed == positiveTotal) {
                    System.out.printf(java.util.Locale.ROOT,
                            "W1-CCG iter=%d oracleProgress=%d/%d added=%d oracleSec=%.3f totalSec=%.3f%n",
                            iterations, positiveProcessed, positiveTotal, addedThisIteration,
                            (System.nanoTime() - oracleStart) / 1.0e9, secondsSince(start));
                }
            }
            if (!completedOraclePass) break;

            if (exact < bestUpper) {
                bestUpper = exact;
                bestY = master.y.clone();
                bestEta = master.eta;
                System.out.printf(java.util.Locale.ROOT,
                        "W1-CCG incumbentUpdate iter=%d UB=%.6f eta=%.6f y=%s%n",
                        iterations, bestUpper, bestEta, java.util.Arrays.toString(bestY));
            }
            double currentGap = Math.max(0.0, bestUpper - master.objective)
                    / Math.max(1.0, Math.abs(bestUpper));
            System.out.printf(java.util.Locale.ROOT,
                    "W1-CCG iter=%d done LB=%.6f bestUB=%.6f gap=%.4f%% added=%d points=%d totalSec=%.3f%n",
                    iterations, master.objective, bestUpper, 100.0 * currentGap,
                    addedThisIteration, points.stream().mapToInt(List::size).sum(), secondsSince(start));
            double gapTolerance = tolerance * Math.max(1.0, Math.abs(bestUpper));
            if (!added && bestUpper - master.objective <= gapTolerance) {
                converged = true;
                break;
            }
        }

        if (master == null || bestY == null) {
            throw new IllegalStateException("Wasserstein CCG did not produce an incumbent.");
        }
        double relativeGap = Math.max(0.0, bestUpper - master.objective)
                / Math.max(1.0, Math.abs(bestUpper));
        int pointCount = points.stream().mapToInt(List::size).sum();
        Solution solution = new Solution(bestUpper, bestY,
                (System.nanoTime() - start) / 1.0e9);
        solution.bestBound = master.objective;
        solution.relativeGap = relativeGap;
        solution.iterationCount = iterations;
        solution.cutCount = pointCount;
        solution.candidateCount = oracleSolves;
        solution.certifiedOptimal = converged && relativeGap <= config.tol;
        solution.solverStatus = solution.certifiedOptimal ? "OPTIMAL_W1_CCG"
                : timedOut ? "TIME_LIMIT_W1_CCG" : "ITERATION_LIMIT_W1_CCG";
        return new Result(solution, bestEta, iterations, pointCount, oracleSolves);
    }

    private static int positiveSampleCount(WassersteinBoxInput input) {
        int count = 0;
        for (double probability : input.probability) if (probability > 0.0) count++;
        return count;
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static double secondsSince(long start) {
        return (System.nanoTime() - start) / 1.0e9;
    }

    private static double remainingSeconds(long start, int limitSeconds) {
        return limitSeconds - secondsSince(start);
    }

    private static String format(double value) {
        return Double.isFinite(value)
                ? String.format(java.util.Locale.ROOT, "%.6f", value) : "NA";
    }

    private static MasterResult solveMaster(WassersteinBoxInput input,
                                             Config config,
                                             List<List<double[]>> points,
                                             double remainingSeconds) throws Exception {
        ProcurementParams params = input.params;
        IloCplex cplex = new IloCplex();
        try {
            if (config.writeSolverLogToConsole) {
                cplex.setOut(System.out);
                cplex.setWarning(System.err);
            } else cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, config.threads);
            cplex.setParam(IloCplex.Param.TimeLimit, Math.max(1e-3, remainingSeconds));

            IloNumVar[] y = cplex.boolVarArray(params.I);
            IloNumVar eta = cplex.numVar(0.0, input.lipschitzBound(), "eta");
            IloNumVar[] t = cplex.numVarArray(input.sampleCount(),
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

            IloLinearNumExpr selected = cplex.linearNumExpr();
            for (IloNumVar variable : y) selected.addTerm(1.0, variable);
            cplex.addGe(selected, params.alpha);
            cplex.addLe(selected, params.beta);

            for (int s = 0; s < input.sampleCount(); s++) {
                for (int k = 0; k < points.get(s).size(); k++) {
                    addRecourseBlock(cplex, params, y, eta, t[s],
                            points.get(s).get(k), input.distance(s, points.get(s).get(k)),
                            s, k);
                }
            }

            IloLinearNumExpr objective = cplex.linearNumExpr();
            objective.addTerm(input.radius, eta);
            for (int s = 0; s < input.sampleCount(); s++) {
                objective.addTerm(input.probability[s], t[s]);
            }
            cplex.addMinimize(objective);
            if (!cplex.solve() || cplex.getStatus() != IloCplex.Status.Optimal) {
                throw new IllegalStateException("Wasserstein CCG master failed: " + cplex.getStatus());
            }
            double[] yValue = new double[params.I];
            for (int i = 0; i < params.I; i++) {
                yValue[i] = cplex.getValue(y[i]) > 0.5 ? 1.0 : 0.0;
            }
            return new MasterResult(cplex.getObjValue(), cplex.getValue(eta),
                    yValue, cplex.getValues(t));
        } finally {
            cplex.end();
        }
    }

    private static void addRecourseBlock(IloCplex cplex,
                                         ProcurementParams params,
                                         IloNumVar[] y,
                                         IloNumVar eta,
                                         IloNumVar t,
                                         double[] demand,
                                         double distance,
                                         int sample,
                                         int point) throws Exception {
        IloNumVar[][] x = new IloNumVar[params.I][params.J];
        IloNumVar[] spot = cplex.numVarArray(params.J, 0.0, Double.POSITIVE_INFINITY);
        IloNumVar[] shortfall = cplex.numVarArray(params.I, 0.0, Double.POSITIVE_INFINITY);
        for (int i = 0; i < params.I; i++) {
            for (int j = 0; j < params.J; j++) {
                if (params.eligible[i][j]) {
                    x[i][j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY,
                            "x_" + sample + "_" + point + "_" + i + "_" + j);
                }
            }
        }

        for (int j = 0; j < params.J; j++) {
            IloLinearNumExpr flow = cplex.linearNumExpr();
            flow.addTerm(1.0, spot[j]);
            for (int i = 0; i < params.I; i++) {
                if (params.eligible[i][j]) flow.addTerm(1.0, x[i][j]);
            }
            cplex.addEq(flow, demand[j]);
        }
        for (int i = 0; i < params.I; i++) {
            IloLinearNumExpr carrierFlow = cplex.linearNumExpr();
            for (int j = 0; j < params.J; j++) {
                if (!params.eligible[i][j]) continue;
                carrierFlow.addTerm(1.0, x[i][j]);
                cplex.addLe(x[i][j], cplex.prod(params.q[i][j], y[i]));
            }
            IloLinearNumExpr minimum = cplex.linearNumExpr();
            minimum.addTerm(params.p[i], y[i]);
            minimum.addTerm(-1.0, shortfall[i]);
            cplex.addGe(carrierFlow, minimum);
            cplex.addLe(carrierFlow, cplex.prod(params.M[i], y[i]));
        }

        IloLinearNumExpr cost = cplex.linearNumExpr();
        for (int i = 0; i < params.I; i++) {
            cost.addTerm(params.h[i], shortfall[i]);
            for (int j = 0; j < params.J; j++) {
                if (params.eligible[i][j]) cost.addTerm(params.r[i][j], x[i][j]);
            }
        }
        for (int j = 0; j < params.J; j++) cost.addTerm(params.e[j], spot[j]);
        cost.addTerm(-distance, eta);
        cplex.addGe(t, cost);
    }

    private static boolean contains(List<double[]> points, double[] candidate) {
        for (double[] point : points) {
            boolean same = true;
            for (int j = 0; j < point.length; j++) {
                if (Math.abs(point[j] - candidate[j]) > 1e-8) {
                    same = false;
                    break;
                }
            }
            if (same) return true;
        }
        return false;
    }

    private static void requireEquality(Config config) {
        if (config == null || !config.enforceDemandEquality) {
            throw new IllegalArgumentException("Wasserstein solver requires demand equality.");
        }
    }

    private record MasterResult(double objective, double eta, double[] y, double[] t) {
        private MasterResult {
            y = y.clone();
            t = t.clone();
        }
    }

    public record Result(Solution solution,
                         double eta,
                         int iterations,
                         int generatedPoints,
                         int oracleSolves) {
    }
}
