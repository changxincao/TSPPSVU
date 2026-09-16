package Model;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import ilog.concert.IloLQNumExpr;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact exchange algorithm for the box-supported partial-moment DRO model.
 *
 * <p>The ambiguity set fixes the demand mean and bounds each centered marginal
 * second moment.  An optional aggregate total-demand variance bound is kept
 * for backward compatibility.  The solver retains unrestricted LP recourse
 * and does not use affine decision rules.</p>
 *
 * <p>Exactness is relative to the stated moment set and numerical tolerances:
 * the master is the generalized-moment dual, while its semi-infinite
 * constraint is separated by a globally solved nonconvex QP.</p>
 */
public final class ExactPartialMomentDROSolver {
    private static final double DUPLICATE_TOLERANCE = 1e-7;

    /**
     * Solves the mean plus componentwise-variance ambiguity set.  No
     * cross-lane covariance or aggregate total-demand variance is imposed.
     */
    public Result solve(ProcurementParams params,
                        double[] mean,
                        double[] marginalVarianceUpper,
                        double[] demandUpper,
                        Config config) throws Exception {
        return solve(params, mean, marginalVarianceUpper, 0.0,
                demandUpper, config, false);
    }

    public Result solve(ProcurementParams params,
                        double[] mean,
                        double[] marginalVarianceUpper,
                        double aggregateVarianceUpper,
                        double[] demandUpper,
                        Config config) throws Exception {
        return solve(params, mean, marginalVarianceUpper,
                aggregateVarianceUpper, demandUpper, config, true);
    }

    private Result solve(ProcurementParams params,
                         double[] mean,
                         double[] marginalVarianceUpper,
                         double aggregateVarianceUpper,
                         double[] demandUpper,
                         Config config,
                         boolean useAggregateVariance) throws Exception {
        validate(params, mean, marginalVarianceUpper, aggregateVarianceUpper,
                demandUpper, config, useAggregateVariance);

        long started = System.nanoTime();
        List<Cut> cuts = new ArrayList<>();
        double[] initialY = initialSelection(params);
        RCSAADecompositionSupport.ScenarioCut initial =
                RCSAADecompositionSupport.solveScenarioCut(
                        params, mean, initialY, 0, true);
        cuts.add(new Cut(mean, initial.constantPart, initial.yCoeff));

        MasterResult master = null;
        Separation separation = null;
        boolean converged = false;
        int separationSolves = 0;
        int maximumIterations = Math.max(1, config.maxBendersIter);
        double tolerance = Math.max(1e-8, config.tol);

        for (int iteration = 1; iteration <= maximumIterations; iteration++) {
            master = solveMaster(params, mean, marginalVarianceUpper,
                    aggregateVarianceUpper, useAggregateVariance, cuts, config);

            // The Dirac distribution at the prescribed mean is always feasible.
            // Anchor every incumbent selection at that point before global
            // separation; otherwise a much larger box-boundary violation can
            // repeatedly starve this necessary recourse face.
            RCSAADecompositionSupport.ScenarioCut meanScenario =
                    RCSAADecompositionSupport.solveScenarioCut(
                            params, mean, master.y, -1, true);
            Cut meanCut = new Cut(mean, meanScenario.constantPart,
                    meanScenario.yCoeff);
            double meanViolation = meanScenario.qValue
                    - momentUpper(master, mean, mean);
            double masterScale = Math.max(1.0, Math.abs(master.objective));
            if (meanViolation > tolerance * masterScale
                    && !containsEquivalent(cuts, meanCut)) {
                cuts.add(meanCut);
                System.out.printf(java.util.Locale.US,
                        "Partial-moment iteration=%d LB=%.10f meanViolation=%.10f "
                                + "cuts=%d y=%s%n",
                        iteration, master.objective, meanViolation, cuts.size(),
                        java.util.Arrays.toString(master.y));
                continue;
            }

            separation = separate(params, master, mean, demandUpper, config);
            separationSolves++;

            double violationTolerance = tolerance * masterScale;
            System.out.printf(java.util.Locale.US,
                    "Partial-moment iteration=%d LB=%.10f violation=%.10f cuts=%d y=%s%n",
                    iteration, master.objective, separation.violation, cuts.size(),
                    java.util.Arrays.toString(master.y));
            if (separation.violation <= violationTolerance) {
                converged = true;
                break;
            }
            if (containsEquivalent(cuts, separation.cut)) {
                throw new IllegalStateException(
                        "Repeated violated partial-moment cut; violation="
                                + separation.violation);
            }
            cuts.add(separation.cut);
        }

        if (master == null || separation == null) {
            throw new IllegalStateException("Partial-moment solver did not execute.");
        }
        double relativeGap = Math.max(0.0, separation.violation)
                / Math.max(1.0, Math.abs(master.objective));
        PrimalCertificate primalCertificate = solveGeneratedSupportPrimal(params, master.y,
                mean, marginalVarianceUpper, aggregateVarianceUpper, cuts,
                config, useAggregateVariance);
        double atomicGap = Math.abs(master.objective - primalCertificate.objective)
                / Math.max(1.0, Math.abs(master.objective));
        boolean certified = converged && separation.certified
                && separation.globalGap <= config.tol
                && relativeGap <= config.tol && atomicGap <= config.tol;

        Solution solution = new Solution(master.objective, master.y,
                (System.nanoTime() - started) / 1.0e9);
        solution.solverStatus = certified
                ? "OPTIMAL_EXACT_PARTIAL_MOMENT"
                : "UNCERTIFIED_PARTIAL_MOMENT";
        solution.bestBound = master.objective;
        solution.relativeGap = relativeGap;
        solution.iterationCount = separationSolves;
        solution.cutCount = cuts.size();
        solution.candidateCount = separationSolves;
        solution.certifiedOptimal = certified;
        return new Result(solution, master.intercept, master.meanPrice,
                master.marginalVariancePrice, master.aggregateVariancePrice,
                separation.demand, separation.violation, cuts.size(),
                separationSolves, primalCertificate, atomicGap,
                separation.status, separation.globalGap);
    }

    private static MasterResult solveMaster(ProcurementParams params,
                                             double[] mean,
                                             double[] marginalVarianceUpper,
                                             double aggregateVarianceUpper,
                                             boolean useAggregateVariance,
                                             List<Cut> cuts,
                                             Config config) throws Exception {
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, config.threads);
            cplex.setParam(IloCplex.Param.TimeLimit, config.timeLimitSeconds);
            cplex.setParam(IloCplex.Param.MIP.Tolerances.MIPGap,
                    Math.max(1e-9, config.tol * 0.1));

            IloNumVar[] y = cplex.boolVarArray(params.I);
            IloNumVar intercept = cplex.numVar(
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, "moment_a");
            IloNumVar[] meanPrice = cplex.numVarArray(params.J,
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            IloNumVar[] variancePrice = cplex.numVarArray(params.J,
                    0.0, Double.POSITIVE_INFINITY);
            IloNumVar aggregatePrice = cplex.numVar(0.0,
                    useAggregateVariance ? Double.POSITIVE_INFINITY : 0.0,
                    "moment_eta");

            IloLinearNumExpr selected = cplex.linearNumExpr();
            for (IloNumVar variable : y) selected.addTerm(1.0, variable);
            cplex.addGe(selected, params.alpha);
            cplex.addLe(selected, params.beta);

            for (int k = 0; k < cuts.size(); k++) {
                Cut cut = cuts.get(k);
                IloLinearNumExpr lhs = cplex.linearNumExpr();
                lhs.addTerm(1.0, intercept);
                double totalDeviation = 0.0;
                for (int j = 0; j < params.J; j++) {
                    lhs.addTerm(cut.demand[j], meanPrice[j]);
                    double deviation = cut.demand[j] - mean[j];
                    lhs.addTerm(deviation * deviation, variancePrice[j]);
                    totalDeviation += deviation;
                }
                lhs.addTerm(totalDeviation * totalDeviation, aggregatePrice);
                for (int i = 0; i < params.I; i++) {
                    lhs.addTerm(-cut.yCoefficient[i], y[i]);
                }
                cplex.addGe(lhs, cut.constant, "moment_cut_" + k);
            }

            IloLinearNumExpr objective = cplex.linearNumExpr();
            objective.addTerm(1.0, intercept);
            for (int j = 0; j < params.J; j++) {
                objective.addTerm(mean[j], meanPrice[j]);
                objective.addTerm(marginalVarianceUpper[j], variancePrice[j]);
            }
            objective.addTerm(aggregateVarianceUpper, aggregatePrice);
            cplex.addMinimize(objective);

            if (!cplex.solve() || cplex.getStatus() != IloCplex.Status.Optimal) {
                throw new IllegalStateException(
                        "Partial-moment master failed: " + cplex.getCplexStatus());
            }
            double[] yValue = new double[params.I];
            for (int i = 0; i < params.I; i++) {
                yValue[i] = cplex.getValue(y[i]) > 0.5 ? 1.0 : 0.0;
            }
            return new MasterResult(cplex.getObjValue(), yValue,
                    cplex.getValue(intercept), cplex.getValues(meanPrice),
                    cplex.getValues(variancePrice), cplex.getValue(aggregatePrice));
        }
    }

    private static Separation separate(ProcurementParams params,
                                       MasterResult master,
                                       double[] mean,
                                       double[] demandUpper,
                                       Config config) throws Exception {
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, config.threads);
            cplex.setParam(IloCplex.Param.TimeLimit, config.timeLimitSeconds);
            cplex.setParam(IloCplex.Param.OptimalityTarget,
                    IloCplex.OptimalityTarget.OptimalGlobal);
            cplex.setParam(IloCplex.Param.MIP.Tolerances.MIPGap,
                    Math.max(1e-9, config.tol * 0.1));
            cplex.setParam(IloCplex.Param.MIP.Tolerances.AbsMIPGap,
                    Math.max(1e-8, config.tol * 0.1));

            int I = params.I;
            int J = params.J;
            IloNumVar[] demand = new IloNumVar[J];
            IloNumVar[] laneDual = new IloNumVar[J];
            for (int j = 0; j < J; j++) {
                demand[j] = cplex.numVar(0.0, demandUpper[j], "d_" + j);
                laneDual[j] = cplex.numVar(alphaLowerBound(params, j),
                        params.e[j], "a_" + j);
            }
            IloNumVar[] minimumDual = new IloNumVar[I];
            IloNumVar[] totalCapacityDual = new IloNumVar[I];
            IloNumVar[][] laneCapacityDual = new IloNumVar[I][J];
            for (int i = 0; i < I; i++) {
                double carrierBound = carrierDualBound(params, i);
                minimumDual[i] = cplex.numVar(0.0, params.h[i], "b_" + i);
                totalCapacityDual[i] = cplex.numVar(
                        0.0, carrierBound, "g_" + i);
                for (int j = 0; j < J; j++) {
                    if (!params.eligible[i][j]) continue;
                    double bound = laneDualBound(params, i, j);
                    laneCapacityDual[i][j] = cplex.numVar(
                            0.0, bound, "s_" + i + "_" + j);
                    IloLinearNumExpr dualFeasibility = cplex.linearNumExpr();
                    dualFeasibility.addTerm(1.0, laneDual[j]);
                    dualFeasibility.addTerm(1.0, minimumDual[i]);
                    dualFeasibility.addTerm(-1.0, totalCapacityDual[i]);
                    dualFeasibility.addTerm(-1.0, laneCapacityDual[i][j]);
                    cplex.addLe(dualFeasibility, params.r[i][j]);
                }
            }

            IloLQNumExpr quadratic = cplex.lqNumExpr();
            for (int j = 0; j < J; j++) {
                quadratic.addTerm(1.0, demand[j], laneDual[j]);
                double linear = -master.meanPrice[j]
                        + 2.0 * master.marginalVariancePrice[j] * mean[j];
                quadratic.addTerm(linear, demand[j]);
                quadratic.addTerm(-master.marginalVariancePrice[j],
                        demand[j], demand[j]);
            }
            double meanTotal = sum(mean);
            for (int j = 0; j < J; j++) {
                quadratic.addTerm(2.0 * master.aggregateVariancePrice * meanTotal,
                        demand[j]);
                quadratic.addTerm(-master.aggregateVariancePrice,
                        demand[j], demand[j]);
                for (int k = j + 1; k < J; k++) {
                    quadratic.addTerm(-2.0 * master.aggregateVariancePrice,
                            demand[j], demand[k]);
                }
            }
            for (int i = 0; i < I; i++) {
                if (master.y[i] > 0.5) {
                    quadratic.addTerm(params.p[i], minimumDual[i]);
                    quadratic.addTerm(-params.M[i], totalCapacityDual[i]);
                }
                for (int j = 0; j < J; j++) {
                    if (params.eligible[i][j]) {
                        quadratic.addTerm(-params.q[i][j], laneCapacityDual[i][j]);
                    }
                }
            }
            double constant = -master.intercept;
            for (int j = 0; j < J; j++) {
                constant -= master.marginalVariancePrice[j] * mean[j] * mean[j];
            }
            constant -= master.aggregateVariancePrice * meanTotal * meanTotal;
            IloNumExpr objective = cplex.sum(quadratic, constant);
            cplex.addMaximize(objective);

            boolean solved = cplex.solve();
            boolean certified = solved && cplex.getStatus() == IloCplex.Status.Optimal;
            if (!solved) {
                throw new IllegalStateException(
                        "Partial-moment separation failed: " + cplex.getCplexStatus());
            }
            double[] demandValue = cplex.getValues(demand);
            double momentUpper = momentUpper(master, mean, demandValue);
            double primalRecourse = SecondStageEvaluator.evaluate(
                    params, master.y, demandValue, true).objective;
            RCSAADecompositionSupport.ScenarioCut reliableCut =
                    RCSAADecompositionSupport.solveScenarioCut(
                            params, demandValue, master.y, -1, true);
            double cutConstant = reliableCut.constantPart;
            double[] yCoefficient = reliableCut.yCoeff;
            double dualRecourse = cutConstant + dot(yCoefficient, master.y);
            double recourseTolerance = 1e-6 * Math.max(1.0, Math.abs(primalRecourse));
            if (Math.abs(primalRecourse - dualRecourse) > recourseTolerance) {
                throw new IllegalStateException(
                        "Partial-moment separation recourse primal/dual mismatch: primal="
                                + primalRecourse + ", dual=" + dualRecourse);
            }
            double actualViolation = primalRecourse - momentUpper;
            double objectiveTolerance = Math.max(1e-6, config.tol)
                    * Math.max(1.0, Math.abs(actualViolation));
            if (Math.abs(actualViolation - cplex.getObjValue()) > objectiveTolerance) {
                throw new IllegalStateException(
                        "Partial-moment separation objective mismatch: actual="
                                + actualViolation + ", qp=" + cplex.getObjValue());
            }
            double globalGap = cplex.isMIP() ? cplex.getMIPRelativeGap() : 0.0;
            return new Separation(actualViolation,
                    new Cut(demandValue, cutConstant, yCoefficient), demandValue,
                    certified, cplex.getCplexStatus().toString(), globalGap);
        }
    }

    private static PrimalCertificate solveGeneratedSupportPrimal(
            ProcurementParams params,
            double[] y,
            double[] mean,
            double[] marginalVarianceUpper,
            double aggregateVarianceUpper,
            List<Cut> cuts,
            Config config,
            boolean useAggregateVariance) throws Exception {
        List<double[]> support = uniqueDemands(cuts);
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, config.threads);
            IloNumVar[] probability = cplex.numVarArray(
                    support.size(), 0.0, Double.POSITIVE_INFINITY);
            IloLinearNumExpr totalProbability = cplex.linearNumExpr();
            for (IloNumVar variable : probability) totalProbability.addTerm(1.0, variable);
            cplex.addEq(totalProbability, 1.0);
            for (int j = 0; j < mean.length; j++) {
                IloLinearNumExpr moment = cplex.linearNumExpr();
                for (int k = 0; k < support.size(); k++) {
                    moment.addTerm(support.get(k)[j], probability[k]);
                }
                cplex.addEq(moment, mean[j]);
            }
            for (int j = 0; j < mean.length; j++) {
                IloLinearNumExpr variance = cplex.linearNumExpr();
                for (int k = 0; k < support.size(); k++) {
                    double deviation = support.get(k)[j] - mean[j];
                    variance.addTerm(deviation * deviation, probability[k]);
                }
                cplex.addLe(variance, marginalVarianceUpper[j]);
            }
            if (useAggregateVariance) {
                IloLinearNumExpr aggregate = cplex.linearNumExpr();
                for (int k = 0; k < support.size(); k++) {
                    double deviation = 0.0;
                    for (int j = 0; j < mean.length; j++) {
                        deviation += support.get(k)[j] - mean[j];
                    }
                    aggregate.addTerm(deviation * deviation, probability[k]);
                }
                cplex.addLe(aggregate, aggregateVarianceUpper);
            }

            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int k = 0; k < support.size(); k++) {
                double recourse = SecondStageEvaluator.evaluate(
                        params, y, support.get(k), true).objective;
                objective.addTerm(recourse, probability[k]);
            }
            cplex.addMaximize(objective);
            if (!cplex.solve() || cplex.getStatus() != IloCplex.Status.Optimal) {
                throw new IllegalStateException(
                        "Generated-support moment primal failed: " + cplex.getCplexStatus());
            }
            double[] probabilityValue = cplex.getValues(probability);
            double probabilityResidual = Math.abs(sum(probabilityValue) - 1.0);
            double maximumMeanResidual = 0.0;
            for (int j = 0; j < mean.length; j++) {
                double attained = 0.0;
                for (int k = 0; k < support.size(); k++) {
                    attained += probabilityValue[k] * support.get(k)[j];
                }
                maximumMeanResidual = Math.max(maximumMeanResidual,
                        Math.abs(attained - mean[j]));
            }
            double maximumMarginalVarianceViolation = 0.0;
            for (int j = 0; j < mean.length; j++) {
                double attained = 0.0;
                for (int k = 0; k < support.size(); k++) {
                    double deviation = support.get(k)[j] - mean[j];
                    attained += probabilityValue[k] * deviation * deviation;
                }
                maximumMarginalVarianceViolation = Math.max(
                        maximumMarginalVarianceViolation,
                        Math.max(0.0, attained - marginalVarianceUpper[j]));
            }
            double attainedAggregateVariance = 0.0;
            int positiveAtoms = 0;
            for (int k = 0; k < support.size(); k++) {
                if (probabilityValue[k] > 1e-8) positiveAtoms++;
                double deviation = 0.0;
                for (int j = 0; j < mean.length; j++) {
                    deviation += support.get(k)[j] - mean[j];
                }
                if (useAggregateVariance) {
                    attainedAggregateVariance += probabilityValue[k]
                            * deviation * deviation;
                }
            }
            return new PrimalCertificate(cplex.getObjValue(), support.size(),
                    positiveAtoms, probabilityResidual, maximumMeanResidual,
                    maximumMarginalVarianceViolation,
                    useAggregateVariance
                            ? Math.max(0.0, attainedAggregateVariance
                                    - aggregateVarianceUpper)
                            : 0.0);
        }
    }

    private static double momentUpper(MasterResult master,
                                      double[] mean,
                                      double[] demand) {
        double value = master.intercept + dot(master.meanPrice, demand);
        double aggregateDeviation = 0.0;
        for (int j = 0; j < mean.length; j++) {
            double deviation = demand[j] - mean[j];
            value += master.marginalVariancePrice[j] * deviation * deviation;
            aggregateDeviation += deviation;
        }
        return value + master.aggregateVariancePrice
                * aggregateDeviation * aggregateDeviation;
    }

    private static double[] initialSelection(ProcurementParams params) {
        double[] y = new double[params.I];
        for (int i = 0; i < params.alpha; i++) y[i] = 1.0;
        return y;
    }

    private static double laneDualBound(ProcurementParams params, int i, int j) {
        return Math.max(0.0, params.e[j] + params.h[i] - params.r[i][j]);
    }

    private static double alphaLowerBound(ProcurementParams params, int j) {
        double lower = 0.0;
        for (int i = 0; i < params.I; i++) {
            if (params.eligible[i][j]) {
                lower = Math.min(lower, params.r[i][j] - params.h[i]);
            }
        }
        return lower;
    }

    private static double carrierDualBound(ProcurementParams params, int i) {
        double bound = 0.0;
        for (int j = 0; j < params.J; j++) {
            if (params.eligible[i][j]) {
                bound = Math.max(bound, laneDualBound(params, i, j));
            }
        }
        return bound;
    }

    private static boolean containsEquivalent(List<Cut> cuts, Cut candidate) {
        for (Cut cut : cuts) {
            if (same(cut.demand, candidate.demand)
                    && same(cut.yCoefficient, candidate.yCoefficient)
                    && Math.abs(cut.constant - candidate.constant)
                    <= DUPLICATE_TOLERANCE) return true;
        }
        return false;
    }

    private static List<double[]> uniqueDemands(List<Cut> cuts) {
        List<double[]> unique = new ArrayList<>();
        for (Cut cut : cuts) {
            boolean found = false;
            for (double[] demand : unique) {
                if (same(demand, cut.demand)) {
                    found = true;
                    break;
                }
            }
            if (!found) unique.add(cut.demand.clone());
        }
        return unique;
    }

    private static boolean same(double[] left, double[] right) {
        if (left.length != right.length) return false;
        for (int index = 0; index < left.length; index++) {
            if (Math.abs(left[index] - right[index]) > DUPLICATE_TOLERANCE) return false;
        }
        return true;
    }

    private static double dot(double[] left, double[] right) {
        double value = 0.0;
        for (int index = 0; index < left.length; index++) {
            value += left[index] * right[index];
        }
        return value;
    }

    private static double sum(double[] values) {
        double value = 0.0;
        for (double entry : values) value += entry;
        return value;
    }

    private static void validate(ProcurementParams params,
                                 double[] mean,
                                 double[] marginalVarianceUpper,
                                 double aggregateVarianceUpper,
                                 double[] demandUpper,
                                 Config config,
                                 boolean useAggregateVariance) {
        if (params == null || config == null) {
            throw new IllegalArgumentException("params/config required");
        }
        if (!config.enforceDemandEquality) {
            throw new IllegalArgumentException(
                    "Exact partial-moment solver requires demand equality.");
        }
        if (mean.length != params.J || marginalVarianceUpper.length != params.J
                || demandUpper.length != params.J) {
            throw new IllegalArgumentException("Moment dimension mismatch.");
        }
        if (useAggregateVariance && (!(aggregateVarianceUpper > 0.0)
                || !Double.isFinite(aggregateVarianceUpper))) {
            throw new IllegalArgumentException("Aggregate variance upper bound must be positive.");
        }
        for (int j = 0; j < params.J; j++) {
            if (mean[j] < 0.0 || mean[j] > demandUpper[j]
                    || !(marginalVarianceUpper[j] > 0.0)
                    || !Double.isFinite(mean[j])
                    || !Double.isFinite(demandUpper[j])
                    || !Double.isFinite(marginalVarianceUpper[j])) {
                throw new IllegalArgumentException("Invalid moment/support at lane " + j);
            }
        }
    }

    private record Cut(double[] demand, double constant, double[] yCoefficient) {
        private Cut {
            demand = demand.clone();
            yCoefficient = yCoefficient.clone();
        }
    }

    private record MasterResult(double objective,
                                double[] y,
                                double intercept,
                                double[] meanPrice,
                                double[] marginalVariancePrice,
                                double aggregateVariancePrice) {
        private MasterResult {
            y = y.clone();
            meanPrice = meanPrice.clone();
            marginalVariancePrice = marginalVariancePrice.clone();
        }
    }

    private record Separation(double violation,
                              Cut cut,
                              double[] demand,
                              boolean certified,
                              String status,
                              double globalGap) {
        private Separation {
            demand = demand.clone();
        }
    }

    private record PrimalCertificate(double objective,
                                     int supportPoints,
                                     int positiveAtoms,
                                     double probabilityResidual,
                                     double maximumMeanResidual,
                                     double maximumMarginalVarianceViolation,
                                     double aggregateVarianceViolation) {
    }

    public static final class Result {
        public final Solution solution;
        public final double intercept;
        public final double[] meanPrice;
        public final double[] marginalVariancePrice;
        public final double aggregateVariancePrice;
        public final double[] worstDemand;
        public final double finalViolation;
        public final int cuts;
        public final int separationSolves;
        public final double generatedSupportLowerBound;
        public final double generatedSupportRelativeGap;
        public final int generatedSupportPoints;
        public final int positiveProbabilityAtoms;
        public final double probabilityResidual;
        public final double maximumMeanResidual;
        public final double maximumMarginalVarianceViolation;
        public final double aggregateVarianceViolation;
        public final String separationStatus;
        public final double separationGlobalGap;

        private Result(Solution solution,
                       double intercept,
                       double[] meanPrice,
                       double[] marginalVariancePrice,
                       double aggregateVariancePrice,
                       double[] worstDemand,
                       double finalViolation,
                       int cuts,
                       int separationSolves,
                       PrimalCertificate primalCertificate,
                       double generatedSupportRelativeGap,
                       String separationStatus,
                       double separationGlobalGap) {
            this.solution = solution;
            this.intercept = intercept;
            this.meanPrice = meanPrice.clone();
            this.marginalVariancePrice = marginalVariancePrice.clone();
            this.aggregateVariancePrice = aggregateVariancePrice;
            this.worstDemand = worstDemand.clone();
            this.finalViolation = finalViolation;
            this.cuts = cuts;
            this.separationSolves = separationSolves;
            this.generatedSupportLowerBound = primalCertificate.objective;
            this.generatedSupportRelativeGap = generatedSupportRelativeGap;
            this.generatedSupportPoints = primalCertificate.supportPoints;
            this.positiveProbabilityAtoms = primalCertificate.positiveAtoms;
            this.probabilityResidual = primalCertificate.probabilityResidual;
            this.maximumMeanResidual = primalCertificate.maximumMeanResidual;
            this.maximumMarginalVarianceViolation =
                    primalCertificate.maximumMarginalVarianceViolation;
            this.aggregateVarianceViolation =
                    primalCertificate.aggregateVarianceViolation;
            this.separationStatus = separationStatus;
            this.separationGlobalGap = separationGlobalGap;
        }
    }
}
