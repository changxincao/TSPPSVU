package Model;

import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import mosek.fusion.Domain;
import mosek.fusion.Expr;
import mosek.fusion.Expression;
import mosek.fusion.Model;
import mosek.fusion.ObjectiveSense;
import mosek.fusion.SolutionError;
import mosek.fusion.Variable;

final class RCSAALBBDExactSolver {

    Solution solve(Data data, Config cfg) throws Exception {
        ProcurementParams p = data.params;
        List<Sample> samples = data.samples;
        int wSize = samples.size();
        double[] pi = RCSAADecompositionSupport.validateAndNormalizeWeights(samples);
        double bestUpperBound = Double.POSITIVE_INFINITY;

        long t0 = System.nanoTime();
        try (Master master = new Master(p, pi, cfg)) {
            for (int iter = 1; iter <= cfg.maxBendersIter; iter++) {
                long iterStart = System.nanoTime();
                System.out.println(String.format(
                        "RCSAA-LBBD-EXACT iter=%d start totalSec=%.3f",
                        iter,
                        secondsBetween(t0, iterStart)));
                Master.Result mr = master.solve();
                long afterMaster = System.nanoTime();
                System.out.println(String.format(
                        "RCSAA-LBBD-EXACT iter=%d masterSolved obj=%.6f sel=%d masterSec=%.3f totalSec=%.3f",
                        iter,
                        mr.obj,
                        countSelected(mr.y),
                        secondsBetween(iterStart, afterMaster),
                        secondsBetween(t0, afterMaster)));

                RCSAADecompositionSupport.ScenarioCut[] scenarioCuts =
                        new RCSAADecompositionSupport.ScenarioCut[wSize];
                List<RCSAADecompositionSupport.ScenarioCut> lowerCuts = new ArrayList<>();
                List<UpperCut> upperCuts = new ArrayList<>();
                double[] qTrue = new double[wSize];
                boolean allExact = true;

                for (int w = 0; w < wSize; w++) {
                    RCSAADecompositionSupport.ScenarioCut cut =
                            RCSAADecompositionSupport.solveScenarioCut(
                                    p, samples.get(w).demand(), mr.y, w, cfg.enforceDemandEquality);
                    scenarioCuts[w] = cut;
                    qTrue[w] = cut.qValue;
                    if (mr.z[w] + cfg.tol < cut.qValue) {
                        lowerCuts.add(cut);
                        allExact = false;
                    }
                    if (shouldLogProgress(w + 1, wSize)) {
                        System.out.println(String.format(
                                "RCSAA-LBBD-EXACT iter=%d scenarioProgress=%d/%d elapsedIterSec=%.3f lowerCuts=%d",
                                iter,
                                w + 1,
                                wSize,
                                secondsBetween(iterStart, System.nanoTime()),
                                lowerCuts.size()));
                    }
                }
                long afterScenario = System.nanoTime();
                double centerObj = RCSAADecompositionSupport.evaluateObjective(qTrue, pi, cfg.lambda);
                if (centerObj < bestUpperBound - cfg.tol) {
                    bestUpperBound = centerObj;
                }

                if (!lowerCuts.isEmpty()) {
                    master.addLowerCuts(lowerCuts);
                    System.out.println(String.format(
                            "RCSAA-LBBD-EXACT iter=%d globalLB=%.6f globalUB=%s gap=%s sel=%d masterSec=%.3f scenarioSec=%.3f lowerCuts=%d upperCuts=0 totalSec=%.3f",
                            iter,
                            mr.obj,
                            formatMaybeInfinity(bestUpperBound),
                            formatGap(mr.obj, bestUpperBound),
                            countSelected(mr.y),
                            secondsBetween(iterStart, afterMaster),
                            secondsBetween(afterMaster, afterScenario),
                            lowerCuts.size(),
                            secondsBetween(t0, System.nanoTime())));
                    continue;
                }

                for (int w = 0; w < wSize; w++) {
                    RCSAADecompositionSupport.ScenarioCut cut = scenarioCuts[w];
                    if (mr.z[w] > cut.qValue + cfg.tol) {
                        upperCuts.add(new UpperCut(cut, mr.y));
                        allExact = false;
                    }
                }
                long afterUpperCheck = System.nanoTime();

                if (allExact) {
                    long t1 = System.nanoTime();
                    double obj = RCSAADecompositionSupport.evaluateObjective(qTrue, pi, cfg.lambda);
                    System.out.println(String.format(
                            "RCSAA-LBBD-EXACT iter=%d solved globalLB=%.6f globalUB=%.6f gap=%s sel=%d masterSec=%.3f scenarioSec=%.3f upperCheckSec=%.3f totalSec=%.3f",
                            iter,
                            mr.obj,
                            obj,
                            formatGap(mr.obj, obj),
                            countSelected(mr.y),
                            secondsBetween(iterStart, afterMaster),
                            secondsBetween(afterMaster, afterScenario),
                            secondsBetween(afterScenario, afterUpperCheck),
                            secondsBetween(t0, t1)));
                    return new Solution(obj, mr.y, secondsBetween(t0, t1));
                }

                master.addUpperCuts(upperCuts);
                System.out.println(String.format(
                        "RCSAA-LBBD-EXACT iter=%d globalLB=%.6f globalUB=%s gap=%s sel=%d masterSec=%.3f scenarioSec=%.3f lowerCuts=0 upperCuts=%d upperCheckSec=%.3f totalSec=%.3f",
                        iter,
                        mr.obj,
                        formatMaybeInfinity(bestUpperBound),
                        formatGap(mr.obj, bestUpperBound),
                        countSelected(mr.y),
                        secondsBetween(iterStart, afterMaster),
                        secondsBetween(afterMaster, afterScenario),
                        upperCuts.size(),
                        secondsBetween(afterScenario, afterUpperCheck),
                        secondsBetween(t0, System.nanoTime())));
            }
        }
        throw new IllegalStateException("RCSAA LBBD exact solver hit max iterations: " + cfg.maxBendersIter);
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
        private final double[] pi;
        private int cutCounter = 0;

        Master(ProcurementParams p, double[] pi, Config cfg) {
            this.pi = pi;
            model = new Model("RCSAA-LBBD-Exact");
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

        void addLowerCuts(List<RCSAADecompositionSupport.ScenarioCut> cuts) {
            for (RCSAADecompositionSupport.ScenarioCut cut : cuts) {
                Expression rhs = affineExpr(cut.constantPart, cut.yCoeff);
                model.constraint("lbCut_" + (cutCounter++),
                        Expr.sub(z.index(cut.scenarioIndex), rhs),
                        Domain.greaterThan(0.0));
            }
        }

        void addUpperCuts(List<UpperCut> cuts) {
            for (UpperCut cut : cuts) {
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

        private Expression affineExpr(double constant, double[] coeffs) {
            Expression rhs = Expr.constTerm(constant);
            for (int i = 0; i < coeffs.length; i++) {
                if (Math.abs(coeffs[i]) > 1e-12) {
                    rhs = Expr.add(rhs, Expr.mul(coeffs[i], y.index(i)));
                }
            }
            return rhs;
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
}
