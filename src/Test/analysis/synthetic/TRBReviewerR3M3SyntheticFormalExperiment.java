package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Model.DROModel;
import Model.RCSAASolverVariant;
import Model.SAAModel;
import Model.SecondStageEvaluator;
import Model.Solution;
import Model.SolveMode;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticInstanceIO.StoredInstance;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reviewer 3, Comment 3 formal experiment driver.
 *
 * <p>Hyperparameters are selected on calibration replications whose seeds are
 * disjoint from all evaluation replications. The selected k/C_h/lambda are then
 * frozen for one distribution/volatility cell. Every evaluation seed saves one
 * immutable instance and all methods use its identical training, query and OOS
 * draws. No evaluation OOS value enters parameter selection.</p>
 */
public final class TRBReviewerR3M3SyntheticFormalExperiment {

    private static final int[] K_GRID = {1, 2, 3};
    private static final double[] C_GRID = {0.1, 0.5, 1, 3, 5, 10, 30, 50, 100};
    private static final double[] LAMBDA_GRID = {0.01, 0.05, 0.1, 1, 5, 10, 50, 100};
    private static final double[] CV_GRID = {0.15, 0.30};
    private static final long CALIBRATION_SEED_OFFSET = 1_000_000L;
    private static final long PROCUREMENT_SEED = 20_260_808L;
    private static final int I = 10;
    private static final int J = 23;
    private static final int S = 50;
    private static final int CALIBRATION_CONFIG_VERSION = 1;

    private TRBReviewerR3M3SyntheticFormalExperiment() {
    }

    /**
     * Usage: {@code <output-root> [evaluation-seeds] [calibration-seeds]
     * [evaluation-oos-draws] [calibration-oos-draws] [threads] [time-limit-sec]}.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 7) {
            throw new IllegalArgumentException(
                    "Usage: <output-root> [evaluation-seeds] [calibration-seeds] "
                            + "[evaluation-oos-draws] [calibration-oos-draws] [threads] [time-limit-sec]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        int evaluationSeeds = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int calibrationSeeds = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        int evaluationDraws = args.length > 3 ? Integer.parseInt(args[3]) : 1000;
        int calibrationDraws = args.length > 4 ? Integer.parseInt(args[4]) : 200;
        int threads = args.length > 5 ? Integer.parseInt(args[5]) : 4;
        int timeLimitSeconds = args.length > 6 ? Integer.parseInt(args[6]) : 3600;
        validatePositive(evaluationSeeds, calibrationSeeds, evaluationDraws,
                calibrationDraws, threads, timeLimitSeconds);

        Files.createDirectories(root);
        for (InnovationDistribution distribution : InnovationDistribution.values()) {
            for (double cv : CV_GRID) {
                runCell(root, distribution, cv, evaluationSeeds, calibrationSeeds,
                        evaluationDraws, calibrationDraws, threads, timeLimitSeconds);
            }
        }
        TRBReviewerR3M3SyntheticPairedStatistics.summarize(root, root.resolve("statistics"));
    }

    private static void runCell(Path root,
                                InnovationDistribution distribution,
                                double cv,
                                int evaluationSeeds,
                                int calibrationSeeds,
                                int evaluationDraws,
                                int calibrationDraws,
                                int threads,
                                int timeLimitSeconds) throws Exception {
        String cellName = distribution.name().toLowerCase(Locale.ROOT)
                + "_cv" + String.format(Locale.US, "%03d", Math.round(cv * 100));
        Path cellRoot = root.resolve(cellName);
        Files.createDirectories(cellRoot);

        Selection selection = selectOnIndependentReplications(
                cellRoot.resolve("calibration"), distribution, cv,
                calibrationSeeds, calibrationDraws, threads, timeLimitSeconds);

        for (int seed = 1; seed <= evaluationSeeds; seed++) {
            Path replicationRoot = cellRoot.resolve(String.format(Locale.US, "seed_%04d", seed));
            Path instanceDirectory = replicationRoot.resolve("instance");
            if (!Files.isRegularFile(instanceDirectory.resolve("manifest.properties"))) {
                Settings settings = settings(distribution, cv, seed,
                        selection.k, evaluationDraws);
                TRBReviewerR3M3SyntheticInstanceBuilder.buildAndSave(
                        instanceDirectory, settings, I, PROCUREMENT_SEED);
            }
            validateStoredInstance(instanceDirectory, distribution, cv, seed,
                    selection.k, evaluationDraws);

            Path resultsRoot = replicationRoot.resolve("results");
            for (Method method : methods()) {
                Path summary = resultsRoot.resolve(method.name()).resolve("solve_summary.csv");
                if (Files.isRegularFile(summary)) {
                    TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.validateCompletedRun(
                            instanceDirectory, method, resultsRoot, selection.cH,
                            selection.lambda, threads, timeLimitSeconds);
                    continue;
                }
                try {
                    TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.runSaved(
                            instanceDirectory, method, resultsRoot,
                            selection.cH, selection.lambda, threads, timeLimitSeconds);
                } catch (Exception failure) {
                    appendFailure(cellRoot.resolve("failed_runs.csv"), seed, method, failure);
                }
            }
        }
    }

    private static void appendFailure(Path path,
                                      int seed,
                                      Method method,
                                      Exception failure) throws Exception {
        boolean writeHeader = !Files.exists(path);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (writeHeader) {
                writer.write("replicationSeed,method,errorType,errorMessage");
                writer.newLine();
            }
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            writer.write(String.format(Locale.US, "%d,%s,%s,%s%n",
                    seed, csv(method.name()), csv(failure.getClass().getSimpleName()),
                    csv(message.replace('\n', ' ').replace('\r', ' '))));
        }
    }

    private static Selection selectOnIndependentReplications(
            Path calibrationDirectory,
            InnovationDistribution distribution,
            double cv,
            int calibrationSeeds,
            int calibrationDraws,
            int threads,
            int timeLimitSeconds) throws Exception {
        Path selectedFile = calibrationDirectory.resolve("selected_parameters.csv");
        if (Files.isRegularFile(selectedFile)) {
            Selection saved = Selection.read(selectedFile);
            if (saved.calibrationSeeds != calibrationSeeds
                    || saved.calibrationDraws != calibrationDraws
                    || saved.distribution != distribution
                    || Double.doubleToLongBits(saved.cv) != Double.doubleToLongBits(cv)
                    || saved.threads != threads
                    || saved.timeLimitSeconds != timeLimitSeconds
                    || saved.configVersion != CALIBRATION_CONFIG_VERSION) {
                throw new IllegalArgumentException(
                        "Existing calibration selection uses a different experiment configuration: "
                                + selectedFile);
            }
            return saved;
        }
        Files.createDirectories(calibrationDirectory);

        List<Candidate> stage1 = new ArrayList<>();
        for (int k : K_GRID) {
            for (double cH : C_GRID) {
                stage1.add(evaluateCandidate(distribution, cv, calibrationSeeds,
                        calibrationDraws, k, cH, Double.NaN, Method.CSAA,
                        threads, timeLimitSeconds));
            }
        }
        Candidate bestStage1 = best(stage1);
        writeCandidates(calibrationDirectory.resolve("stage1_k_c_candidates.csv"), stage1);

        List<Candidate> stage2 = new ArrayList<>();
        for (double lambda : LAMBDA_GRID) {
            stage2.add(evaluateCandidate(distribution, cv, calibrationSeeds,
                    calibrationDraws, bestStage1.k, bestStage1.cH, lambda,
                    Method.RCSAA_LBBD, threads, timeLimitSeconds));
        }
        Candidate bestStage2 = best(stage2);
        writeCandidates(calibrationDirectory.resolve("stage2_lambda_candidates.csv"), stage2);

        Selection selection = new Selection(bestStage2.k, bestStage2.cH, bestStage2.lambda,
                calibrationSeeds, calibrationDraws, distribution, cv, threads,
                timeLimitSeconds, CALIBRATION_CONFIG_VERSION);
        selection.write(selectedFile);
        return selection;
    }

    private static Candidate evaluateCandidate(InnovationDistribution distribution,
                                               double cv,
                                               int calibrationSeeds,
                                               int calibrationDraws,
                                               int k,
                                               double cH,
                                               double lambda,
                                               Method method,
                                               int threads,
                                               int timeLimitSeconds) {
        double effectiveLambda = Double.isNaN(lambda) ? 1.0 : lambda;
        try {
            double score = calibrationScore(distribution, cv, calibrationSeeds,
                    calibrationDraws, k, cH, effectiveLambda,
                    method, threads, timeLimitSeconds);
            return new Candidate(k, cH, lambda, score, "OK");
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            return new Candidate(k, cH, lambda, Double.POSITIVE_INFINITY,
                    failure.getClass().getSimpleName() + ":" + message.replace('\n', ' ').replace('\r', ' '));
        }
    }

    private static void validateStoredInstance(Path instanceDirectory,
                                               InnovationDistribution distribution,
                                               double cv,
                                               long seed,
                                               int k,
                                               int oosDraws) throws Exception {
        StoredInstance stored = TRBReviewerR3M3SyntheticInstanceIO.load(instanceDirectory);
        Settings settings = stored.settings;
        boolean matches = settings.innovationDistribution == distribution
                && Double.doubleToLongBits(settings.innovationCv) == Double.doubleToLongBits(cv)
                && settings.replicationSeed == seed
                && settings.observedLagPeriods == k
                && settings.trainingSampleCount == S
                && settings.oosSampleCount == oosDraws
                && settings.laneCount == J
                && stored.procurementParams.I == I;
        if (!matches) {
            throw new IllegalArgumentException(
                    "Existing instance does not match the frozen cell: " + instanceDirectory);
        }
    }

    private static double calibrationScore(InnovationDistribution distribution,
                                           double cv,
                                           int calibrationSeeds,
                                           int calibrationDraws,
                                           int k,
                                           double cH,
                                           double lambda,
                                           Method method,
                                           int threads,
                                           int timeLimitSeconds) throws Exception {
        double sum = 0.0;
        for (int index = 1; index <= calibrationSeeds; index++) {
            long seed = CALIBRATION_SEED_OFFSET + index;
            Settings settings = settings(distribution, cv, seed, k, calibrationDraws);
            ReplicationData replication =
                    TRBReviewerR3M3IndependentPathDemandGenerator.generate(settings);
            ProcurementParams params = TRBReviewerSyntheticProcurementFactory
                    .generateScaleComparable(replication.baselineDemand, I, PROCUREMENT_SEED).params;
            Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                    method, k, cH, lambda, threads, timeLimitSeconds);
            TRBReviewerR3M3SyntheticSolveBridge.PreparedInput prepared =
                    TRBReviewerR3M3SyntheticSolveBridge.prepare(replication, params, config);

            Solution solution = config.solveMode == SolveMode.CSAA
                    ? new SAAModel().solve(prepared.solveData, config, null)
                    : new DROModel().solve(prepared.solveData, config);
            if (!solution.certifiedOptimal) {
                throw new IllegalStateException(
                        "Calibration candidate did not solve to certified optimality: method="
                                + method + ", k=" + k + ", C_h=" + cH + ", lambda=" + lambda
                                + ", seed=" + seed + ", status=" + solution.solverStatus
                                + ", gap=" + solution.relativeGap);
            }
            sum += meanOosCost(params, solution.y, prepared.oosSamples,
                    config.enforceDemandEquality);
        }
        return sum / calibrationSeeds;
    }

    private static double meanOosCost(ProcurementParams params,
                                      double[] y,
                                      List<Sample> oos,
                                      boolean equality) throws Exception {
        double sum = 0.0;
        for (Sample sample : oos) {
            sum += SecondStageEvaluator.evaluate(params, y, sample.demand(), equality).objective;
        }
        return sum / oos.size();
    }

    private static Settings settings(InnovationDistribution distribution,
                                     double cv,
                                     long seed,
                                     int k,
                                     int oosDraws) {
        Settings settings = new Settings();
        settings.laneCount = J;
        settings.trainingSampleCount = S;
        settings.oosSampleCount = oosDraws;
        settings.observedLagPeriods = k;
        settings.innovationDistribution = distribution;
        settings.innovationCv = cv;
        settings.replicationSeed = seed;
        return settings;
    }

    private static List<Method> methods() {
        return List.of(Method.D, Method.SAA, Method.CSAA,
                Method.DRO, Method.RCSAA_LBBD);
    }

    private static Candidate best(List<Candidate> candidates) {
        return candidates.stream()
                .filter(candidate -> Double.isFinite(candidate.score))
                .min((a, b) -> {
                    int scoreOrder = Double.compare(a.score, b.score);
                    if (scoreOrder != 0) return scoreOrder;
                    int kOrder = Integer.compare(a.k, b.k);
                    if (kOrder != 0) return kOrder;
                    int cOrder = Double.compare(a.cH, b.cH);
                    if (cOrder != 0) return cOrder;
                    return Double.compare(a.lambda, b.lambda);
                })
                .orElseThrow(() -> new IllegalStateException("No finite calibration candidate."));
    }

    private static void writeCandidates(Path path, List<Candidate> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("k,C_h,lambda,meanCalibrationOosCost,status");
            writer.newLine();
            for (Candidate row : rows) {
                writer.write(String.format(Locale.US, "%d,%.17g,%.17g,%.17g,%s%n",
                        row.k, row.cH, row.lambda, row.score, csv(row.status)));
            }
        }
    }

    private static void validatePositive(int... values) {
        for (int value : values) {
            if (value <= 0) throw new IllegalArgumentException("All numeric counts must be positive.");
        }
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private record Candidate(int k, double cH, double lambda, double score, String status) {
    }

    private record Selection(int k, double cH, double lambda,
                             int calibrationSeeds, int calibrationDraws,
                             InnovationDistribution distribution, double cv,
                             int threads, int timeLimitSeconds, int configVersion) {
        void write(Path path) throws Exception {
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write("k,C_h,lambda,calibrationSeeds,calibrationOosDraws,"
                        + "distribution,innovationCv,threads,timeLimitSeconds,configVersion");
                writer.newLine();
                writer.write(String.format(Locale.US, "%d,%.17g,%.17g,%d,%d,%s,%.17g,%d,%d,%d%n",
                        k, cH, lambda, calibrationSeeds, calibrationDraws,
                        distribution, cv, threads, timeLimitSeconds, configVersion));
            }
        }

        static Selection read(Path path) throws Exception {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() != 2) {
                throw new IllegalArgumentException("Invalid selected-parameter file: " + path);
            }
            String[] values = lines.get(1).split(",", -1);
            if (values.length != 10) {
                throw new IllegalArgumentException("Invalid selected-parameter row: " + path);
            }
            return new Selection(Integer.parseInt(values[0]), Double.parseDouble(values[1]),
                    Double.parseDouble(values[2]), Integer.parseInt(values[3]),
                    Integer.parseInt(values[4]), InnovationDistribution.valueOf(values[5]),
                    Double.parseDouble(values[6]), Integer.parseInt(values[7]),
                    Integer.parseInt(values[8]), Integer.parseInt(values[9]));
        }
    }
}
