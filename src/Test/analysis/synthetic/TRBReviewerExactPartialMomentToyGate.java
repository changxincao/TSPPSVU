package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Model.ExactPartialMomentDROSolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Exactness gate for the unrestricted-recourse partial-moment solver. */
public final class TRBReviewerExactPartialMomentToyGate {
    private TRBReviewerExactPartialMomentToyGate() {
    }

    /** Usage: {@code <empty-output-dir> [time-limit-sec]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException(
                    "Usage: <empty-output-dir> [time-limit-sec]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(root)) {
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Output directory must be empty: " + root);
                }
            }
        }
        Files.createDirectories(root);

        ProcurementParams params = new ProcurementParams(
                List.of("carrier_1", "carrier_2"),
                2,
                new double[]{3.0, 3.0},
                new double[]{3.0, 3.0},
                new double[]{1.0, 0.9},
                new double[][]{{6.0, 4.0}, {4.0, 6.0}},
                new double[][]{{1.0, 1.6}, {1.5, 0.9}},
                new boolean[][]{{true, true}, {true, true}},
                1, 1);
        double[] mean = {5.0, 10.0 / 3.0};
        double[] marginalVariance = {1.0, 7.0 / 3.0};
        double aggregateVariance = 8.0 / 3.0;
        double[] upper = {8.0, 8.0};

        Config config = new Config();
        config.enforceDemandEquality = true;
        config.threads = 1;
        config.timeLimitSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 120;
        config.maxBendersIter = 100;
        config.tol = 1e-6;

        ExactPartialMomentDROSolver.Result result =
                new ExactPartialMomentDROSolver().solve(params, mean,
                        marginalVariance, aggregateVariance, upper, config);
        if (!result.solution.certifiedOptimal) {
            throw new IllegalStateException("Toy partial-moment gate was not certified.");
        }
        String json = String.format(Locale.US,
                "{\n"
                        + "  \"model\": \"exact_partial_moment_unrestricted_recourse\",\n"
                        + "  \"objective\": %.12f,\n"
                        + "  \"selected\": \"%s\",\n"
                        + "  \"status\": \"%s\",\n"
                        + "  \"relative_gap\": %.12g,\n"
                        + "  \"cuts\": %d,\n"
                        + "  \"separation_solves\": %d,\n"
                        + "  \"final_violation\": %.12g,\n"
                        + "  \"separation_status\": \"%s\",\n"
                        + "  \"separation_global_gap\": %.12g,\n"
                        + "  \"generated_support_lower_bound\": %.12f,\n"
                        + "  \"generated_support_relative_gap\": %.12g,\n"
                        + "  \"generated_support_points\": %d,\n"
                        + "  \"positive_probability_atoms\": %d,\n"
                        + "  \"probability_residual\": %.12g,\n"
                        + "  \"maximum_mean_residual\": %.12g,\n"
                        + "  \"maximum_marginal_variance_violation\": %.12g,\n"
                        + "  \"aggregate_variance_violation\": %.12g,\n"
                        + "  \"worst_demand\": \"%s\"\n"
                        + "}\n",
                result.solution.objValue,
                Arrays.toString(result.solution.y),
                result.solution.solverStatus,
                result.solution.relativeGap,
                result.cuts,
                result.separationSolves,
                result.finalViolation,
                result.separationStatus,
                result.separationGlobalGap,
                result.generatedSupportLowerBound,
                result.generatedSupportRelativeGap,
                result.generatedSupportPoints,
                result.positiveProbabilityAtoms,
                result.probabilityResidual,
                result.maximumMeanResidual,
                result.maximumMarginalVarianceViolation,
                result.aggregateVarianceViolation,
                Arrays.toString(result.worstDemand));
        Files.writeString(root.resolve("result.json"), json, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("configuration.txt"),
                "purpose=exactness gate; not manuscript evidence\n"
                        + "recourse=unrestricted LP\n"
                        + "support=box [0,8]^2\n"
                        + "mean=" + Arrays.toString(mean) + "\n"
                        + "marginalVarianceUpper=" + Arrays.toString(marginalVariance) + "\n"
                        + "aggregateVarianceUpper=" + aggregateVariance + "\n",
                StandardCharsets.UTF_8);
        System.out.print(json);
    }
}
