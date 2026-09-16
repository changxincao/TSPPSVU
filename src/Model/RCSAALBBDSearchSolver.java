package Model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import mosek.fusion.Domain;
import mosek.fusion.Expr;
import mosek.fusion.Expression;
import mosek.fusion.Model;
import mosek.fusion.ObjectiveSense;
import mosek.fusion.ProblemStatus;
import mosek.fusion.SolutionError;
import mosek.fusion.Variable;

final class RCSAALBBDSearchSolver {

    Solution solve(Data data, Config cfg) throws Exception {
        // 版本二：
        // 只累计 lower cut。对每个已经 lower-tight 的 incumbent，
        // 先评估中心解，再显式评估距离 1/2 的邻域解来改进全局上界，
        // 最后用一条邻域球排除 cut 切掉整个已评估邻域。
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

        long t0 = System.nanoTime();
        try (Master master = new Master(p, pi, cfg)) {
            for (int iter = 1; iter <= cfg.maxBendersIter; iter++) {
                long iterStart = System.nanoTime();
                System.out.println(String.format(
                        "RCSAA-LBBD-SEARCH iter=%d start totalSec=%.3f",
                        iter,
                        secondsBetween(t0, iterStart)));
                Master.Result mr;
                try {
                    mr = master.solve();
                } catch (SolutionError ex) {
                    if (best.bestY != null && master.isRemainingSearchSpaceInfeasible()) {
                        long t1 = System.nanoTime();
                        System.out.println(String.format(
                                "RCSAA-LBBD-SEARCH stop=infeasible-space bestUB=%.6f totalSec=%.3f",
                                best.bestUpperBound,
                                secondsBetween(t0, t1)));
                        return new Solution(best.bestUpperBound, best.bestY, secondsBetween(t0, t1));
                    }
                    throw ex;
                }
                long afterMaster = System.nanoTime();
                System.out.println(String.format(
                        "RCSAA-LBBD-SEARCH iter=%d masterSolved obj=%.6f sel=%d masterSec=%.3f totalSec=%.3f",
                        iter,
                        mr.obj,
                        countSelected(mr.y),
                        secondsBetween(iterStart, afterMaster),
                        secondsBetween(t0, afterMaster)));

                List<RCSAADecompositionSupport.ScenarioCut> lowerCuts = new ArrayList<>();
                double[] qCenter = new double[wSize];
                for (int w = 0; w < wSize; w++) {
                    RCSAADecompositionSupport.ScenarioCut cut =
                            RCSAADecompositionSupport.solveScenarioCut(
                                    p, samples.get(w).demand(), mr.y, w, cfg.enforceDemandEquality);
                    qCenter[w] = cut.qValue;
                    if (mr.z[w] + cfg.tol < cut.qValue) {
                        lowerCuts.add(cut);
                    }
                    if (shouldLogProgress(w + 1, wSize)) {
                        System.out.println(String.format(
                                "RCSAA-LBBD-SEARCH iter=%d scenarioProgress=%d/%d elapsedIterSec=%.3f lowerCuts=%d",
                                iter,
                                w + 1,
                                wSize,
                                secondsBetween(iterStart, System.nanoTime()),
                                lowerCuts.size()));
                    }
                }
                long afterScenario = System.nanoTime();

                if (!lowerCuts.isEmpty()) {
                    master.addLowerCuts(lowerCuts);
                    System.out.println(String.format(
                            "RCSAA-LBBD-SEARCH iter=%d remainingLB=%.6f bestUB=%s gap=%s sel=%d masterSec=%.3f scenarioSec=%.3f lowerCuts=%d totalSec=%.3f",
                            iter,
                            mr.obj,
                            formatMaybeInfinity(best.bestUpperBound),
                            formatGap(mr.obj, best.bestUpperBound),
                            countSelected(mr.y),
                            secondsBetween(iterStart, afterMaster),
                            secondsBetween(afterMaster, afterScenario),
                            lowerCuts.size(),
                            secondsBetween(t0, System.nanoTime())));
                    continue;
                }

                boolean ubImproved = false;
                int improvedCount = 0;
                double centerObj = RCSAADecompositionSupport.evaluateObjective(qCenter, pi, cfg.lambda);
                if (updateBestIfImproved(best, mr.y, centerObj, cfg.tol)) {
                    ubImproved = true;
                    improvedCount++;
                }
                long afterCenterEval = System.nanoTime();

                List<double[]> neighborhood = new ArrayList<>();
                int skippedNeighborhood = 0;
                int evaluatedNeighborhood = 0;
                if (cfg.rcsaaSearchNeighborhoodRadius > 0) {
                    neighborhood = enumerateNeighborhood(mr.y, p.alpha, p.beta, cfg.rcsaaSearchNeighborhoodRadius);
                    for (double[] neighbor : neighborhood) {
                        if (prunedSignatures.contains(signatureOf(neighbor))) {
                            skippedNeighborhood++;
                            if (shouldLogProgress(skippedNeighborhood + evaluatedNeighborhood, neighborhood.size())) {
                                System.out.println(String.format(
                                        "RCSAA-LBBD-SEARCH iter=%d neighProgress=%d/%d eval=%d skip=%d elapsedIterSec=%.3f",
                                        iter,
                                        skippedNeighborhood + evaluatedNeighborhood,
                                        neighborhood.size(),
                                        evaluatedNeighborhood,
                                        skippedNeighborhood,
                                        secondsBetween(iterStart, System.nanoTime())));
                            }
                            continue;
                        }
                        EvaluatedSolution evaluated = evaluateCandidate(
                                p, samples, pi, cfg.lambda, neighbor, cfg.enforceDemandEquality);
                        evaluatedNeighborhood++;
                        if (updateBestIfImproved(best, neighbor, evaluated.objective, cfg.tol)) {
                            ubImproved = true;
                            improvedCount++;
                        }
                        if (shouldLogProgress(skippedNeighborhood + evaluatedNeighborhood, neighborhood.size())) {
                            System.out.println(String.format(
                                    "RCSAA-LBBD-SEARCH iter=%d neighProgress=%d/%d eval=%d skip=%d elapsedIterSec=%.3f",
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

                if (ubImproved) {
                    master.updateObjectiveCutoff(best.bestUpperBound, cfg.tol);
                }

                if (best.bestUpperBound < Double.POSITIVE_INFINITY && mr.obj >= best.bestUpperBound - cfg.tol) {
                    long t1 = System.nanoTime();
                    System.out.println(String.format(
                            "RCSAA-LBBD-SEARCH iter=%d solvedByBound remainingLB=%.6f bestUB=%.6f gap=%s sel=%d masterSec=%.3f scenarioSec=%.3f centerSec=%.3f neighSec=%.3f neighEval=%d neighSkip=%d totalSec=%.3f",
                            iter,
                            mr.obj,
                            best.bestUpperBound,
                            formatGap(mr.obj, best.bestUpperBound),
                            countSelected(mr.y),
                            secondsBetween(iterStart, afterMaster),
                            secondsBetween(afterMaster, afterScenario),
                            secondsBetween(afterScenario, afterCenterEval),
                            secondsBetween(afterCenterEval, afterNeighborhood),
                            evaluatedNeighborhood,
                            skippedNeighborhood,
                            secondsBetween(t0, t1)));
                    return new Solution(best.bestUpperBound, best.bestY, secondsBetween(t0, t1));
                }

                master.addNeighborhoodExclusionCut(mr.y, cfg.rcsaaSearchNeighborhoodRadius);
                prunedSignatures.add(signatureOf(mr.y));
                for (double[] neighbor : neighborhood) {
                    prunedSignatures.add(signatureOf(neighbor));
                }

                System.out.println(String.format(
                        "RCSAA-LBBD-SEARCH iter=%d remainingLB=%.6f centerObj=%.6f bestUB=%s gap=%s sel=%d masterSec=%.3f scenarioSec=%.3f centerSec=%.3f neighSec=%.3f neighGen=%d neighEval=%d neighSkip=%d ubImproved=%s improveCount=%d totalSec=%.3f",
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
                    "RCSAA-LBBD-SEARCH stop=maxIter bestUB=%.6f totalSec=%.3f",
                    best.bestUpperBound,
                    secondsBetween(t0, t1)));
            return new Solution(best.bestUpperBound, best.bestY, secondsBetween(t0, t1));
        }
        throw new IllegalStateException("RCSAA LBBD search solver hit max iterations: " + cfg.maxBendersIter);
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

    private EvaluatedSolution evaluateCandidate(ProcurementParams p,
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
        double objective = RCSAADecompositionSupport.evaluateObjective(qTrue, pi, lambda);
        return new EvaluatedSolution(qTrue, objective);
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
        private int cutCounter = 0;

        Master(ProcurementParams p, double[] pi, Config cfg) {
            this.lambda = cfg.lambda;
            model = new Model("RCSAA-LBBD-Search");
            y = model.variable("y", p.I, Domain.binary());
            z = model.variable("z", pi.length, Domain.greaterThan(0.0));
            mu = model.variable("mu", 1, Domain.unbounded());
            rho = model.variable("rho", 1, Domain.greaterThan(0.0));
            t = model.variable("t", 1, Domain.unbounded());

            Expression sumY = Expr.sum(y);
            model.constraint("minSelected", sumY, Domain.greaterThan(p.alpha));
            model.constraint("maxSelected", sumY, Domain.lessThan(p.beta));
            model.constraint("meanDef", Expr.sub(mu, Expr.dot(pi, z)), Domain.equalsTo(0.0));

            Expression[] cone = new Expression[pi.length + 1];
            cone[0] = rho;
            for (int w = 0; w < pi.length; w++) {
                // rho >= sqrt(sum_w pi_w (z_w - t)^2)
                cone[w + 1] = Expr.mul(Math.sqrt(pi[w]), Expr.sub(z.index(w), t.index(0)));
            }
            model.constraint("socStd", Expr.vstack(cone), Domain.inQCone());
            model.objective(ObjectiveSense.Minimize, Expr.add(mu, Expr.mul(cfg.lambda, rho)));
            model.setSolverParam("numThreads", cfg.threads);
            model.setSolverParam("mioMaxTime", cfg.timeLimitSeconds);
        }

        Result solve() throws SolutionError {
            model.solve();
            return new Result(levels(y), levels(z), model.primalObjValue());
        }

        boolean isRemainingSearchSpaceInfeasible() {
            ProblemStatus status = model.getProblemStatus();
            return status == ProblemStatus.PrimalInfeasible
                    || status == ProblemStatus.PrimalAndDualInfeasible
                    || status == ProblemStatus.PrimalInfeasibleOrUnbounded;
        }

        void addLowerCuts(List<RCSAADecompositionSupport.ScenarioCut> cuts) {
            for (RCSAADecompositionSupport.ScenarioCut cut : cuts) {
                Expression rhs = Expr.constTerm(cut.constantPart);
                for (int i = 0; i < cut.yCoeff.length; i++) {
                    if (Math.abs(cut.yCoeff[i]) > 1e-12) {
                        rhs = Expr.add(rhs, Expr.mul(cut.yCoeff[i], y.index(i)));
                    }
                }
                model.constraint("lbCut_" + (cutCounter++),
                        Expr.sub(z.index(cut.scenarioIndex), rhs),
                        Domain.greaterThan(0.0));
            }
        }

        void addNeighborhoodExclusionCut(double[] centerY, int radius) {
            // Delta(y, y^r) >= delta + 1
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

        void updateObjectiveCutoff(double upperBound, double tol) {
            model.constraint("objCutoff_" + (cutCounter++),
                    Expr.add(mu, Expr.mul(lambda, rho)),
                    Domain.lessThan(upperBound - tol));
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

            Result(double[] y, double[] z, double obj) {
                this.y = y;
                this.z = z;
                this.obj = obj;
            }
        }
    }

    private static final class EvaluatedSolution {
        final double[] qTrue;
        final double objective;

        EvaluatedSolution(double[] qTrue, double objective) {
            this.qTrue = qTrue;
            this.objective = objective;
        }
    }
}
