package Model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import mosek.fusion.AccSolutionStatus;
import mosek.fusion.Domain;
import mosek.fusion.Expr;
import mosek.fusion.Expression;
import mosek.fusion.Model;
import mosek.fusion.ObjectiveSense;
import mosek.fusion.ProblemStatus;
import mosek.fusion.SolutionError;
import mosek.fusion.SolutionStatus;
import mosek.fusion.Variable;

final class RCSAALBBDPrimalSearchSolver {

    Solution solve(Data data, Config cfg) throws Exception {
        if (cfg.rcsaaCompactDual)
            throw new IllegalArgumentException("Compact uses LBBD_PRIMAL_EXACT without local branching");
        if (cfg.rcsaaSearchNeighborhoodRadius < 0 || cfg.rcsaaSearchNeighborhoodRadius > 2) {
            throw new IllegalArgumentException(
                    "rcsaaSearchNeighborhoodRadius must be 0, 1, or 2, current="
                            + cfg.rcsaaSearchNeighborhoodRadius);
        }

        ProcurementParams p = data.params;
        List<Sample> samples = data.samples;
        int wSize = samples.size();
        double[] pi = RCSAADecompositionSupport.validateAndNormalizeWeights(samples);
        RCSAADecompositionSupport.SearchState best = new RCSAADecompositionSupport.SearchState();
        Set<String> prunedSignatures = new HashSet<>();
        double globalLowerBound = Double.NEGATIVE_INFINITY;
        long totalNodes = 0L;
        long totalCandidates = 0L;
        int totalCuts = 0;

        long t0 = System.nanoTime();
        try (Master master = new Master(p, samples, pi, cfg)) {
            for (int iter = 1; iter <= cfg.maxBendersIter; iter++) {
                double remainingSeconds = cfg.timeLimitSeconds - secondsBetween(t0, System.nanoTime());
                if (remainingSeconds <= 0.0) {
                    return incompleteSolution(best, globalLowerBound, t0, iter - 1,
                            master.cutCounter, totalNodes, totalCandidates, "TIME_LIMIT");
                }
                long iterStart = System.nanoTime();
                System.out.println(String.format(
                        "RCSAA-LBBD-PRIMAL-SEARCH iter=%d start totalSec=%.3f",
                        iter,
                        secondsBetween(t0, iterStart)));

                Master.Result mr;
                try {
                    mr = master.solve(remainingSeconds);
                } catch (SolverTerminationException ex) {
                    if (ex.nodeCount >= 0L) totalNodes += ex.nodeCount;
                    if (best.bestY != null && master.isRemainingSearchSpaceInfeasible()) {
                        long t1 = System.nanoTime();
                        return optimalSolution(best.bestUpperBound, best.bestUpperBound - cfg.tol,
                                best.bestY, t0, t1,
                                iter, master.cutCounter, totalNodes, totalCandidates);
                    }
                    if (best.bestY != null) {
                        if (Double.isFinite(ex.bestBound)) globalLowerBound = Math.max(globalLowerBound, ex.bestBound);
                        return incompleteSolution(best, Math.min(best.bestUpperBound, globalLowerBound), t0,
                                iter, master.cutCounter, totalNodes, totalCandidates, ex.solverStatus);
                    }
                    throw ex;
                } catch (SolutionError ex) {
                    if (best.bestY != null && master.isRemainingSearchSpaceInfeasible()) {
                        long t1 = System.nanoTime();
                        System.out.println(String.format(
                                "RCSAA-LBBD-PRIMAL-SEARCH stop=infeasible-space bestUB=%.6f totalSec=%.3f",
                                best.bestUpperBound,
                                secondsBetween(t0, t1)));
                        return optimalSolution(best.bestUpperBound, best.bestUpperBound - cfg.tol,
                                best.bestY, t0, t1,
                                iter, master.cutCounter, totalNodes, totalCandidates);
                    }
                    throw ex;
                }
                if (mr.nodeCount >= 0L) totalNodes += mr.nodeCount;
                double iterationLowerBound = mr.bestBound;
                if (Double.isFinite(iterationLowerBound)) {
                    globalLowerBound = Math.max(globalLowerBound, iterationLowerBound);
                }
                long afterMaster = System.nanoTime();
                System.out.println(String.format(
                        "RCSAA-LBBD-PRIMAL-SEARCH iter=%d masterSolved obj=%.6f sel=%d masterSec=%.3f totalSec=%.3f",
                        iter,
                        mr.obj,
                        countSelected(mr.y),
                        secondsBetween(iterStart, afterMaster),
                        secondsBetween(t0, afterMaster)));

                double[] qCenter = new double[wSize];
                for (int w = 0; w < wSize; w++) {
                    RCSAADecompositionSupport.ScenarioCut cut =
                            cfg.rcsaaRepairCuts
                            ? RCSAAUpperReformulation.repair(p, samples.get(w).demand(), mr.y, w, cfg.enforceDemandEquality)
                            : RCSAADecompositionSupport.solveScenarioCut(
                                    p, samples.get(w).demand(), mr.y, w, cfg.enforceDemandEquality);
                    qCenter[w] = cut.qValue;
                    // Global repair planes survive exclusion of their center's neighborhood.
                    if (cfg.rcsaaRepairCuts) master.addRepairCut(cut);
                    if (shouldLogProgress(w + 1, wSize)) {
                        System.out.println(String.format(
                                "RCSAA-LBBD-PRIMAL-SEARCH iter=%d scenarioProgress=%d/%d elapsedIterSec=%.3f",
                                iter,
                                w + 1,
                                wSize,
                                secondsBetween(iterStart, System.nanoTime())));
                    }
                }
                long afterScenario = System.nanoTime();

                boolean ubImproved = false;
                int improvedCount = 0;
                double centerObj = RCSAADecompositionSupport.evaluateObjective(qCenter, pi, cfg.lambda);
                totalCandidates++;
                if (updateBestIfImproved(best, mr.y, centerObj, cfg.tol)) {
                    ubImproved = true;
                    improvedCount++;
                }
                long afterCenterEval = System.nanoTime();
                if (secondsBetween(t0, afterCenterEval) >= cfg.timeLimitSeconds) {
                    return incompleteSolution(best, globalLowerBound, t0, iter,
                            master.cutCounter, totalNodes, totalCandidates, "TIME_LIMIT");
                }

                List<double[]> neighborhood = new ArrayList<>();
                int skippedNeighborhood = 0;
                int evaluatedNeighborhood = 0;
                if (cfg.rcsaaSearchNeighborhoodRadius > 0) {
                    neighborhood = enumerateNeighborhood(mr.y, p.alpha, p.beta, cfg.rcsaaSearchNeighborhoodRadius);
                    for (double[] neighbor : neighborhood) {
                        if (secondsBetween(t0, System.nanoTime()) >= cfg.timeLimitSeconds) {
                            return incompleteSolution(best, globalLowerBound, t0, iter,
                                    master.cutCounter, totalNodes, totalCandidates, "TIME_LIMIT");
                        }
                        if (prunedSignatures.contains(signatureOf(neighbor))) {
                            skippedNeighborhood++;
                            if (shouldLogProgress(skippedNeighborhood + evaluatedNeighborhood, neighborhood.size())) {
                                System.out.println(String.format(
                                        "RCSAA-LBBD-PRIMAL-SEARCH iter=%d neighProgress=%d/%d eval=%d skip=%d elapsedIterSec=%.3f",
                                        iter,
                                        skippedNeighborhood + evaluatedNeighborhood,
                                        neighborhood.size(),
                                        evaluatedNeighborhood,
                                        skippedNeighborhood,
                                        secondsBetween(iterStart, System.nanoTime())));
                            }
                            continue;
                        }
                        double objective = evaluateCandidate(
                                p, samples, pi, cfg.lambda, neighbor, cfg.enforceDemandEquality);
                        evaluatedNeighborhood++;
                        totalCandidates++;
                        if (updateBestIfImproved(best, neighbor, objective, cfg.tol)) {
                            ubImproved = true;
                            improvedCount++;
                        }
                        if (shouldLogProgress(skippedNeighborhood + evaluatedNeighborhood, neighborhood.size())) {
                            System.out.println(String.format(
                                    "RCSAA-LBBD-PRIMAL-SEARCH iter=%d neighProgress=%d/%d eval=%d skip=%d elapsedIterSec=%.3f",
                                    iter,
                                    skippedNeighborhood + evaluatedNeighborhood,
                                    neighborhood.size(),
                                    evaluatedNeighborhood,
                                    skippedNeighborhood,
                                    secondsBetween(iterStart, System.nanoTime())));
                        }
                    }
                }
                long afterNeighborhood = System.nanoTime();

                if (!mr.certifiedOptimal
                        || secondsBetween(t0, afterNeighborhood) >= cfg.timeLimitSeconds) {
                    return incompleteSolution(best, globalLowerBound, t0, iter,
                            master.cutCounter, totalNodes, totalCandidates, mr.status);
                }

                if (ubImproved) {
                    master.updateObjectiveCutoff(best.bestUpperBound, cfg.tol);
                }

                if (best.bestUpperBound < Double.POSITIVE_INFINITY
                        && Double.isFinite(mr.bestBound)
                        && mr.bestBound >= best.bestUpperBound - cfg.tol) {
                    long t1 = System.nanoTime();
                    System.out.println(String.format(
                            "RCSAA-LBBD-PRIMAL-SEARCH iter=%d solvedByBound remainingLB=%.6f bestUB=%.6f gap=%s sel=%d masterSec=%.3f scenarioSec=%.3f centerSec=%.3f neighSec=%.3f neighEval=%d neighSkip=%d totalSec=%.3f",
                            iter,
                            mr.bestBound,
                            best.bestUpperBound,
                            formatGap(mr.bestBound, best.bestUpperBound),
                            countSelected(mr.y),
                            secondsBetween(iterStart, afterMaster),
                            secondsBetween(afterMaster, afterScenario),
                            secondsBetween(afterScenario, afterCenterEval),
                            secondsBetween(afterCenterEval, afterNeighborhood),
                            evaluatedNeighborhood,
                            skippedNeighborhood,
                            secondsBetween(t0, t1)));
                    return optimalSolution(best.bestUpperBound, mr.bestBound, best.bestY, t0, t1,
                            iter, master.cutCounter, totalNodes, totalCandidates);
                }

                master.addNeighborhoodExclusionCut(mr.y, cfg.rcsaaSearchNeighborhoodRadius);
                totalCuts = master.cutCounter;
                prunedSignatures.add(signatureOf(mr.y));
                for (double[] neighbor : neighborhood) {
                    prunedSignatures.add(signatureOf(neighbor));
                }

                System.out.println(String.format(
                        "RCSAA-LBBD-PRIMAL-SEARCH iter=%d remainingLB=%.6f centerObj=%.6f bestUB=%s gap=%s sel=%d masterSec=%.3f scenarioSec=%.3f centerSec=%.3f neighSec=%.3f neighGen=%d neighEval=%d neighSkip=%d ubImproved=%s improveCount=%d totalSec=%.3f",
                        iter,
                        mr.obj,
                        centerObj,
                        formatMaybeInfinity(best.bestUpperBound),
                        formatGap(mr.obj, best.bestUpperBound),
                        countSelected(mr.y),
                        secondsBetween(iterStart, afterMaster),
                        secondsBetween(afterMaster, afterScenario),
                        secondsBetween(afterScenario, afterCenterEval),
                        secondsBetween(afterCenterEval, afterNeighborhood),
                        neighborhood.size(),
                        evaluatedNeighborhood,
                        skippedNeighborhood,
                        String.valueOf(ubImproved),
                        improvedCount,
                        secondsBetween(t0, System.nanoTime())));
            }
        }

        if (best.bestY != null) {
            long t1 = System.nanoTime();
            System.out.println(String.format(
                    "RCSAA-LBBD-PRIMAL-SEARCH stop=maxIter bestUB=%.6f totalSec=%.3f",
                    best.bestUpperBound,
                    secondsBetween(t0, t1)));
            return incompleteSolution(best, globalLowerBound, t0, cfg.maxBendersIter,
                    totalCuts, totalNodes, totalCandidates, "MAX_ITERATIONS");
        }
        throw new IllegalStateException("RCSAA primal search solver hit max iterations: " + cfg.maxBendersIter);
    }

    private static Solution optimalSolution(double objective,
                                             double lowerBound,
                                             double[] y,
                                            long start,
                                            long end,
                                            int iterations,
                                            int cuts,
                                            long nodes,
                                            long candidates) {
        Solution solution = new Solution(objective, y, secondsBetween(start, end));
        solution.solverStatus = "OPTIMAL";
        solution.bestBound = Double.isFinite(lowerBound)
                ? Math.min(objective, lowerBound) : Double.NaN;
        solution.relativeGap = relativeGap(solution.bestBound, objective);
        solution.nodeCount = nodes;
        solution.iterationCount = iterations;
        solution.cutCount = cuts;
        solution.candidateCount = candidates;
        solution.certifiedOptimal = true;
        return solution;
    }

    private static Solution incompleteSolution(RCSAADecompositionSupport.SearchState best,
                                               double lowerBound,
                                               long start,
                                               int iterations,
                                               int cuts,
                                               long nodes,
                                               long candidates,
                                               String status) {
        if (best.bestY == null || !Double.isFinite(best.bestUpperBound)) {
            throw new IllegalStateException(
                    "RCSAA primal search stopped without a feasible incumbent. status=" + status);
        }
        Solution solution = new Solution(best.bestUpperBound, best.bestY,
                secondsBetween(start, System.nanoTime()));
        solution.solverStatus = "INCOMPLETE:" + status;
        solution.bestBound = Double.isFinite(lowerBound) ? lowerBound : Double.NaN;
        solution.relativeGap = relativeGap(lowerBound, best.bestUpperBound);
        solution.nodeCount = nodes;
        solution.iterationCount = iterations;
        solution.cutCount = cuts;
        solution.candidateCount = candidates;
        solution.certifiedOptimal = false;
        return solution;
    }

    private static double relativeGap(double lowerBound, double upperBound) {
        if (!Double.isFinite(lowerBound) || !Double.isFinite(upperBound)) return Double.NaN;
        return Math.max(0.0, upperBound - lowerBound) / Math.max(1e-12, Math.abs(upperBound));
    }

    private boolean updateBestIfImproved(RCSAADecompositionSupport.SearchState best,
                                         double[] y,
                                         double objective,
                                         double tol) {
        if (objective + tol < best.bestUpperBound) {
            best.bestUpperBound = objective;
            best.bestY = y.clone();
            return true;
        }
        return false;
    }

    private double evaluateCandidate(ProcurementParams p,
                                     List<Sample> samples,
                                     double[] pi,
                                     double lambda,
                                     double[] y,
                                     boolean enforceDemandEquality) throws Exception {
        double[] qTrue = new double[samples.size()];
        for (int w = 0; w < samples.size(); w++) {
            RCSAADecompositionSupport.ScenarioCut cut =
                    RCSAADecompositionSupport.solveScenarioCut(
                            p, samples.get(w).demand(), y, w, enforceDemandEquality);
            qTrue[w] = cut.qValue;
        }
        return RCSAADecompositionSupport.evaluateObjective(qTrue, pi, lambda);
    }

    private List<double[]> enumerateNeighborhood(double[] centerY, int alpha, int beta, int maxDistance) {
        List<double[]> neighbors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int currentSelected = countSelected(centerY);

        if (maxDistance >= 1) {
            for (int i = 0; i < centerY.length; i++) {
                double[] distanceOne = centerY.clone();
                distanceOne[i] = 1.0 - distanceOne[i];
                int selected = currentSelected + (centerY[i] > 0.5 ? -1 : 1);
                if (selected >= alpha && selected <= beta) {
                    addUniqueNeighbor(neighbors, seen, distanceOne);
                }
            }
        }

        if (maxDistance >= 2) {
            for (int i = 0; i < centerY.length; i++) {
                for (int j = i + 1; j < centerY.length; j++) {
                    double[] distanceTwo = centerY.clone();
                    distanceTwo[i] = 1.0 - distanceTwo[i];
                    distanceTwo[j] = 1.0 - distanceTwo[j];
                    int selected = currentSelected;
                    selected += (centerY[i] > 0.5 ? -1 : 1);
                    selected += (centerY[j] > 0.5 ? -1 : 1);
                    if (selected >= alpha && selected <= beta) {
                        addUniqueNeighbor(neighbors, seen, distanceTwo);
                    }
                }
            }
        }
        return neighbors;
    }

    private static void addUniqueNeighbor(List<double[]> neighbors, Set<String> seen, double[] y) {
        String signature = signatureOf(y);
        if (seen.add(signature)) {
            neighbors.add(y);
        }
    }

    private static int countSelected(double[] y) {
        int selected = 0;
        for (double v : y) {
            if (v > 0.5) {
                selected++;
            }
        }
        return selected;
    }

    private static String signatureOf(double[] y) {
        StringBuilder sb = new StringBuilder(y.length);
        for (double v : y) {
            sb.append(v > 0.5 ? '1' : '0');
        }
        return sb.toString();
    }

    private static double secondsBetween(long start, long end) {
        return (end - start) / 1e9;
    }

    private static String formatMaybeInfinity(double value) {
        if (Double.isFinite(value)) {
            return String.format("%.6f", value);
        }
        return "INF";
    }

    private static String formatGap(double lb, double ub) {
        if (!Double.isFinite(lb) || !Double.isFinite(ub)) {
            return "INF";
        }
        if (Math.abs(ub) <= 1e-9) {
            return String.format("%.6f", Math.abs(ub - lb));
        }
        return String.format("%.4f%%", Math.max(0.0, (ub - lb) / Math.abs(ub)) * 100.0);
    }

    private static boolean shouldLogProgress(int completed, int total) {
        return total > 0 && (completed == total || completed % 10 == 0);
    }

    private static final class Master implements AutoCloseable {
        private final Model model;
        private final Variable y;
        private final Variable z;
        private final Variable mu;
        private final Variable rho;
        private final Variable t;
        private final double lambda;
        private final double tolerance;
        private int cutCounter = 0;

        Master(ProcurementParams p, List<Sample> samples, double[] pi, Config cfg) {
            int I = p.I;
            int J = p.J;
            int W = samples.size();

            this.lambda = cfg.lambda;
            this.tolerance = cfg.tol;
            model = new Model("RCSAA-LBBD-Primal-Search");
            model.acceptedSolutionStatus(AccSolutionStatus.Feasible);
            y = model.variable("y", I, Domain.binary());
            z = model.variable("z", W, Domain.greaterThan(0.0));
            mu = model.variable("mu", 1, Domain.unbounded());
            rho = model.variable("rho", 1, Domain.greaterThan(0.0));
            t = model.variable("t", 1, Domain.unbounded());

            Variable x = model.variable("x", new int[] { I, J, W }, Domain.greaterThan(0.0));
            Variable s = model.variable("s", new int[] { J, W }, Domain.greaterThan(0.0));
            Variable u = model.variable("u", new int[] { I, W }, Domain.greaterThan(0.0));

            Expression sumY = Expr.sum(y);
            model.constraint("minSelected", sumY, Domain.greaterThan(p.alpha));
            model.constraint("maxSelected", sumY, Domain.lessThan(p.beta));
            model.constraint("meanDef", Expr.sub(mu, Expr.dot(pi, z)), Domain.equalsTo(0.0));

            Expression[] cone = new Expression[W + 1];
            cone[0] = rho;
            for (int w = 0; w < W; w++) {
                cone[w + 1] = Expr.mul(Math.sqrt(pi[w]), Expr.sub(z.index(w), t.index(0)));
            }
            model.constraint("socStd", Expr.vstack(cone), Domain.inQCone());

            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    if (p.eligible[i][j]) {
                        continue;
                    }
                    for (int w = 0; w < W; w++) {
                        model.constraint("inelig_" + i + "_" + j + "_" + w, x.index(i, j, w), Domain.equalsTo(0.0));
                    }
                }
            }

            for (int w = 0; w < W; w++) {
                double[] d = samples.get(w).demand();

                for (int j = 0; j < J; j++) {
                    Expression served = Expr.add(
                            Expr.sum(x.slice(new int[] { 0, j, w }, new int[] { I, j + 1, w + 1 })),
                            s.index(j, w));
                    var demandDomain = cfg.enforceDemandEquality
                            ? Domain.equalsTo(d[j])
                            : Domain.greaterThan(d[j]);
                    model.constraint("dem_" + j + "_" + w, served, demandDomain);
                }

                for (int i = 0; i < I; i++) {
                    Expression sumX = Expr.sum(x.slice(new int[] { i, 0, w }, new int[] { i + 1, J, w + 1 }));
                    Expression mqcLow = Expr.sub(Expr.mul(p.p[i], y.index(i)), u.index(i, w));
                    model.constraint("mqcLow_" + i + "_" + w, Expr.sub(sumX, mqcLow), Domain.greaterThan(0.0));

                    Expression totalCap = Expr.mul(p.M[i], y.index(i));
                    model.constraint("capTot_" + i + "_" + w, Expr.sub(sumX, totalCap), Domain.lessThan(0.0));

                    for (int j = 0; j < J; j++) {
                        if (!p.eligible[i][j]) {
                            continue;
                        }
                        Expression laneCap = Expr.mul(p.q[i][j], y.index(i));
                        model.constraint("capLane_" + i + "_" + j + "_" + w,
                                Expr.sub(x.index(i, j, w), laneCap),
                                Domain.lessThan(0.0));
                    }
                }

                model.constraint("zCost_" + w,
                        Expr.sub(z.index(w), scenarioCostExpr(p, I, J, w, x, s, u)),
                        Domain.greaterThan(0.0));
            }

            model.objective(ObjectiveSense.Minimize, Expr.add(mu, Expr.mul(cfg.lambda, rho)));
            model.setSolverParam("numThreads", cfg.threads);
            model.setSolverParam("mioMaxTime", cfg.timeLimitSeconds);
        }

        Result solve(double maxTimeSeconds) throws SolutionError {
            model.setSolverParam("mioMaxTime", Math.max(1e-3, maxTimeSeconds));
            model.solve();
            double relativeGap = solverDoubleInfo("mioObjRelGap");
            double bestBound = solverDoubleInfo("mioObjBound");
            if (solverIntInfo("mioObjBoundDefined") <= 0L) bestBound = Double.NaN;
            if (relativeGap < 0.0) relativeGap = Double.NaN;
            long nodeCount = solverIntInfo("mioNumRelax");
            String status = String.valueOf(model.getProblemStatus());
            SolutionStatus primalStatus = model.getPrimalSolutionStatus();
            if (primalStatus != SolutionStatus.Optimal
                    && primalStatus != SolutionStatus.Feasible) {
                throw new SolverTerminationException(
                        status + "/" + primalStatus, bestBound, relativeGap, nodeCount);
            }
            boolean certifiedOptimal = Double.isFinite(relativeGap) && relativeGap <= tolerance;
            return new Result(levels(y), levels(z), model.primalObjValue(),
                    status, bestBound, relativeGap, nodeCount, certifiedOptimal);
        }

        private double solverDoubleInfo(String key) {
            try {
                return model.getSolverDoubleInfo(key);
            } catch (Throwable ignored) {
                return Double.NaN;
            }
        }

        private long solverIntInfo(String key) {
            try {
                return model.getSolverIntInfo(key);
            } catch (Throwable ignored) {
                return -1L;
            }
        }

        boolean isRemainingSearchSpaceInfeasible() {
            ProblemStatus status = model.getProblemStatus();
            return status == ProblemStatus.PrimalInfeasible
                    || status == ProblemStatus.PrimalAndDualInfeasible
                    || status == ProblemStatus.PrimalInfeasibleOrUnbounded;
        }

        void addNeighborhoodExclusionCut(double[] centerY, int radius) {
            Expression lhs = Expr.constTerm(0.0);
            for (int i = 0; i < centerY.length; i++) {
                if (centerY[i] > 0.5) {
                    lhs = Expr.add(lhs, Expr.sub(Expr.constTerm(1.0), y.index(i)));
                } else {
                    lhs = Expr.add(lhs, y.index(i));
                }
            }
            model.constraint("nogood_" + (cutCounter++), lhs, Domain.greaterThan(radius + 1.0));
        }

        void addRepairCut(RCSAADecompositionSupport.ScenarioCut cut) {
            RCSAAUpperReformulation.addRepair(model, y, z, cut, "repair_" + (cutCounter++));
        }

        void updateObjectiveCutoff(double upperBound, double tol) {
            model.constraint("objCutoff_" + (cutCounter++),
                    Expr.add(mu, Expr.mul(lambda, rho)),
                    Domain.lessThan(upperBound - tol));
        }

        private Expression scenarioCostExpr(ProcurementParams p,
                                            int I,
                                            int J,
                                            int w,
                                            Variable x,
                                            Variable s,
                                            Variable u) {
            List<Expression> exprs = new ArrayList<>();
            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    if (!p.eligible[i][j]) {
                        continue;
                    }
                    exprs.add(Expr.mul(p.r[i][j], x.index(i, j, w)));
                }
            }
            for (int j = 0; j < J; j++) {
                exprs.add(Expr.mul(p.e[j], s.index(j, w)));
            }
            for (int i = 0; i < I; i++) {
                exprs.add(Expr.mul(p.h[i], u.index(i, w)));
            }
            return Expr.add(exprs.toArray(new Expression[0]));
        }

        private double[] levels(Variable var) throws SolutionError {
            double[] level = var.level();
            double[] out = new double[level.length];
            System.arraycopy(level, 0, out, 0, level.length);
            return out;
        }

        @Override
        public void close() {
            model.dispose();
        }

        private static final class Result {
            final double[] y;
            final double[] z;
            final double obj;
            final String status;
            final double bestBound;
            final double relativeGap;
            final long nodeCount;
            final boolean certifiedOptimal;

            Result(double[] y, double[] z, double obj, String status,
                   double bestBound, double relativeGap, long nodeCount,
                   boolean certifiedOptimal) {
                this.y = y;
                this.z = z;
                this.obj = obj;
                this.status = status;
                this.bestBound = bestBound;
                this.relativeGap = relativeGap;
                this.nodeCount = nodeCount;
                this.certifiedOptimal = certifiedOptimal;
            }
        }
    }
}
