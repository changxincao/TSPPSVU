package Model;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact CCG pilot for a weighted empirical 1-Wasserstein ball with
 * scaled-L1 distance and box-supported nonnegative demand.
 *
 * <p>This class is intentionally separate from the existing chi-square DRO.
 * It implements equality demand and a nonnegative rectangular support.</p>
 */
public final class ContextualWassersteinBoxSolver {
    public Result solve(Data data,
                        Config config,
                        double[] demandUpper,
                        double[] demandScale,
                        double radius) throws Exception {
        validate(data, config, demandUpper, demandScale, radius);
        ProcurementParams params = data.params;
        int sampleCount = data.samples.size();
        double[] weights = normalizedWeights(data.samples);
        double lipschitzBound = 0.0;
        for (int j = 0; j < params.J; j++) {
            double slopeMagnitude = Math.max(params.e[j], -alphaLowerBound(params, j));
            lipschitzBound = Math.max(lipschitzBound, slopeMagnitude * demandScale[j]);
        }

        if (radius == 0.0) {
            Solution csaa = new SAAModel().solve(withWeights(data, weights), config, null);
            csaa.iterationCount = 0;
            csaa.cutCount = 0;
            csaa.candidateCount = 0;
            return new Result(csaa, lipschitzBound, radius, lipschitzBound,
                    0, 0, 0);
        }

        List<List<Cut>> cutsBySample = new ArrayList<>(sampleCount);
        double[] initialY = new SAAModel().solve(withWeights(data, weights), config, null).y;
        for (int w = 0; w < sampleCount; w++) {
            cutsBySample.add(new ArrayList<>());
            Separation separation = separate(params, data.samples.get(w).demand(),
                    demandUpper, demandScale, initialY, lipschitzBound,
                    config.threads, config.timeLimitSeconds);
            double primalValue = SecondStageEvaluator.evaluate(params, initialY,
                    data.samples.get(w).demand(), true).objective;
            double dualTolerance = 1e-7 * Math.max(1.0, Math.abs(primalValue));
            if (Math.abs(separation.value - primalValue) > dualTolerance) {
                throw new IllegalStateException(
                        "Nominal recourse primal/dual mismatch at sample " + w
                                + ": primal=" + primalValue + ", dual=" + separation.value);
            }
            cutsBySample.get(w).add(separation.cut);
        }

        long start = System.nanoTime();
        MasterResult master = null;
        double bestUpperBound = Double.POSITIVE_INFINITY;
        double[] bestY = null;
        double bestEta = Double.NaN;
        int separationSolves = sampleCount;
        int iterations = 0;
        int maximumIterations = Math.max(1, config.maxBendersIter);
        double absoluteTolerance = Math.max(1e-7, config.tol);
        boolean converged = false;

        while (iterations < maximumIterations) {
            iterations++;
            master = solveMaster(params, data.samples, weights, demandUpper,
                    demandScale, radius, lipschitzBound, cutsBySample,
                    config.threads, config.timeLimitSeconds);

            boolean added = false;
            double exactWeightedValue = 0.0;
            for (int w = 0; w < sampleCount; w++) {
                Separation separation = separate(params, data.samples.get(w).demand(),
                        demandUpper, demandScale, master.y, master.eta,
                        config.threads, config.timeLimitSeconds);
                separationSolves++;
                exactWeightedValue += weights[w] * separation.value;
                double tolerance = absoluteTolerance * Math.max(1.0, Math.abs(separation.value));
                if (separation.value > master.t[w] + tolerance) {
                    if (containsEquivalent(cutsBySample.get(w), separation.cut)) {
                        throw new IllegalStateException(
                                "Wasserstein CCG repeated a violated cut at sample " + w
                                        + "; violation=" + (separation.value - master.t[w]));
                    }
                    cutsBySample.get(w).add(separation.cut);
                    added = true;
                }
            }
            double currentUpperBound = radius * master.eta + exactWeightedValue;
            if (currentUpperBound < bestUpperBound) {
                bestUpperBound = currentUpperBound;
                bestY = master.y.clone();
                bestEta = master.eta;
            }
            System.out.printf(java.util.Locale.US,
                    "Wasserstein CCG iteration=%d LB=%.8f UB=%.8f cuts=%d%n",
                    iterations, master.objective, currentUpperBound,
                    cutsBySample.stream().mapToInt(List::size).sum());
            double gapTolerance = absoluteTolerance * Math.max(1.0, Math.abs(bestUpperBound));
            if (!added && bestUpperBound - master.objective <= gapTolerance) {
                converged = true;
                break;
            }
        }

        if (master == null || bestY == null) {
            throw new IllegalStateException("Wasserstein master did not produce an incumbent.");
        }
        double gap = Math.max(0.0, bestUpperBound - master.objective)
                / Math.max(1.0, Math.abs(bestUpperBound));
        boolean certified = converged && gap <= config.tol;
        int cutCount = cutsBySample.stream().mapToInt(List::size).sum();
        Solution solution = new Solution(bestUpperBound, bestY,
                (System.nanoTime() - start) / 1.0e9);
        solution.solverStatus = certified ? "OPTIMAL_CCG" : "ITERATION_LIMIT";
        solution.bestBound = master.objective;
        solution.relativeGap = gap;
        solution.iterationCount = iterations;
        solution.cutCount = cutCount;
        solution.candidateCount = separationSolves;
        solution.certifiedOptimal = certified;
        return new Result(solution, bestEta, radius, lipschitzBound,
                iterations, cutCount, separationSolves);
    }

    private static MasterResult solveMaster(ProcurementParams params,
                                             List<Sample> samples,
                                             double[] weights,
                                             double[] upper,
                                             double[] scale,
                                             double radius,
                                             double etaUpper,
                                             List<List<Cut>> cutsBySample,
                                             int threads,
                                             int timeLimitSeconds) throws Exception {
        IloCplex cplex = new IloCplex();
        try {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, threads);
            cplex.setParam(IloCplex.Param.TimeLimit, timeLimitSeconds);

            IloNumVar[] y = cplex.boolVarArray(params.I);
            IloNumVar eta = cplex.numVar(0.0, etaUpper, "eta");
            IloNumVar[] t = cplex.numVarArray(samples.size(),
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

            IloLinearNumExpr selected = cplex.linearNumExpr();
            for (IloNumVar variable : y) selected.addTerm(1.0, variable);
            cplex.addGe(selected, params.alpha);
            cplex.addLe(selected, params.beta);

            for (int w = 0; w < samples.size(); w++) {
                double[] demand = samples.get(w).demand();
                addNominalRecourseStrengthening(cplex, params, y, t[w], demand, w);
                List<Cut> cuts = cutsBySample.get(w);
                for (int k = 0; k < cuts.size(); k++) {
                    Cut cut = cuts.get(k);
                    double fixedPart = cut.constant;
                    for (int j = 0; j < params.J; j++) {
                        fixedPart += cut.alpha[j] * demand[j];
                    }
                    IloLinearNumExpr rhs = cplex.linearNumExpr(fixedPart);
                    for (int i = 0; i < params.I; i++) rhs.addTerm(cut.yCoefficient[i], y[i]);

                    for (int j = 0; j < params.J; j++) {
                        IloNumVar endpointContribution = cplex.numVar(0.0,
                                Double.POSITIVE_INFINITY, "p_" + w + "_" + k + "_" + j);

                        IloLinearNumExpr moveUpper = cplex.linearNumExpr(
                                (upper[j] - demand[j]) * cut.alpha[j]);
                        moveUpper.addTerm(-(upper[j] - demand[j]) / scale[j], eta);
                        cplex.addGe(endpointContribution, moveUpper);

                        IloLinearNumExpr moveLower = cplex.linearNumExpr(
                                -demand[j] * cut.alpha[j]);
                        moveLower.addTerm(-demand[j] / scale[j], eta);
                        cplex.addGe(endpointContribution, moveLower);
                        rhs.addTerm(1.0, endpointContribution);
                    }
                    cplex.addGe(t[w], rhs);
                }
            }

            IloLinearNumExpr objective = cplex.linearNumExpr();
            objective.addTerm(radius, eta);
            for (int w = 0; w < samples.size(); w++) objective.addTerm(weights[w], t[w]);
            cplex.addMinimize(objective);
            if (!cplex.solve() || cplex.getStatus() != IloCplex.Status.Optimal) {
                throw new IllegalStateException("Wasserstein master failed: " + cplex.getStatus());
            }
            double[] yValue = new double[params.I];
            for (int i = 0; i < params.I; i++) yValue[i] = cplex.getValue(y[i]) > 0.5 ? 1.0 : 0.0;
            double[] tValue = cplex.getValues(t);
            return new MasterResult(cplex.getObjValue(), cplex.getValue(eta), yValue, tValue);
        } finally {
            cplex.end();
        }
    }

    private static void addNominalRecourseStrengthening(IloCplex cplex,
                                                         ProcurementParams params,
                                                         IloNumVar[] y,
                                                         IloNumVar t,
                                                         double[] demand,
                                                         int sample) throws Exception {
        IloNumVar[][] x = new IloNumVar[params.I][params.J];
        IloNumVar[] spot = cplex.numVarArray(params.J, 0.0, Double.POSITIVE_INFINITY);
        IloNumVar[] shortfall = cplex.numVarArray(params.I, 0.0, Double.POSITIVE_INFINITY);
        for (int i = 0; i < params.I; i++) {
            for (int j = 0; j < params.J; j++) {
                if (params.eligible[i][j]) {
                    x[i][j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY,
                            "nom_x_" + sample + "_" + i + "_" + j);
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
        cplex.addGe(t, cost);
    }

    private static Separation separate(ProcurementParams params,
                                       double[] demand,
                                       double[] upper,
                                       double[] scale,
                                       double[] y,
                                       double eta,
                                       int threads,
                                       int timeLimitSeconds) throws Exception {
        IloCplex cplex = new IloCplex();
        try {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, threads);
            cplex.setParam(IloCplex.Param.TimeLimit, timeLimitSeconds);
            int I = params.I;
            int J = params.J;

            IloNumVar[] alpha = new IloNumVar[J];
            IloNumVar[] beta = new IloNumVar[I];
            IloNumVar[] gamma = new IloNumVar[I];
            IloNumVar[][] sigma = new IloNumVar[I][J];
            IloNumVar[] moveLower = cplex.boolVarArray(J);
            IloNumVar[] moveUpper = cplex.boolVarArray(J);
            IloNumVar[] alphaWhenLower = new IloNumVar[J];
            IloNumVar[] alphaWhenUpper = new IloNumVar[J];

            for (int j = 0; j < J; j++) {
                double alphaLower = alphaLowerBound(params, j);
                double alphaUpper = params.e[j];
                alpha[j] = cplex.numVar(alphaLower, alphaUpper, "a_" + j);
                alphaWhenLower[j] = binaryProduct(cplex, alpha[j], moveLower[j],
                        alphaLower, alphaUpper, "al_" + j);
                alphaWhenUpper[j] = binaryProduct(cplex, alpha[j], moveUpper[j],
                        alphaLower, alphaUpper, "au_" + j);
                IloLinearNumExpr endpointChoice = cplex.linearNumExpr();
                endpointChoice.addTerm(1.0, moveLower[j]);
                endpointChoice.addTerm(1.0, moveUpper[j]);
                cplex.addLe(endpointChoice, 1.0);
            }
            for (int i = 0; i < I; i++) {
                beta[i] = cplex.numVar(0.0, params.h[i], "b_" + i);
                double carrierBound = 0.0;
                for (int j = 0; j < J; j++) {
                    if (!params.eligible[i][j]) continue;
                    carrierBound = Math.max(carrierBound,
                            Math.max(0.0, params.e[j] + params.h[i] - params.r[i][j]));
                }
                gamma[i] = cplex.numVar(0.0, carrierBound, "g_" + i);
                for (int j = 0; j < J; j++) {
                    if (!params.eligible[i][j]) continue;
                    double laneBound = Math.max(0.0,
                            params.e[j] + params.h[i] - params.r[i][j]);
                    sigma[i][j] = cplex.numVar(0.0, laneBound, "s_" + i + "_" + j);
                    IloLinearNumExpr lhs = cplex.linearNumExpr();
                    lhs.addTerm(1.0, alpha[j]);
                    lhs.addTerm(1.0, beta[i]);
                    lhs.addTerm(-1.0, gamma[i]);
                    lhs.addTerm(-1.0, sigma[i][j]);
                    cplex.addLe(lhs, params.r[i][j]);
                }
            }

            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int j = 0; j < J; j++) {
                objective.addTerm(demand[j], alpha[j]);
                objective.addTerm(-demand[j], alphaWhenLower[j]);
                objective.addTerm(-demand[j] * eta / scale[j], moveLower[j]);
                objective.addTerm(upper[j] - demand[j], alphaWhenUpper[j]);
                objective.addTerm(-(upper[j] - demand[j]) * eta / scale[j], moveUpper[j]);
            }
            for (int i = 0; i < I; i++) {
                if (y[i] > 0.5) {
                    objective.addTerm(params.p[i], beta[i]);
                    objective.addTerm(-params.M[i], gamma[i]);
                }
                for (int j = 0; j < J; j++) {
                    if (!params.eligible[i][j]) continue;
                    objective.addTerm(-params.q[i][j], sigma[i][j]);
                }
            }
            cplex.addMaximize(objective);
            if (!cplex.solve() || cplex.getStatus() != IloCplex.Status.Optimal) {
                throw new IllegalStateException("Wasserstein separation failed: " + cplex.getStatus());
            }

            double[] alphaValue = cplex.getValues(alpha);
            double[] yCoefficient = new double[I];
            double cutConstant = 0.0;
            for (int i = 0; i < I; i++) {
                yCoefficient[i] = params.p[i] * cplex.getValue(beta[i])
                        - params.M[i] * cplex.getValue(gamma[i]);
                for (int j = 0; j < J; j++) {
                    if (params.eligible[i][j]) {
                        cutConstant -= params.q[i][j] * cplex.getValue(sigma[i][j]);
                    }
                }
            }
            return new Separation(cplex.getObjValue(),
                    new Cut(alphaValue, yCoefficient, cutConstant));
        } finally {
            cplex.end();
        }
    }

    private static boolean containsEquivalent(List<Cut> cuts, Cut candidate) {
        for (Cut cut : cuts) {
            if (same(cut.alpha, candidate.alpha)
                    && same(cut.yCoefficient, candidate.yCoefficient)
                    && Math.abs(cut.constant - candidate.constant) <= 1e-7) return true;
        }
        return false;
    }

    private static IloNumVar binaryProduct(IloCplex cplex,
                                            IloNumVar value,
                                            IloNumVar binary,
                                            double lower,
                                            double upper,
                                            String name) throws Exception {
        IloNumVar product = cplex.numVar(Math.min(0.0, lower),
                Math.max(0.0, upper), name);
        cplex.addLe(product, cplex.prod(upper, binary));
        cplex.addGe(product, cplex.prod(lower, binary));

        IloLinearNumExpr upperLink = cplex.linearNumExpr(-lower);
        upperLink.addTerm(1.0, value);
        upperLink.addTerm(lower, binary);
        cplex.addLe(product, upperLink);

        IloLinearNumExpr lowerLink = cplex.linearNumExpr(-upper);
        lowerLink.addTerm(1.0, value);
        lowerLink.addTerm(upper, binary);
        cplex.addGe(product, lowerLink);
        return product;
    }

    private static double alphaLowerBound(ProcurementParams params, int lane) {
        double lower = 0.0;
        for (int i = 0; i < params.I; i++) {
            if (params.eligible[i][lane]) {
                lower = Math.min(lower, params.r[i][lane] - params.h[i]);
            }
        }
        return lower;
    }

    private static boolean same(double[] left, double[] right) {
        if (left.length != right.length) return false;
        for (int index = 0; index < left.length; index++) {
            if (Math.abs(left[index] - right[index]) > 1e-7) return false;
        }
        return true;
    }

    private static double[] normalizedWeights(List<Sample> samples) {
        double[] weights = new double[samples.size()];
        double sum = 0.0;
        for (int w = 0; w < samples.size(); w++) {
            double weight = samples.get(w).weight;
            if (!Double.isFinite(weight) || weight < 0.0) {
                throw new IllegalArgumentException("Invalid sample weight " + weight);
            }
            weights[w] = weight;
            sum += weights[w];
        }
        if (!(sum > 0.0) || !Double.isFinite(sum)) {
            throw new IllegalArgumentException("Sample weights must have a positive finite sum.");
        }
        for (int w = 0; w < weights.length; w++) weights[w] /= sum;
        return weights;
    }

    private static Data withWeights(Data data, double[] weights) {
        List<Sample> copied = new ArrayList<>(data.samples.size());
        for (int s = 0; s < data.samples.size(); s++) {
            Sample source = data.samples.get(s);
            copied.add(new Sample(source.id, source.period, source.theta, weights[s]));
        }
        return new Data(data.lanes, copied, data.thetaNow, data.params);
    }

    private static void validate(Data data,
                                 Config config,
                                 double[] upper,
                                 double[] scale,
                                 double radius) {
        if (data == null || config == null) throw new IllegalArgumentException("data/config required");
        if (!config.enforceDemandEquality) {
            throw new IllegalArgumentException("Wasserstein pilot requires demand equality.");
        }
        if (!Double.isFinite(radius) || radius < 0.0) {
            throw new IllegalArgumentException("Invalid Wasserstein radius " + radius);
        }
        ProcurementParams params = data.params;
        if (upper.length != params.J || scale.length != params.J) {
            throw new IllegalArgumentException("Box/scale dimension mismatch.");
        }
        for (int j = 0; j < params.J; j++) {
            if (!(upper[j] >= 0.0) || !Double.isFinite(upper[j])
                    || !(scale[j] > 0.0) || !Double.isFinite(scale[j])) {
                throw new IllegalArgumentException("Invalid upper/scale at lane " + j);
            }
        }
        for (int w = 0; w < data.samples.size(); w++) {
            double[] demand = data.samples.get(w).demand();
            for (int j = 0; j < params.J; j++) {
                if (demand[j] < -1e-9 || demand[j] > upper[j] + 1e-9) {
                    throw new IllegalArgumentException(
                            "Training demand outside box at sample/lane " + w + "/" + j);
                }
            }
        }
    }

    private record Cut(double[] alpha, double[] yCoefficient, double constant) {
        private Cut {
            alpha = alpha.clone();
            yCoefficient = yCoefficient.clone();
        }
    }

    private record Separation(double value, Cut cut) {
    }

    private record MasterResult(double objective, double eta, double[] y, double[] t) {
        private MasterResult {
            y = y.clone();
            t = t.clone();
        }
    }

    public static final class Result {
        public final Solution solution;
        public final double eta;
        public final double radius;
        public final double lipschitzBound;
        public final int iterations;
        public final int cuts;
        public final int separationSolves;

        private Result(Solution solution, double eta, double radius,
                       double lipschitzBound, int iterations, int cuts,
                       int separationSolves) {
            this.solution = solution;
            this.eta = eta;
            this.radius = radius;
            this.lipschitzBound = lipschitzBound;
            this.iterations = iterations;
            this.cuts = cuts;
            this.separationSolves = separationSolves;
        }
    }
}
