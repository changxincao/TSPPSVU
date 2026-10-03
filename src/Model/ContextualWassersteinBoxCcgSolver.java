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
        double bestLower = Double.NaN;
        double[] bestY = null;
        double bestEta = Double.NaN;
        WorstDemandSnapshot bestWorstDemand = null;
        MasterResult master = null;
        int oracleSolves = 0;
        int generatedCuts = 0;
        double optimizerTimeSec = 0.0;
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
                if ((ex instanceof WassersteinBoxOracle.TimeLimitException
                        || remainingSeconds(start, config.timeLimitSeconds) <= 0.0) && bestY != null) {
                    timedOut = true;
                    break;
                }
                throw ex;
            }
            double masterSeconds = (System.nanoTime() - masterStart) / 1.0e9;
            optimizerTimeSec += master.optimizerTimeSec;
            if (Double.isFinite(master.lowerBound) && master.lowerBound <= master.objective
                    + 1e-8 * Math.max(1.0, Math.abs(master.objective)))
                bestLower = Double.isFinite(bestLower) ? Math.max(bestLower, master.lowerBound) : master.lowerBound;
            else System.err.printf(java.util.Locale.ROOT,
                    "W1-CCG invalid/unavailable master bound: incumbent=%.17g lowerBound=%.17g%n",
                    master.objective, master.lowerBound);
            // Even an incomplete oracle pass must not discard a feasible master y.
            // All-spot recourse gives a valid (possibly loose) robust upper bound.
            double fallback = allSpotUpperBound(input, master.y);
            if (fallback < bestUpper) {
                bestUpper = fallback;
                bestY = master.y.clone();
                bestEta = 0.0;
                bestWorstDemand = null; // no claimed worst-demand diagnostic without separation
            }
            System.out.printf(java.util.Locale.ROOT,
                    "W1-CCG iter=%d masterSolved LB=%.6f masterIncumbent=%.6f eta=%.6f selected=%d masterSec=%.3f totalSec=%.3f%n",
                    iterations, bestLower, master.objective, master.eta, selectedCount(master.y),
                    masterSeconds, secondsSince(start));
            double candidateUpper = input.radius * master.eta;
            boolean added = false;
            int addedThisIteration = 0;
            int positiveProcessed = 0;
            int positiveTotal = positiveSampleCount(input);
            long oracleStart = System.nanoTime();
            boolean completedOraclePass = true;
            WorstDemandAccumulator worstDemand = new WorstDemandAccumulator(input);

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
                            config.threads, remaining, true);
                } catch (IllegalStateException ex) {
                    if ((ex instanceof WassersteinBoxOracle.TimeLimitException
                            || remainingSeconds(start, config.timeLimitSeconds) <= 0.0) && bestY != null) {
                        timedOut = true;
                        completedOraclePass = false;
                        break;
                    }
                    throw ex;
                }
                oracleSolves++;
                optimizerTimeSec += oracle.optimizerTimeSec();
                // This is a maximization oracle: its incumbent is NOT an upper bound.
                double oracleUpper = oracle.upperBound();
                if (!Double.isFinite(oracleUpper) || oracleUpper < oracle.value()
                        - 1e-8 * Math.max(1.0, Math.abs(oracle.value()))) {
                    System.err.printf(java.util.Locale.ROOT,
                            "W1-CCG invalid/unavailable oracle bound: sample=%d incumbent=%.17g upperBound=%.17g%n",
                            s, oracle.value(), oracleUpper);
                    oracleUpper = Double.POSITIVE_INFINITY;
                }
                candidateUpper += input.probability[s] * oracleUpper;
                worstDemand.add(s, oracle.worstDemand());
                double violationTolerance = tolerance * Math.max(1.0, Math.abs(oracle.value()));
                if (oracle.value() > master.t[s] + violationTolerance) {
                    if (contains(points.get(s), oracle.worstDemand())) {
                        throw new IllegalStateException(
                                "CCG repeated a violated support point at sample " + s);
                    }
                    points.get(s).add(oracle.worstDemand());
                    added = true;
                    addedThisIteration++;
                    generatedCuts++;
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

            if (candidateUpper < bestUpper) {
                bestUpper = candidateUpper;
                bestY = master.y.clone();
                bestEta = master.eta;
                bestWorstDemand = worstDemand.snapshot();
                System.out.printf(java.util.Locale.ROOT,
                        "W1-CCG incumbentUpdate iter=%d UB=%.6f eta=%.6f y=%s%n",
                        iterations, bestUpper, bestEta, java.util.Arrays.toString(bestY));
            }
            double currentGap = certifiedGap(bestUpper, bestLower);
            System.out.printf(java.util.Locale.ROOT,
                    "W1-CCG iter=%d done LB=%.6f bestUB=%.6f gap=%.4f%% added=%d points=%d totalSec=%.3f%n",
                    iterations, bestLower, bestUpper, 100.0 * currentGap,
                    addedThisIteration, points.stream().mapToInt(List::size).sum(), secondsSince(start));
            if (!added && Double.isFinite(currentGap) && currentGap <= tolerance) {
                converged = true;
                break;
            }
            if (!master.optimal) { timedOut = true; break; }
        }

        if (master == null || bestY == null) {
            throw new IllegalStateException("Wasserstein CCG did not produce an incumbent.");
        }
        double relativeGap = certifiedGap(bestUpper, bestLower);
        int initialPointCount = input.sampleCount();
        int totalPointCount = points.stream().mapToInt(List::size).sum();
        Solution solution = new Solution(bestUpper, bestY,
                (System.nanoTime() - start) / 1.0e9);
        solution.bestBound = bestLower;
        solution.relativeGap = relativeGap;
        solution.iterationCount = iterations;
        solution.cutCount = generatedCuts;
        solution.candidateCount = oracleSolves;
        solution.optimizerTimeSec = optimizerTimeSec;
        solution.wassersteinRadius = input.radius;
        solution.wassersteinEta = bestEta;
        solution.wassersteinInitialPointCount = initialPointCount;
        solution.wassersteinGeneratedCutCount = generatedCuts;
        solution.wassersteinTotalPointCount = totalPointCount;
        solution.wassersteinBoxLower = input.lower.clone();
        solution.wassersteinBoxUpper = input.upper.clone();
        solution.wassersteinDistanceScale = input.scale.clone();
        if (bestWorstDemand != null) {
        solution.wassersteinWorstMeanDistance = bestWorstDemand.meanDistance();
        solution.wassersteinWorstMeanNominalDemand = bestWorstDemand.meanNominalDemand();
        solution.wassersteinWorstMeanDemand = bestWorstDemand.meanWorstDemand();
        solution.wassersteinWorstMeanNominalTotalDemand = bestWorstDemand.meanNominalTotalDemand();
        solution.wassersteinWorstMeanTotalDemand = bestWorstDemand.meanWorstTotalDemand();
        solution.wassersteinWorstMeanMovedLaneCount = bestWorstDemand.meanMovedLaneCount();
        solution.wassersteinWorstMaxMovedLaneCount = bestWorstDemand.maxMovedLaneCount();
        solution.wassersteinWorstLowerMoveProbability = bestWorstDemand.lowerMoveProbability();
        solution.wassersteinWorstUpperMoveProbability = bestWorstDemand.upperMoveProbability();
        }
        solution.certifiedOptimal = converged && relativeGap <= config.tol;
        solution.solverStatus = solution.certifiedOptimal ? "OPTIMAL_W1_CCG"
                : timedOut ? "TIME_LIMIT_W1_CCG" : "ITERATION_LIMIT_W1_CCG";
        if (!Double.isFinite(relativeGap)) solution.solverStatus += "_BOUND_UNAVAILABLE_OR_INCONSISTENT";
        if (bestWorstDemand == null) solution.solverStatus += "_ANALYTIC_UPPER_BOUND";
        System.out.printf(java.util.Locale.ROOT,
                "W1-CCG summary radius=%.17g eta=%.17g initialPoints=%d generatedCuts=%d totalPoints=%d oracleSolves=%d optimizerSec=%.6f boxLower=%s boxUpper=%s distanceScale=%s meanWorstDistance=%.17g meanNominalDemand=%s meanWorstDemand=%s meanMovedLanes=%.17g maxMovedLanes=%d meanNominalTotal=%.17g meanWorstTotal=%.17g lowerMoveProbability=%s upperMoveProbability=%s%n",
                input.radius, bestEta, initialPointCount, generatedCuts, totalPointCount,
                oracleSolves, optimizerTimeSec, java.util.Arrays.toString(input.lower),
                java.util.Arrays.toString(input.upper), java.util.Arrays.toString(input.scale),
                solution.wassersteinWorstMeanDistance,
                java.util.Arrays.toString(solution.wassersteinWorstMeanNominalDemand),
                java.util.Arrays.toString(solution.wassersteinWorstMeanDemand),
                solution.wassersteinWorstMeanMovedLaneCount,
                solution.wassersteinWorstMaxMovedLaneCount, solution.wassersteinWorstMeanNominalTotalDemand,
                solution.wassersteinWorstMeanTotalDemand,
                java.util.Arrays.toString(solution.wassersteinWorstLowerMoveProbability),
                java.util.Arrays.toString(solution.wassersteinWorstUpperMoveProbability));
        return new Result(solution, bestEta, iterations, initialPointCount,
                generatedCuts, totalPointCount, oracleSolves);
    }

    private static int positiveSampleCount(WassersteinBoxInput input) {
        int count = 0;
        for (double probability : input.probability) if (probability > 0.0) count++;
        return count;
    }

    static double certifiedGap(double upper, double lower) {
        if (!Double.isFinite(upper) || !Double.isFinite(lower)
                || lower > upper + 1e-8 * Math.max(1.0, Math.abs(upper))) return Double.NaN;
        return Math.max(0.0, upper - lower) / Math.max(1.0, Math.abs(upper));
    }

    static double allSpotUpperBound(WassersteinBoxInput input, double[] y) {
        double bound = 0.0;
        for (int j = 0; j < input.params.J; j++) bound += input.params.e[j] * input.upper[j];
        for (int i = 0; i < input.params.I; i++) bound += input.params.h[i] * input.params.p[i] * y[i];
        return bound;
    }

    private static final class WorstDemandAccumulator {
        private final WassersteinBoxInput input;
        private final double[] lowerMoveProbability;
        private final double[] upperMoveProbability;
        private final double[] meanNominalDemand;
        private final double[] meanWorstDemand;
        private double meanDistance;
        private double meanNominalTotalDemand;
        private double meanWorstTotalDemand;
        private double meanMovedLaneCount;
        private int maxMovedLaneCount;

        private WorstDemandAccumulator(WassersteinBoxInput input) {
            this.input = input;
            this.lowerMoveProbability = new double[input.params.J];
            this.upperMoveProbability = new double[input.params.J];
            this.meanNominalDemand = new double[input.params.J];
            this.meanWorstDemand = new double[input.params.J];
        }

        private void add(int sample, double[] worst) {
            double probability = input.probability[sample];
            double[] nominal = input.demand[sample];
            int moved = 0;
            double nominalTotal = 0.0, worstTotal = 0.0;
            for (int j = 0; j < input.params.J; j++) {
                nominalTotal += nominal[j];
                worstTotal += worst[j];
                meanNominalDemand[j] += probability * nominal[j];
                meanWorstDemand[j] += probability * worst[j];
                double tolerance = 1e-8 * Math.max(1.0, input.upper[j]);
                if (Math.abs(worst[j] - nominal[j]) <= tolerance) continue;
                moved++;
                if (Math.abs(worst[j] - input.lower[j]) <= tolerance)
                    lowerMoveProbability[j] += probability;
                else if (Math.abs(worst[j] - input.upper[j]) <= tolerance)
                    upperMoveProbability[j] += probability;
            }
            meanDistance += probability * input.distance(sample, worst);
            meanNominalTotalDemand += probability * nominalTotal;
            meanWorstTotalDemand += probability * worstTotal;
            meanMovedLaneCount += probability * moved;
            maxMovedLaneCount = Math.max(maxMovedLaneCount, moved);
        }

        private WorstDemandSnapshot snapshot() {
            return new WorstDemandSnapshot(meanDistance, meanNominalTotalDemand,
                    meanWorstTotalDemand, meanMovedLaneCount, maxMovedLaneCount,
                    meanNominalDemand.clone(), meanWorstDemand.clone(),
                    lowerMoveProbability.clone(), upperMoveProbability.clone());
        }
    }

    private record WorstDemandSnapshot(double meanDistance,
                                       double meanNominalTotalDemand,
                                       double meanWorstTotalDemand,
                                       double meanMovedLaneCount,
                                       int maxMovedLaneCount,
                                       double[] meanNominalDemand,
                                       double[] meanWorstDemand,
                                       double[] lowerMoveProbability,
                                       double[] upperMoveProbability) { }

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
        long started = System.nanoTime();
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
            double remaining = remainingSeconds - secondsSince(started);
            if (remaining <= 0.0) throw new WassersteinBoxOracle.TimeLimitException("W1 master budget exhausted during modeling");
            cplex.setParam(IloCplex.Param.TimeLimit, remaining);
            long optimizerStart = System.nanoTime();
            boolean solved = cplex.solve();
            double optimizerTimeSec = (System.nanoTime() - optimizerStart) / 1e9;
            if (!solved) {
                if (String.valueOf(cplex.getCplexStatus()).contains("TimeLim"))
                    throw new WassersteinBoxOracle.TimeLimitException("W1 master timed out without incumbent");
                throw new IllegalStateException("Wasserstein CCG master failed: " + cplex.getStatus());
            }
            double[] yValue = new double[params.I];
            for (int i = 0; i < params.I; i++) {
                yValue[i] = cplex.getValue(y[i]) > 0.5 ? 1.0 : 0.0;
            }
            return new MasterResult(cplex.getObjValue(), cplex.getValue(eta),
                    yValue, cplex.getValues(t), optimizerTimeSec, cplex.getBestObjValue(),
                    cplex.getStatus() == IloCplex.Status.Optimal);
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

    private record MasterResult(double objective, double eta, double[] y, double[] t,
                                double optimizerTimeSec, double lowerBound, boolean optimal) {
        private MasterResult {
            y = y.clone();
            t = t.clone();
        }
    }

    public record Result(Solution solution,
                         double eta,
                         int iterations,
                         int initialPoints,
                         int generatedCuts,
                         int totalPoints,
                         int oracleSolves) {
    }
}
