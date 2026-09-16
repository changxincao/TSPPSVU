package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Model.ExactMeanMadDROSolver;
import Model.ExactPartialMomentDROSolver;
import Model.SecondStageEvaluator;
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

/** Independent toy gates for exact Mean-MAD and marginal-variance DRO. */
public final class TRBReviewerMomentAmbiguityVerification {
    private static final double TOLERANCE = 2e-5;

    private TRBReviewerMomentAmbiguityVerification() {
    }

    /** Usage: {@code <empty-output-dir>}. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: <empty-output-dir>");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        requireEmpty(root);
        Files.createDirectories(root);

        ProcurementParams params = params();
        Config config = config();
        double[] lower = {0.0, 0.0};
        double[] mean = {3.0, 4.0};
        double[] mad = {2.0, 2.0};
        double[] upper = {6.0, 8.0};

        ExactMeanMadDROSolver.Result exactMad = new ExactMeanMadDROSolver().solve(
                params, lower, mean, mad, upper, config);
        Reference madReference = enumerateMeanMadReference(
                params, lower, mean, mad, upper, config);
        double madDifference = Math.abs(
                exactMad.solution().objValue - madReference.objective);
        if (!exactMad.solution().certifiedOptimal || madDifference > TOLERANCE) {
            throw new IllegalStateException(
                    "Mean-MAD exact/reference mismatch: exact="
                            + exactMad.solution().objValue + ", reference="
                            + madReference.objective + ", difference=" + madDifference);
        }
        validateDistribution(exactMad.distribution(), mean);
        double maximumSupermodularityViolation = supermodularityGate(
                params, lower, mean, upper);
        if (maximumSupermodularityViolation > TOLERANCE) {
            throw new IllegalStateException(
                    "Recourse supermodularity gate failed: "
                            + maximumSupermodularityViolation);
        }

        double[] marginalVariance = {2.0, 3.0};
        ExactPartialMomentDROSolver.Result marginalOnly =
                new ExactPartialMomentDROSolver().solve(
                        params, mean, marginalVariance, upper, config);
        ExactPartialMomentDROSolver.Result withAggregate =
                new ExactPartialMomentDROSolver().solve(
                        params, mean, marginalVariance, 4.0, upper, config);
        if (!marginalOnly.solution.certifiedOptimal
                || !withAggregate.solution.certifiedOptimal) {
            throw new IllegalStateException(
                    "Partial-moment toy gate was not certified: marginal="
                            + marginalOnly.solution.solverStatus + ", aggregate="
                            + withAggregate.solution.solverStatus);
        }
        if (marginalOnly.solution.objValue + TOLERANCE
                < withAggregate.solution.objValue) {
            throw new IllegalStateException(
                    "Removing the aggregate constraint reduced the robust objective.");
        }
        if (Math.abs(marginalOnly.aggregateVariancePrice) > 1e-10
                || Math.abs(marginalOnly.aggregateVarianceViolation) > 1e-10) {
            throw new IllegalStateException(
                    "Marginal-only model retained an aggregate-moment effect.");
        }
        ExactPartialMomentDROSolver.Result negativeSlopeGate =
                new ExactPartialMomentDROSolver().solve(
                        negativeSlopeParams(), new double[]{5.0},
                        new double[]{2.0}, new double[]{10.0}, config);
        if (!negativeSlopeGate.solution.certifiedOptimal) {
            throw new IllegalStateException(
                    "h>r negative-demand-slope moment gate was not certified.");
        }

        List<String> summary = List.of(
                "status=PASSED",
                String.format(Locale.US, "meanMadExactObjective=%.12f",
                        exactMad.solution().objValue),
                String.format(Locale.US, "meanMadGridReference=%.12f",
                        madReference.objective),
                String.format(Locale.US, "meanMadAbsoluteDifference=%.12g",
                        madDifference),
                "meanMadSelected=" + Arrays.toString(exactMad.solution().y),
                "meanMadReferenceSelected=" + Arrays.toString(madReference.y),
                "meanMadAtoms=" + exactMad.distribution().probability.length,
                String.format(Locale.US, "maximumSupermodularityViolation=%.12g",
                        maximumSupermodularityViolation),
                String.format(Locale.US, "marginalVarianceObjective=%.12f",
                        marginalOnly.solution.objValue),
                String.format(Locale.US, "marginalVarianceGeneratedSupportGap=%.12g",
                        marginalOnly.generatedSupportRelativeGap),
                String.format(Locale.US, "aggregateVarianceObjective=%.12f",
                        withAggregate.solution.objValue),
                String.format(Locale.US, "aggregateVarianceGeneratedSupportGap=%.12g",
                        withAggregate.generatedSupportRelativeGap),
                String.format(Locale.US, "negativeSlopeMomentObjective=%.12f",
                        negativeSlopeGate.solution.objValue));
        Files.write(root.resolve("verification.txt"), summary, StandardCharsets.UTF_8);
        Files.write(root.resolve("mean_mad_atoms.csv"), atomRows(exactMad.distribution()),
                StandardCharsets.UTF_8);
        System.out.println("MOMENT_AMBIGUITY_VALIDATION=PASSED");
        summary.forEach(System.out::println);
    }

    private static Reference enumerateMeanMadReference(
            ProcurementParams params,
            double[] lower,
            double[] mean,
            double[] mad,
            double[] upper,
            Config config) throws Exception {
        double best = Double.POSITIVE_INFINITY;
        double[] bestY = null;
        int selections = 1 << params.I;
        for (int mask = 0; mask < selections; mask++) {
            int count = Integer.bitCount(mask);
            if (count < params.alpha || count > params.beta) continue;
            double[] y = new double[params.I];
            for (int i = 0; i < params.I; i++) y[i] = (mask >> i) & 1;
            double value = fixedSelectionMeanMad(
                    params, y, lower, mean, mad, upper, config);
            if (value < best) {
                best = value;
                bestY = y;
            }
        }
        return new Reference(best, bestY);
    }

    private static double fixedSelectionMeanMad(
            ProcurementParams params,
            double[] y,
            double[] lower,
            double[] mean,
            double[] mad,
            double[] upper,
            Config config) throws Exception {
        List<double[]> grid = endpointGrid(lower, mean, upper);
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, config.threads);
            IloNumVar[] probability = cplex.numVarArray(
                    grid.size(), 0.0, Double.POSITIVE_INFINITY);
            IloLinearNumExpr sum = cplex.linearNumExpr();
            for (IloNumVar variable : probability) sum.addTerm(1.0, variable);
            cplex.addEq(sum, 1.0);
            for (int j = 0; j < mean.length; j++) {
                IloLinearNumExpr firstMoment = cplex.linearNumExpr();
                IloLinearNumExpr absoluteDeviation = cplex.linearNumExpr();
                for (int k = 0; k < grid.size(); k++) {
                    firstMoment.addTerm(grid.get(k)[j], probability[k]);
                    absoluteDeviation.addTerm(
                            Math.abs(grid.get(k)[j] - mean[j]), probability[k]);
                }
                cplex.addEq(firstMoment, mean[j]);
                cplex.addLe(absoluteDeviation, mad[j]);
            }
            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int k = 0; k < grid.size(); k++) {
                double recourse = SecondStageEvaluator.evaluate(
                        params, y, grid.get(k), true).objective;
                objective.addTerm(recourse, probability[k]);
            }
            cplex.addMaximize(objective);
            if (!cplex.solve() || cplex.getStatus() != IloCplex.Status.Optimal) {
                throw new IllegalStateException("Mean-MAD reference LP failed.");
            }
            return cplex.getObjValue();
        }
    }

    private static double supermodularityGate(ProcurementParams params,
                                               double[] lower,
                                               double[] mean,
                                               double[] upper) throws Exception {
        List<double[]> grid = endpointGrid(lower, mean, upper);
        double maximumViolation = 0.0;
        int selections = 1 << params.I;
        for (int mask = 0; mask < selections; mask++) {
            int count = Integer.bitCount(mask);
            if (count < params.alpha || count > params.beta) continue;
            double[] y = new double[params.I];
            for (int i = 0; i < params.I; i++) y[i] = (mask >> i) & 1;
            for (double[] left : grid) {
                for (double[] right : grid) {
                    double[] meet = new double[params.J];
                    double[] join = new double[params.J];
                    for (int j = 0; j < params.J; j++) {
                        meet[j] = Math.min(left[j], right[j]);
                        join[j] = Math.max(left[j], right[j]);
                    }
                    double lhs = recourse(params, y, left) + recourse(params, y, right);
                    double rhs = recourse(params, y, meet) + recourse(params, y, join);
                    maximumViolation = Math.max(maximumViolation, lhs - rhs);
                }
            }
        }
        return maximumViolation;
    }

    private static double recourse(ProcurementParams params,
                                   double[] y,
                                   double[] demand) throws Exception {
        return SecondStageEvaluator.evaluate(params, y, demand, true).objective;
    }

    private static List<double[]> endpointGrid(double[] lower,
                                               double[] mean,
                                               double[] upper) {
        int points = 1;
        for (int ignored = 0; ignored < mean.length; ignored++) points *= 3;
        List<double[]> grid = new ArrayList<>(points);
        for (int mask = 0; mask < points; mask++) {
            int code = mask;
            double[] point = new double[mean.length];
            for (int j = 0; j < mean.length; j++) {
                int endpoint = code % 3;
                code /= 3;
                point[j] = endpoint == 0 ? lower[j]
                        : endpoint == 1 ? mean[j] : upper[j];
            }
            grid.add(point);
        }
        return grid;
    }

    private static void validateDistribution(
            ExactMeanMadDROSolver.Distribution distribution,
            double[] targetMean) {
        double probability = Arrays.stream(distribution.probability).sum();
        if (Math.abs(probability - 1.0) > 1e-9
                || distribution.probability.length > 2 * targetMean.length + 1) {
            throw new IllegalStateException("Invalid Mean-MAD atom count/probabilities.");
        }
        for (int j = 0; j < targetMean.length; j++) {
            if (Math.abs(distribution.attainedMean[j] - targetMean[j]) > 1e-9
                    || Math.abs(distribution.attainedMad[j]
                            - distribution.effectiveMad[j]) > 1e-9) {
                throw new IllegalStateException("Mean-MAD marginal mismatch at lane " + j);
            }
        }
    }

    private static List<String> atomRows(
            ExactMeanMadDROSolver.Distribution distribution) {
        List<String> rows = new ArrayList<>();
        rows.add("atom,probability,demand");
        for (int k = 0; k < distribution.probability.length; k++) {
            rows.add(String.format(Locale.US, "%d,%.12f,\"%s\"", k,
                    distribution.probability[k],
                    Arrays.toString(distribution.demand[k])));
        }
        return rows;
    }

    private static ProcurementParams params() {
        return new ProcurementParams(
                List.of("c0", "c1", "c2"), 2,
                new double[]{4.2, 4.0},
                new double[]{3.0, 3.5, 2.5},
                new double[]{0.8, 0.7, 0.9},
                new double[][]{{6.0, 2.0}, {2.0, 6.0}, {4.0, 4.0}},
                new double[][]{{1.0, 1.7}, {1.6, 1.0}, {1.25, 1.3}},
                new boolean[][]{{true, true}, {true, true}, {true, true}},
                1, 2);
    }

    private static ProcurementParams negativeSlopeParams() {
        return new ProcurementParams(
                List.of("c0"), 1,
                new double[]{10.0}, new double[]{5.0}, new double[]{8.0},
                new double[][]{{10.0}}, new double[][]{{1.0}},
                new boolean[][]{{true}}, 1, 1);
    }

    private static Config config() {
        Config config = new Config();
        config.enforceDemandEquality = true;
        config.threads = 1;
        config.timeLimitSeconds = 120;
        config.maxBendersIter = 200;
        config.tol = 1e-6;
        return config;
    }

    private static void requireEmpty(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output directory must be empty: " + root);
            }
        }
    }

    private record Reference(double objective, double[] y) {
        private Reference {
            y = y.clone();
        }
    }
}
