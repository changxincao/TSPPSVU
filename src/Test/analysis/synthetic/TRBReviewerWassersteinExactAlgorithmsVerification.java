package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Data;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Model.ContextualWassersteinBoxCcgSolver;
import Model.ContextualWassersteinBoxCallbackMultiCutSolver;
import Model.ContextualWassersteinBoxRegularizedSingleCutBendersSolver;
import Model.ContextualWassersteinBoxRegularizedMultiCutBendersSolver;
import Model.ContextualWassersteinBoxSingleCutBendersSolver;
import Model.ContextualWassersteinBoxSolver;
import Model.SecondStageEvaluator;
import Model.Solution;
import Model.WassersteinBoxInput;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Exact small-instance gate for the three bounded-box W1 implementations.
 * The reference independently enumerates first-stage selections and every
 * sample-wise {lower, nominal, upper} demand corner, then solves only the eta LP.
 */
public final class TRBReviewerWassersteinExactAlgorithmsVerification {
    private TRBReviewerWassersteinExactAlgorithmsVerification() {
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0
                ? Path.of("analysis", "wasserstein_exact_algorithms_validation_20260823")
                : Path.of(args[0]);
        Files.createDirectories(output);

        ProcurementParams params = params();
        double[][] demand = {{4.0, 3.0}, {6.0, 2.0}, {5.0, 5.0}};
        double[] upper = {8.0, 7.0};
        double[] scale = {2.0, 2.5};
        List<Case> cases = List.of(
                new Case("equal", new double[]{1.0, 1.0, 1.0}, 0.0),
                new Case("equal", new double[]{1.0, 1.0, 1.0}, 0.4),
                new Case("contextual", new double[]{1.0, 2.0, 7.0}, 0.0),
                new Case("contextual", new double[]{1.0, 2.0, 7.0}, 0.4),
                new Case("zero_probability", new double[]{0.0, 1.0, 4.0}, 0.0),
                new Case("zero_probability", new double[]{0.0, 1.0, 4.0}, 0.4));

        Config config = new Config();
        config.enforceDemandEquality = true;
        config.threads = 1;
        config.timeLimitSeconds = 60;
        config.maxBendersIter = 500;
        config.tol = 1e-7;

        List<String> rows = new ArrayList<>();
        rows.add("case,radius,method,objective,fixed_y_objective,y,eta,iterations,cuts,oracles,certified,gap");
        for (Case test : cases) {
            WassersteinBoxInput input = new WassersteinBoxInput(
                    params, demand, test.probability, upper, scale, test.radius);
            Reference reference = enumerateReference(input, config);

            ContextualWassersteinBoxCcgSolver.Result ccg =
                    new ContextualWassersteinBoxCcgSolver().solve(input, config);
            check("CCG", input, config, reference, ccg.solution(), ccg.eta());
            rows.add(row(test, "CCG", ccg.solution(), ccg.eta(),
                    ccg.iterations(), ccg.generatedPoints(), ccg.oracleSolves(),
                    fixedObjective(input, config, ccg.solution().y)));

            ContextualWassersteinBoxCallbackMultiCutSolver.Result callback =
                    new ContextualWassersteinBoxCallbackMultiCutSolver().solve(input, config);
            check("CALLBACK_MULTI_CUT", input, config, reference,
                    callback.solution(), callback.eta());
            rows.add(row(test, "CALLBACK_MULTI_CUT", callback.solution(),
                    callback.eta(), callback.callbackCalls(), callback.cuts(),
                    callback.oracleSolves(),
                    fixedObjective(input, config, callback.solution().y)));

            ContextualWassersteinBoxSingleCutBendersSolver.Result single =
                    new ContextualWassersteinBoxSingleCutBendersSolver().solve(input, config);
            check("SINGLE_CUT", input, config, reference, single.solution(), single.eta());
            rows.add(row(test, "SINGLE_CUT", single.solution(), single.eta(),
                    single.iterations(), single.cuts(), single.oracleSolves(),
                    fixedObjective(input, config, single.solution().y)));

            ContextualWassersteinBoxRegularizedSingleCutBendersSolver.Result regularized =
                    new ContextualWassersteinBoxRegularizedSingleCutBendersSolver()
                            .solve(input, config, 0.5);
            check("REGULARIZED_SINGLE_CUT", input, config, reference,
                    regularized.solution(), regularized.eta());
            rows.add(row(test, "REGULARIZED_SINGLE_CUT", regularized.solution(),
                    regularized.eta(), regularized.iterations(), regularized.cuts(),
                    regularized.oracleSolves(),
                    fixedObjective(input, config, regularized.solution().y)));

            ContextualWassersteinBoxRegularizedMultiCutBendersSolver.Result regularizedMulti =
                    new ContextualWassersteinBoxRegularizedMultiCutBendersSolver()
                            .solve(input, config, 0.5);
            check("REGULARIZED_MULTI_CUT", input, config, reference,
                    regularizedMulti.solution(), regularizedMulti.eta());
            rows.add(row(test, "REGULARIZED_MULTI_CUT", regularizedMulti.solution(),
                    regularizedMulti.eta(), regularizedMulti.iterations(),
                    regularizedMulti.cuts(), regularizedMulti.oracleSolves(),
                    fixedObjective(input, config, regularizedMulti.solution().y)));

            Data data = data(params, demand, test.probability);
            ContextualWassersteinBoxSolver.Result old = new ContextualWassersteinBoxSolver()
                    .solve(data, config, upper, scale, test.radius);
            check("DUAL_VERTEX", input, config, reference, old.solution, old.eta);
            rows.add(row(test, "DUAL_VERTEX", old.solution, old.eta,
                    old.iterations, old.cuts, old.separationSolves,
                    fixedObjective(input, config, old.solution.y)));

            Solution referenceSolution = new Solution(reference.objective,
                    reference.y, 0.0);
            referenceSolution.certifiedOptimal = true;
            referenceSolution.relativeGap = 0.0;
            rows.add(row(test, "ENUMERATION_REFERENCE", referenceSolution,
                    reference.eta, 0, 0, 0, reference.objective));
        }
        LowerEndpointGate lowerGate = checkLowerEndpointCase(config);
        IterationLimitGate iterationGate = checkIterationLimitedIncumbent(
                params, demand, upper, scale);
        checkRejectedInputs(params, demand, upper, scale);
        Files.write(output.resolve("verification.csv"), rows, StandardCharsets.UTF_8);
        Files.write(output.resolve("randomized_verification.csv"),
                randomizedChecks(config), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("lower_endpoint_gate.txt"),
                String.format(Locale.US,
                        "PASSED: exact three-endpoint objective=%.12f; "
                                + "incorrect nominal/upper-only objective=%.12f.%n",
                        lowerGate.exactObjective, lowerGate.upperOnlyObjective),
                StandardCharsets.UTF_8);
        Files.writeString(output.resolve("iteration_limit_incumbent_gate.txt"),
                String.format(Locale.US,
                        "PASSED: iteration-limited legacy objective=%.12f; "
                                + "fixed-y optimum=%.12f; full reference=%.12f; certified=%s.%n",
                        iterationGate.returnedObjective, iterationGate.fixedYObjective,
                        iterationGate.referenceObjective, iterationGate.certified),
                StandardCharsets.UTF_8);
        Files.writeString(output.resolve("validation.txt"),
                "PASSED: all algorithms matched exhaustive selection/corner reference; "
                        + "equal, contextual, and exact zero probabilities were tested; "
                        + "eight additional randomized instances passed; h_i>r_ij and lower "
                        + "box endpoints were exercised; invalid probability, box, and radius "
                        + "inputs were rejected; the iteration-limited legacy solver retained "
                        + "its best incumbent.\n",
                StandardCharsets.UTF_8);
        System.out.println("WASSERSTEIN_EXACT_ALGORITHMS_VALIDATION=PASSED");
    }

    private static void check(String method,
                              WassersteinBoxInput input,
                              Config config,
                              Reference reference,
                              Solution solution,
                              double eta) throws Exception {
        if (!solution.certifiedOptimal) {
            throw new IllegalStateException(method + " was not certified: " + solution.solverStatus);
        }
        double fixed = fixedObjective(input, config, solution.y);
        double tolerance = 2e-6 * Math.max(1.0, Math.abs(reference.objective));
        if (Math.abs(solution.objValue - reference.objective) > tolerance
                || Math.abs(fixed - reference.objective) > tolerance
                || eta < -1e-8 || eta > input.lipschitzBound() + 1e-8) {
            throw new IllegalStateException(method + " mismatch: result=" + solution.objValue
                    + ", fixedY=" + fixed + ", reference=" + reference.objective
                    + ", eta=" + eta);
        }
    }

    private static String row(Case test,
                              String method,
                              Solution solution,
                              double eta,
                              int iterations,
                              int cuts,
                              int oracles,
                              double fixedObjective) {
        return String.format(Locale.US,
                "%s,%.6f,%s,%.12f,%.12f,%s,%.12f,%d,%d,%d,%s,%.12g",
                test.name, test.radius, method, solution.objValue, fixedObjective,
                binary(solution.y), eta, iterations, cuts, oracles,
                solution.certifiedOptimal, solution.relativeGap);
    }

    private static Reference enumerateReference(WassersteinBoxInput input,
                                                Config config) throws Exception {
        double best = Double.POSITIVE_INFINITY;
        double[] bestY = null;
        double bestEta = Double.NaN;
        int combinations = 1 << input.params.I;
        for (int mask = 0; mask < combinations; mask++) {
            int selected = Integer.bitCount(mask);
            if (selected < input.params.alpha || selected > input.params.beta) continue;
            double[] y = new double[input.params.I];
            for (int i = 0; i < y.length; i++) y[i] = (mask >>> i) & 1;
            FixedResult fixed = fixedResult(input, config, y);
            if (fixed.objective < best) {
                best = fixed.objective;
                bestY = y;
                bestEta = fixed.eta;
            }
        }
        if (bestY == null) throw new IllegalStateException("No feasible carrier selection.");
        return new Reference(best, bestEta, bestY);
    }

    private static double fixedObjective(WassersteinBoxInput input,
                                         Config config,
                                         double[] y) throws Exception {
        return fixedResult(input, config, y).objective;
    }

    private static FixedResult fixedResult(WassersteinBoxInput input,
                                           Config config,
                                           double[] y) throws Exception {
        return fixedResult(input, config, y, true);
    }

    private static FixedResult fixedResult(WassersteinBoxInput input,
                                           Config config,
                                           double[] y,
                                           boolean includeLower) throws Exception {
        if (input.params.J >= 20) {
            throw new IllegalArgumentException("Reference gate is only for small J.");
        }
        IloCplex cplex = new IloCplex();
        try {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, config.threads);
            IloNumVar eta = cplex.numVar(0.0, input.lipschitzBound(), "eta");
            IloNumVar[] t = cplex.numVarArray(input.sampleCount(),
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            for (int s = 0; s < input.sampleCount(); s++) {
                int endpointCount = includeLower ? 3 : 2;
                int corners = integerPower(endpointCount, input.params.J);
                for (int mask = 0; mask < corners; mask++) {
                    double[] point = input.demand[s].clone();
                    int code = mask;
                    for (int j = 0; j < input.params.J; j++) {
                        int endpoint = code % endpointCount;
                        code /= endpointCount;
                        if (includeLower && endpoint == 0) point[j] = 0.0;
                        if ((!includeLower && endpoint == 1)
                                || (includeLower && endpoint == 2)) {
                            point[j] = input.upper[j];
                        }
                    }
                    double recourse = SecondStageEvaluator.evaluate(
                            input.params, y, point, true).objective;
                    IloLinearNumExpr rhs = cplex.linearNumExpr(recourse);
                    rhs.addTerm(-input.distance(s, point), eta);
                    cplex.addGe(t[s], rhs);
                }
            }
            IloLinearNumExpr objective = cplex.linearNumExpr();
            objective.addTerm(input.radius, eta);
            for (int s = 0; s < input.sampleCount(); s++) {
                objective.addTerm(input.probability[s], t[s]);
            }
            cplex.addMinimize(objective);
            if (!cplex.solve() || cplex.getStatus() != IloCplex.Status.Optimal) {
                throw new IllegalStateException("Reference eta LP failed: " + cplex.getStatus());
            }
            return new FixedResult(cplex.getObjValue(), cplex.getValue(eta));
        } finally {
            cplex.end();
        }
    }

    private static ProcurementParams params() {
        return new ProcurementParams(
                List.of("c0", "c1", "c2"),
                2,
                new double[]{4.2, 4.0},
                new double[]{3.0, 3.5, 2.5},
                new double[]{2.4, 1.0, 0.8},
                new double[][]{{6.0, 2.0}, {2.0, 6.0}, {4.0, 4.0}},
                new double[][]{{1.0, 1.7}, {1.6, 1.0}, {1.25, 1.3}},
                new boolean[][]{{true, true}, {true, true}, {true, true}},
                1, 2);
    }

    private static LowerEndpointGate checkLowerEndpointCase(Config config) throws Exception {
        ProcurementParams params = new ProcurementParams(
                List.of("c0"), 1,
                new double[]{10.0}, new double[]{5.0}, new double[]{8.0},
                new double[][]{{10.0}}, new double[][]{{1.0}},
                new boolean[][]{{true}}, 1, 1);
        double[][] demand = {{5.0}};
        double[] upper = {10.0};
        double[] scale = {1.0};
        WassersteinBoxInput input = WassersteinBoxInput.equalWeight(
                params, demand, upper, scale, 1.0);
        Reference reference = enumerateReference(input, config);
        double upperOnly = fixedResult(input, config, new double[]{1.0}, false).objective;
        if (reference.objective <= upperOnly + 1e-6) {
            throw new IllegalStateException("Lower endpoint gate did not require the lower endpoint.");
        }

        ContextualWassersteinBoxCcgSolver.Result ccg =
                new ContextualWassersteinBoxCcgSolver().solve(input, config);
        check("LOWER_CCG", input, config, reference, ccg.solution(), ccg.eta());
        ContextualWassersteinBoxCallbackMultiCutSolver.Result callback =
                new ContextualWassersteinBoxCallbackMultiCutSolver().solve(input, config);
        check("LOWER_CALLBACK_MULTI", input, config, reference,
                callback.solution(), callback.eta());
        ContextualWassersteinBoxSingleCutBendersSolver.Result single =
                new ContextualWassersteinBoxSingleCutBendersSolver().solve(input, config);
        check("LOWER_SINGLE", input, config, reference, single.solution(), single.eta());
        ContextualWassersteinBoxRegularizedSingleCutBendersSolver.Result regularized =
                new ContextualWassersteinBoxRegularizedSingleCutBendersSolver()
                        .solve(input, config, 0.5);
        check("LOWER_REGULARIZED", input, config, reference,
                regularized.solution(), regularized.eta());
        ContextualWassersteinBoxRegularizedMultiCutBendersSolver.Result regularizedMulti =
                new ContextualWassersteinBoxRegularizedMultiCutBendersSolver()
                        .solve(input, config, 0.5);
        check("LOWER_REGULARIZED_MULTI", input, config, reference,
                regularizedMulti.solution(), regularizedMulti.eta());
        ContextualWassersteinBoxSolver.Result dual = new ContextualWassersteinBoxSolver()
                .solve(data(params, demand, new double[]{1.0}), config,
                        upper, scale, input.radius);
        check("LOWER_DUAL_VERTEX", input, config, reference,
                dual.solution, dual.eta);
        return new LowerEndpointGate(reference.objective, upperOnly);
    }

    private static IterationLimitGate checkIterationLimitedIncumbent(
            ProcurementParams params,
            double[][] demand,
            double[] upper,
            double[] scale) throws Exception {
        Config limited = new Config();
        limited.enforceDemandEquality = true;
        limited.threads = 1;
        limited.timeLimitSeconds = 60;
        limited.maxBendersIter = 4;
        limited.tol = 1e-7;
        double[] probability = {1.0, 1.0, 1.0};
        WassersteinBoxInput input = new WassersteinBoxInput(
                params, demand, probability, upper, scale, 0.4);
        Reference reference = enumerateReference(input, limited);
        ContextualWassersteinBoxSolver.Result result =
                new ContextualWassersteinBoxSolver().solve(
                        data(params, demand, probability), limited,
                        upper, scale, input.radius);
        double fixedY = fixedObjective(input, limited, result.solution.y);
        if (result.solution.certifiedOptimal
                || result.solution.objValue > 10.1
                || result.solution.objValue + 1e-7 < fixedY
                || result.solution.objValue + 1e-7 < reference.objective) {
            throw new IllegalStateException(
                    "Iteration-limited incumbent regression failed: returned="
                            + result.solution.objValue + ", fixedY=" + fixedY
                            + ", reference=" + reference.objective
                            + ", certified=" + result.solution.certifiedOptimal);
        }
        return new IterationLimitGate(result.solution.objValue, fixedY,
                reference.objective, result.solution.certifiedOptimal);
    }

    private static void checkRejectedInputs(ProcurementParams params,
                                            double[][] demand,
                                            double[] upper,
                                            double[] scale) {
        expectRejected(() -> new WassersteinBoxInput(params, demand,
                new double[]{0.0, 0.0, 0.0}, upper, scale, 0.1));
        expectRejected(() -> new WassersteinBoxInput(params, demand,
                new double[]{1.0, 1.0, 1.0}, upper, scale, -0.1));
        double[][] outside = {{9.0, 3.0}, {6.0, 2.0}, {5.0, 5.0}};
        expectRejected(() -> new WassersteinBoxInput(params, outside,
                new double[]{1.0, 1.0, 1.0}, upper, scale, 0.1));
    }

    private static List<String> randomizedChecks(Config config) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("seed,method,objective,reference,absolute_difference,certified");
        for (int seed = 0; seed < 8; seed++) {
            Random random = new Random(20260823L + seed);
            ProcurementParams params = randomParams(random);
            double[] upper = {8.0 + random.nextDouble(), 8.0 + random.nextDouble()};
            double[] scale = {1.5 + random.nextDouble(), 1.5 + random.nextDouble()};
            double[][] demand = new double[4][2];
            for (int s = 0; s < demand.length; s++) {
                for (int j = 0; j < 2; j++) {
                    demand[s][j] = 1.0 + random.nextDouble() * (upper[j] - 1.0);
                }
            }
            double[] probability = seed % 2 == 0
                    ? new double[]{0.0, 1.0, 2.0, 4.0}
                    : new double[]{1.0, 2.0, 3.0, 4.0};
            WassersteinBoxInput input = new WassersteinBoxInput(params, demand,
                    probability, upper, scale, 0.15 + 0.05 * seed);
            Reference reference = enumerateReference(input, config);

            ContextualWassersteinBoxCcgSolver.Result ccg =
                    new ContextualWassersteinBoxCcgSolver().solve(input, config);
            check("RANDOM_CCG", input, config, reference, ccg.solution(), ccg.eta());
            rows.add(randomRow(seed, "CCG", ccg.solution(), reference.objective));

            ContextualWassersteinBoxCallbackMultiCutSolver.Result callback =
                    new ContextualWassersteinBoxCallbackMultiCutSolver().solve(input, config);
            check("RANDOM_CALLBACK_MULTI", input, config, reference,
                    callback.solution(), callback.eta());
            rows.add(randomRow(seed, "CALLBACK_MULTI_CUT",
                    callback.solution(), reference.objective));

            ContextualWassersteinBoxSingleCutBendersSolver.Result single =
                    new ContextualWassersteinBoxSingleCutBendersSolver().solve(input, config);
            check("RANDOM_SINGLE", input, config, reference,
                    single.solution(), single.eta());
            rows.add(randomRow(seed, "SINGLE_CUT", single.solution(), reference.objective));

            ContextualWassersteinBoxRegularizedSingleCutBendersSolver.Result regularized =
                    new ContextualWassersteinBoxRegularizedSingleCutBendersSolver()
                            .solve(input, config, 0.5);
            check("RANDOM_REGULARIZED", input, config, reference,
                    regularized.solution(), regularized.eta());
            rows.add(randomRow(seed, "REGULARIZED_SINGLE_CUT",
                    regularized.solution(), reference.objective));

            ContextualWassersteinBoxRegularizedMultiCutBendersSolver.Result regularizedMulti =
                    new ContextualWassersteinBoxRegularizedMultiCutBendersSolver()
                            .solve(input, config, 0.5);
            check("RANDOM_REGULARIZED_MULTI", input, config, reference,
                    regularizedMulti.solution(), regularizedMulti.eta());
            rows.add(randomRow(seed, "REGULARIZED_MULTI_CUT",
                    regularizedMulti.solution(), reference.objective));

            ContextualWassersteinBoxSolver.Result dual =
                    new ContextualWassersteinBoxSolver().solve(
                            data(params, demand, probability), config,
                            upper, scale, input.radius);
            check("RANDOM_DUAL_VERTEX", input, config, reference,
                    dual.solution, dual.eta);
            rows.add(randomRow(seed, "DUAL_VERTEX", dual.solution, reference.objective));
        }
        return rows;
    }

    private static ProcurementParams randomParams(Random random) {
        double[] spot = {4.0 + random.nextDouble(), 4.0 + random.nextDouble()};
        double[] mqc = new double[3];
        double[] penalty = new double[3];
        double[][] laneCap = new double[3][2];
        double[][] rate = new double[3][2];
        double[] totalCap = new double[3];
        for (int i = 0; i < 3; i++) {
            mqc[i] = 2.0 + random.nextDouble() * 2.0;
            penalty[i] = 0.5 + random.nextDouble();
            for (int j = 0; j < 2; j++) {
                laneCap[i][j] = 3.0 + random.nextDouble() * 4.0;
                rate[i][j] = penalty[i] + 0.25 + random.nextDouble() * 1.5;
                totalCap[i] += laneCap[i][j];
            }
            totalCap[i] *= 0.8;
        }
        return new ProcurementParams(List.of("c0", "c1", "c2"), 2,
                spot, mqc, penalty, laneCap, rate,
                new boolean[][]{{true, true}, {true, true}, {true, true}},
                1, 2);
    }

    private static String randomRow(int seed,
                                    String method,
                                    Solution solution,
                                    double reference) {
        return String.format(Locale.US, "%d,%s,%.12f,%.12f,%.12g,%s",
                seed, method, solution.objValue, reference,
                Math.abs(solution.objValue - reference), solution.certifiedOptimal);
    }

    private static int integerPower(int base, int exponent) {
        int value = 1;
        for (int index = 0; index < exponent; index++) value *= base;
        return value;
    }

    private static void expectRejected(ThrowingRunnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        } catch (Exception other) {
            throw new IllegalStateException("Unexpected rejection type", other);
        }
        throw new IllegalStateException("Invalid Wasserstein input was accepted.");
    }

    private static Data data(ProcurementParams params,
                             double[][] demand,
                             double[] probability) {
        List<Sample> samples = new ArrayList<>();
        List<String> lanes = new ArrayList<>();
        for (int j = 0; j < params.J; j++) lanes.add("j" + j);
        for (int s = 0; s < demand.length; s++) {
            PeriodData period = new PeriodData(s, null, null, demand[s].clone(),
                    0, 0.0, 0.0, 0.0);
            samples.add(new Sample(s, period,
                    new CovariateVector(new double[0]), probability[s]));
        }
        return new Data(lanes, samples,
                new CovariateVector(new double[0]), params);
    }

    private static String binary(double[] y) {
        return Arrays.stream(y)
                .mapToInt(value -> value > 0.5 ? 1 : 0)
                .mapToObj(Integer::toString)
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private record Case(String name, double[] probability, double radius) {
    }

    private record Reference(double objective, double eta, double[] y) {
        private Reference {
            y = y.clone();
        }
    }

    private record FixedResult(double objective, double eta) {
    }

    private record LowerEndpointGate(double exactObjective, double upperOnlyObjective) {
    }

    private record IterationLimitGate(double returnedObjective,
                                      double fixedYObjective,
                                      double referenceObjective,
                                      boolean certified) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
