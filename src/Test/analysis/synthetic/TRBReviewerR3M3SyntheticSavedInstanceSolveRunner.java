package Test.analysis.synthetic;

import Helper.basicHelper.Config;
import Model.RCSAASolverVariant;
import Model.SolveMode;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticInstanceIO.StoredInstance;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.Properties;

/**
 * Command-line connection between a saved Reviewer 3 Comment 3 instance and
 * the current main solvers.
 *
 * <p><strong>Input.</strong> One immutable instance directory, one method, and
 * already selected {@code C_h}/{@code lambda}. <strong>Operation.</strong> Load
 * the exact saved training/query/OOS/procurement data, enforce the corrected
 * demand equality, solve once, and evaluate the fixed decision on every
 * conditional OOS draw. <strong>Output.</strong> The paired cost CSV and method
 * summary written by {@link TRBReviewerR3M3SyntheticMainSolve}.</p>
 *
 * <p>This runner intentionally performs no hyperparameter search. Formal
 * multi-method experiments must call it with parameters selected without using
 * the saved OOS outcomes.</p>
 */
public final class TRBReviewerR3M3SyntheticSavedInstanceSolveRunner {

    private TRBReviewerR3M3SyntheticSavedInstanceSolveRunner() {
    }

    /** Supported executable mappings to the project's current solver classes. */
    public enum Method {
        D,
        SAA,
        KNN_SAA,
        CSAA,
        CSAA_MATCHED_RCSAA_COUNT,
        DRO,
        RCSAA_ENUMERATE,
        RCSAA_LBBD,
        RCSAA_LBBD_SEARCH
    }

    /**
     * Usage:
     * {@code <instance-dir> <method> <output-dir> [C_h] [lambda] [threads]
     * [time-limit-sec] [knn-neighbors]}.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 8) {
            throw new IllegalArgumentException(
                    "Usage: <instance-dir> <D|SAA|KNN_SAA|CSAA|DRO|RCSAA_ENUMERATE|RCSAA_LBBD|RCSAA_LBBD_SEARCH> "
                            + "<output-dir> [C_h] [lambda] [threads] [time-limit-sec] [knn-neighbors]");
        }

        Path instanceDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Method method = Method.valueOf(args[1].trim().toUpperCase());
        Path outputDirectory = Path.of(args[2]).toAbsolutePath().normalize();
        double cH = args.length > 3 ? Double.parseDouble(args[3]) : 1.0;
        double lambda = args.length > 4 ? Double.parseDouble(args[4]) : 1.0;
        int threads = args.length > 5 ? Integer.parseInt(args[5]) : 4;
        int timeLimitSeconds = args.length > 6 ? Integer.parseInt(args[6]) : 3600;
        int knnNeighbors = args.length > 7 ? Integer.parseInt(args[7]) : 0;

        runSaved(instanceDirectory, method, outputDirectory,
                cH, lambda, threads, timeLimitSeconds, knnNeighbors);
    }

    public static TRBReviewerR3M3SyntheticMainSolve.Result runSaved(
            Path instanceDirectory,
            Method method,
            Path outputRoot,
            double cH,
            double lambda,
            int threads,
            int timeLimitSeconds) throws Exception {
        return runSaved(instanceDirectory, method, outputRoot, cH, lambda,
                threads, timeLimitSeconds, 0);
    }

    public static TRBReviewerR3M3SyntheticMainSolve.Result runSaved(
            Path instanceDirectory,
            Method method,
            Path outputRoot,
            double cH,
            double lambda,
            int threads,
            int timeLimitSeconds,
            int knnNeighbors) throws Exception {
        StoredInstance stored = TRBReviewerR3M3SyntheticInstanceIO.load(instanceDirectory);
        if (!stored.hasProcurementParams()) {
            throw new IllegalArgumentException("Saved instance contains no procurement parameters.");
        }
        Config config = config(method, stored.settings.observedLagPeriods,
                cH, lambda, threads, timeLimitSeconds, knnNeighbors);
        Path methodOutput = outputRoot.resolve(method.name());
        TRBReviewerR3M3SyntheticMainSolve.Result result = TRBReviewerR3M3SyntheticMainSolve.run(
                method.name(), stored.demandData, stored.procurementParams,
                config, methodOutput);
        writeRunConfiguration(methodOutput.resolve("run_config.properties"),
                instanceDirectory, method, config);
        return result;
    }

    public static void validateCompletedRun(Path instanceDirectory,
                                            Method method,
                                            Path outputRoot,
                                            double cH,
                                            double lambda,
                                            int threads,
                                            int timeLimitSeconds) throws Exception {
        validateCompletedRun(instanceDirectory, method, outputRoot, cH, lambda,
                threads, timeLimitSeconds, 0);
    }

    public static void validateCompletedRun(Path instanceDirectory,
                                            Method method,
                                            Path outputRoot,
                                            double cH,
                                            double lambda,
                                            int threads,
                                            int timeLimitSeconds,
                                            int knnNeighbors) throws Exception {
        StoredInstance stored = TRBReviewerR3M3SyntheticInstanceIO.load(instanceDirectory);
        Config expected = config(method, stored.settings.observedLagPeriods,
                cH, lambda, threads, timeLimitSeconds, knnNeighbors);
        Path methodOutput = outputRoot.resolve(method.name());
        Path summary = methodOutput.resolve("solve_summary.csv");
        Path runConfig = methodOutput.resolve("run_config.properties");
        if (!Files.isRegularFile(summary) || !Files.isRegularFile(runConfig)) {
            throw new IllegalArgumentException(
                    "Existing result is incomplete (summary/run_config missing): " + methodOutput);
        }
        Properties actual = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(runConfig, StandardCharsets.UTF_8)) {
            actual.load(reader);
        }
        require(actual, "instanceDirectory", instanceDirectory.toAbsolutePath().normalize().toString(), runConfig);
        require(actual, "method", method.name(), runConfig);
        require(actual, "solveMode", expected.solveMode.name(), runConfig);
        require(actual, "solverVariant", expected.rcsaaSolverVariant == null
                ? "" : expected.rcsaaSolverVariant.name(), runConfig);
        require(actual, "observedLagPeriods", Integer.toString(expected.k1LagPeriods), runConfig);
        require(actual, "C_h", Double.toString(cH), runConfig);
        require(actual, "lambda", Double.toString(lambda), runConfig);
        require(actual, "threads", Integer.toString(threads), runConfig);
        require(actual, "timeLimitSeconds", Integer.toString(timeLimitSeconds), runConfig);
        require(actual, "knnNeighbors", Integer.toString(expected.knnNeighbors), runConfig);
        require(actual, "enforceDemandEquality", "true", runConfig);
    }

    private static void writeRunConfiguration(Path path,
                                              Path instanceDirectory,
                                              Method method,
                                              Config config) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("instanceDirectory", instanceDirectory.toAbsolutePath().normalize().toString());
        properties.setProperty("method", method.name());
        properties.setProperty("solveMode", config.solveMode.name());
        properties.setProperty("solverVariant", config.rcsaaSolverVariant == null
                ? "" : config.rcsaaSolverVariant.name());
        properties.setProperty("observedLagPeriods", Integer.toString(config.k1LagPeriods));
        properties.setProperty("C_h", Double.toString(config.C_h));
        properties.setProperty("lambda", Double.toString(config.lambda));
        properties.setProperty("threads", Integer.toString(config.threads));
        properties.setProperty("timeLimitSeconds", Integer.toString(config.timeLimitSeconds));
        properties.setProperty("knnNeighbors", Integer.toString(config.knnNeighbors));
        properties.setProperty("enforceDemandEquality", Boolean.toString(config.enforceDemandEquality));
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, "Formal synthetic solve configuration");
        }
    }

    private static void require(Properties actual, String key, String expected, Path source) {
        String value = actual.getProperty(key);
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(
                    "Existing result configuration mismatch for " + key + " in " + source
                            + ": expected=" + expected + ", actual=" + value);
        }
    }

    public static Config config(Method method,
                                 int observedLagPeriods,
                                 double cH,
                                 double lambda,
                                 int threads,
                                 int timeLimitSeconds) {
        return config(method, observedLagPeriods, cH, lambda, threads,
                timeLimitSeconds, 0);
    }

    public static Config config(Method method,
                                 int observedLagPeriods,
                                 double cH,
                                 double lambda,
                                 int threads,
                                 int timeLimitSeconds,
                                 int knnNeighbors) {
        if (!(cH > 0.0) || !Double.isFinite(cH)) {
            throw new IllegalArgumentException("C_h must be finite and positive.");
        }
        if (!(lambda > 0.0) || !Double.isFinite(lambda)) {
            throw new IllegalArgumentException("lambda must be finite and positive.");
        }
        if (threads <= 0 || timeLimitSeconds <= 0) {
            throw new IllegalArgumentException("threads and time limit must be positive.");
        }
        if (method == Method.KNN_SAA) {
            if (knnNeighbors <= 0) {
                throw new IllegalArgumentException("KNN_SAA requires a positive knnNeighbors value.");
            }
        } else if (knnNeighbors != 0) {
            throw new IllegalArgumentException("knnNeighbors is only valid for KNN_SAA.");
        }

        Config config = new Config();
        config.k1LagPeriods = observedLagPeriods;
        config.standardizeTheta = true;
        config.C_h = cH;
        config.lambda = lambda;
        config.threads = threads;
        config.timeLimitSeconds = timeLimitSeconds;
        config.enforceDemandEquality = true;
        config.knnNeighbors = knnNeighbors;

        switch (method) {
            case D -> {
                config.solveMode = SolveMode.MeanDeterministic;
                config.rcsaaSolverVariant = null;
            }
            case SAA -> {
                config.solveMode = SolveMode.SAA;
                config.rcsaaSolverVariant = null;
            }
            case KNN_SAA -> {
                config.solveMode = SolveMode.CSAA;
                config.C_h = Double.NaN;
                config.rcsaaSolverVariant = null;
            }
            case CSAA, CSAA_MATCHED_RCSAA_COUNT -> {
                config.solveMode = SolveMode.CSAA;
                config.rcsaaSolverVariant = null;
            }
            case DRO -> {
                config.solveMode = SolveMode.RCSAA;
                config.rcsaaSolverVariant = RCSAASolverVariant.DRO_EXTENSIVE;
            }
            case RCSAA_ENUMERATE -> {
                config.solveMode = SolveMode.RCSAA;
                config.rcsaaSolverVariant = RCSAASolverVariant.ENUMERATE_EXACT;
            }
            case RCSAA_LBBD -> {
                config.solveMode = SolveMode.RCSAA;
                config.rcsaaSolverVariant = RCSAASolverVariant.LBBD_PRIMAL_EXACT;
            }
            case RCSAA_LBBD_SEARCH -> {
                config.solveMode = SolveMode.RCSAA;
                config.rcsaaSolverVariant = RCSAASolverVariant.LBBD_PRIMAL_SEARCH;
            }
        }
        return config;
    }
}
