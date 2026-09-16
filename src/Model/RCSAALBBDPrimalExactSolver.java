package Model;

import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import mosek.fusion.Domain;
import mosek.fusion.AccSolutionStatus;
import mosek.fusion.Expr;
import mosek.fusion.Expression;
import mosek.fusion.Model;
import mosek.fusion.ObjectiveSense;
import mosek.fusion.SolutionError;
import mosek.fusion.SolutionStatus;
import mosek.fusion.Variable;

final class RCSAALBBDPrimalExactSolver {

    Solution solve(Data data, Config cfg) throws Exception {
        if (cfg.rcsaaCompactSwitchedDual)
            throw new IllegalArgumentException("Experimental switched compact is quarantined: I10 bound contradicts independently verified feasible solution; diagnostic fixed-y tests only");
        if (cfg.lambda < 0 || !Double.isFinite(cfg.lambda))
            throw new IllegalArgumentException("RCSAA lambda must be finite and nonnegative");
        if (cfg.rcsaaCompactDual && cfg.rcsaaRepairCuts)
            throw new IllegalArgumentException("Choose repair or compact, not both");
        ProcurementParams p = data.params;
        List<Sample> samples = data.samples;
        int wSize = samples.size();
        double[] pi = RCSAADecompositionSupport.validateAndNormalizeWeights(samples);
        double bestUpperBound = Double.POSITIVE_INFINITY;
        double[] bestY = null;
        double globalLowerBound = Double.NEGATIVE_INFINITY;
        long totalNodes = 0L;
        int totalCuts = 0;

        long t0 = System.nanoTime();
        try (Master master = new Master(p, samples, pi, cfg)) {
            totalCuts = master.cutCounter;
            for (int iter = 1; iter <= cfg.maxBendersIter; iter++) {
                double remainingSeconds = cfg.timeLimitSeconds - secondsBetween(t0, System.nanoTime());
                if (remainingSeconds <= 0.0) {
                    return incompleteSolution(bestUpperBound, bestY, globalLowerBound,
                            secondsBetween(t0, System.nanoTime()), iter - 1,
                            totalCuts, totalNodes, "TIME_LIMIT");
                }
                long iterStart = System.nanoTime();
                System.out.println(String.format(
                        "RCSAA-LBBD-PRIMAL-EXACT iter=%d start totalSec=%.3f",
                        iter,
                        secondsBetween(t0, iterStart)));

                Master.Result mr;
                try {
                    mr = master.solve(remainingSeconds);
                } catch (SolverTerminationException ex) {
                    if (bestY == null) throw ex;
                    if (Double.isFinite(ex.bestBound)) globalLowerBound = Math.max(globalLowerBound, ex.bestBound);
                    if (ex.nodeCount >= 0) totalNodes += ex.nodeCount;
                    return incompleteSolution(bestUpperBound, bestY, Math.min(bestUpperBound, globalLowerBound),
                            secondsBetween(t0, System.nanoTime()), iter,
                            totalCuts, totalNodes, ex.solverStatus);
                }
                if (mr.nodeCount >= 0L) totalNodes += mr.nodeCount;
                double iterationLowerBound = mr.bestBound;
                if (Double.isFinite(iterationLowerBound)) {
                    globalLowerBound = Math.max(globalLowerBound, iterationLowerBound);
                }
                long afterMaster = System.nanoTime();
                System.out.println(String.format(
                        "RCSAA-LBBD-PRIMAL-EXACT iter=%d masterSolved obj=%.6f sel=%d masterSec=%.3f totalSec=%.3f",
                        iter,
                        mr.obj,
                        countSelected(mr.y),
                        secondsBetween(iterStart, afterMaster),
                        secondsBetween(t0, afterMaster)));

                List<UpperCut> upperCuts = new ArrayList<>();
                double[] qTrue = new double[wSize];
                boolean allExact = true;
                for (int w = 0; w < wSize; w++) {
                    RCSAADecompositionSupport.ScenarioCut cut =
                            cfg.rcsaaRepairCuts
                            ? RCSAAUpperReformulation.repair(p, samples.get(w).demand(), mr.y, w, cfg.enforceDemandEquality)
                            : RCSAADecompositionSupport.solveScenarioCut(
                                    p, samples.get(w).demand(), mr.y, w, cfg.enforceDemandEquality);
                    qTrue[w] = cut.qValue;
                    if (mr.z[w] > cut.qValue + cfg.tol) {
                        upperCuts.add(new UpperCut(cut, mr.y));
                        allExact = false;
                    }
                    if (shouldLogProgress(w + 1, wSize)) {
                        System.out.println(String.format(
                                "RCSAA-LBBD-PRIMAL-EXACT iter=%d scenarioProgress=%d/%d elapsedIterSec=%.3f upperCuts=%d",
                                iter,
                                w + 1,
                                wSize,
                                secondsBetween(iterStart, System.nanoTime()),
                                upperCuts.size()));
                    }
                }
                long afterScenario = System.nanoTime();

                double centerObj = RCSAADecompositionSupport.evaluateObjective(qTrue, pi, cfg.lambda);
                if (centerObj < bestUpperBound - cfg.tol) {
                    bestUpperBound = centerObj;
                    bestY = mr.y.clone();
                }

                if (!mr.certifiedOptimal
                        || secondsBetween(t0, System.nanoTime()) >= cfg.timeLimitSeconds) {
                    return incompleteSolution(bestUpperBound, bestY, globalLowerBound,
                            secondsBetween(t0, System.nanoTime()), iter,
                            totalCuts, totalNodes, mr.status);
                }

                // Compact is a single solve, never fall back to no-good cuts.
                // Allow only scaled LP/SOCP feasibility error in the z=Q check.
                if (cfg.rcsaaCompactDual) {
                    double maxError = 0.0;
                    for (int w = 0; w < wSize; w++) maxError = Math.max(maxError,
                            Math.abs(mr.z[w] - qTrue[w]) / Math.max(1.0, Math.abs(qTrue[w])));
                    if (maxError > 1e-6)
                        throw new IllegalStateException("Compact z/Q residual=" + maxError);
                    allExact = true;
                }

                if (allExact) {
                    long t1 = System.nanoTime();
                    double reportedBound = Double.isFinite(mr.bestBound)
                            ? Math.min(bestUpperBound, mr.bestBound) : Double.NaN;
                    double reportedGap = relativeGap(reportedBound, bestUpperBound);
                    System.out.println(String.format(
                            "RCSAA-LBBD-PRIMAL-EXACT iter=%d solved globalLB=%.6f globalUB=%.6f gap=%s sel=%d masterSec=%.3f scenarioSec=%.3f upperCuts=0 totalSec=%.3f",
                            iter,
                            reportedBound,
                            bestUpperBound,
                            formatGap(reportedBound, bestUpperBound),
                            countSelected(bestY),
                            secondsBetween(iterStart, afterMaster),
                            secondsBetween(afterMaster, afterScenario),
                            secondsBetween(t0, t1)));
                    Solution solution = new Solution(bestUpperBound, bestY, secondsBetween(t0, t1));
                    solution.solverStatus = "OPTIMAL";
                    solution.bestBound = reportedBound;
                    solution.relativeGap = reportedGap;
                    solution.nodeCount = totalNodes;
                    solution.iterationCount = iter;
                    solution.cutCount = totalCuts;
                    solution.candidateCount = iter;
                    solution.certifiedOptimal = Double.isFinite(reportedGap)
                            && reportedGap <= cfg.tol + 1e-12;
                    return solution;
                }

                master.addUpperCuts(upperCuts);
                totalCuts += upperCuts.size();
                System.out.println(String.format(
                        "RCSAA-LBBD-PRIMAL-EXACT iter=%d globalLB=%.6f globalUB=%s gap=%s sel=%d masterSec=%.3f scenarioSec=%.3f upperCuts=%d totalSec=%.3f",
                        iter,
                        mr.obj,
                        formatMaybeInfinity(bestUpperBound),
                        formatGap(mr.obj, bestUpperBound),
                        countSelected(mr.y),
                        secondsBetween(iterStart, afterMaster),
                        secondsBetween(afterMaster, afterScenario),
                        upperCuts.size(),
                        secondsBetween(t0, System.nanoTime())));
            }
        }

        throw new IllegalStateException("RCSAA primal exact solver hit max iterations: " + cfg.maxBendersIter);
    }

    private static Solution incompleteSolution(double upperBound,
                                               double[] bestY,
                                               double lowerBound,
                                               double timeSeconds,
                                               int iterations,
                                               int cuts,
                                               long nodes,
                                               String status) {
        if (bestY == null || !Double.isFinite(upperBound)) {
            throw new IllegalStateException(
                    "RCSAA primal exact solver stopped without a feasible incumbent. status=" + status);
        }
        Solution solution = new Solution(upperBound, bestY, timeSeconds);
        solution.solverStatus = "INCOMPLETE:" + status;
        solution.bestBound = Double.isFinite(lowerBound) ? lowerBound : Double.NaN;
        solution.relativeGap = relativeGap(lowerBound, upperBound);
        solution.nodeCount = nodes;
        solution.iterationCount = iterations;
        solution.cutCount = cuts;
        solution.candidateCount = iterations;
        solution.certifiedOptimal = false;
        return solution;
    }

    private static double relativeGap(double lowerBound, double upperBound) {
        if (!Double.isFinite(lowerBound) || !Double.isFinite(upperBound)) return Double.NaN;
        return Math.max(0.0, upperBound - lowerBound) / Math.max(1e-12, Math.abs(upperBound));
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

    private static double secondsBetween(long start, long end) {
        return (end - start) / 1e9;
    }

    private static boolean shouldLogProgress(int completed, int total) {
        return completed == total || completed % 10 == 0;
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

    private static final class UpperCut {
        final RCSAADecompositionSupport.ScenarioCut scenarioCut;
        final double[] incumbentY;

        UpperCut(RCSAADecompositionSupport.ScenarioCut scenarioCut, double[] incumbentY) {
            this.scenarioCut = scenarioCut;
            this.incumbentY = incumbentY.clone();
        }
    }

    private static final class Master implements AutoCloseable {
        private final Model model;
        private final Variable y;
        private final Variable z;
        private final Variable mu;
        private final Variable rho;
        private final Variable t;
        private final double tolerance;
        private final boolean repairCuts;
        private int cutCounter = 0;

        Master(ProcurementParams p, List<Sample> samples, double[] pi, Config cfg) throws Exception {
            int I = p.I;
            int J = p.J;
            int W = samples.size();
            tolerance = cfg.tol;
            repairCuts = cfg.rcsaaRepairCuts;

            model = new Model("RCSAA-LBBD-Primal-Exact");
            if (cfg.writeSolverLogToConsole)
                model.setLogHandler(new java.io.PrintWriter(System.out, true));
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

            if (cfg.rcsaaCompactDual) {
                if (cfg.rcsaaCompactSwitchedDual)
                    RCSAAUpperReformulation.addSwitched(model, y, z, p, samples, cfg.enforceDemandEquality);
                else RCSAAUpperReformulation.addCompact(model, y, z, p, samples, cfg.enforceDemandEquality);
            }
            if (cfg.rcsaaCompactRepairAnchors) {
                if (!cfg.rcsaaCompactDual) throw new IllegalArgumentException("Static anchors require compact");
                double[] all = new double[I];
                java.util.Arrays.fill(all, 1.0);
                for (int w = 0; w < W; w++) {
                    double spot = 0.0;
                    double[] penalties = new double[I];
                    for (int j = 0; j < J; j++) spot += p.e[j] * samples.get(w).demand()[j];
                    for (int i = 0; i < I; i++) penalties[i] = p.h[i] * p.p[i];
                    model.constraint("repairZero_" + w, Expr.sub(z.index(w),
                            Expr.add(spot, Expr.dot(penalties, y))), Domain.lessThan(0.0));
                    RCSAAUpperReformulation.addRepair(model, y, z,
                            RCSAAUpperReformulation.repair(p, samples.get(w).demand(), all, w,
                                    cfg.enforceDemandEquality), "repairAll_" + w);
                    cutCounter += 2;
                }
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

        void addUpperCuts(List<UpperCut> cuts) {
            for (UpperCut cut : cuts) {
                if (repairCuts) {
                    RCSAAUpperReformulation.addRepair(model, y, z, cut.scenarioCut,
                            "repair_" + (cutCounter++));
                    continue;
                }
                int ones = 0;
                for (double v : cut.incumbentY) {
                    if (v > 0.5) {
                        ones++;
                    }
                }
                double mw = cut.scenarioCut.incumbentMw();
                Expression rhs = Expr.constTerm(cut.scenarioCut.constantPart + mw * ones);
                for (int i = 0; i < cut.scenarioCut.yCoeff.length; i++) {
                    double coeff = cut.scenarioCut.yCoeff[i] + (cut.incumbentY[i] > 0.5 ? -mw : mw);
                    if (Math.abs(coeff) > 1e-12) {
                        rhs = Expr.add(rhs, Expr.mul(coeff, y.index(i)));
                    }
                }
                model.constraint("ubCut_" + (cutCounter++),
                        Expr.sub(z.index(cut.scenarioCut.scenarioIndex), rhs),
                        Domain.lessThan(0.0));
            }
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

            Result(double[] y, double[] z, double obj,
                   String status, double bestBound, double relativeGap,
                   long nodeCount, boolean certifiedOptimal) {
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
