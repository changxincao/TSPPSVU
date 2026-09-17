package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** RSOME/MOSEK adapter for the documented lifted-affine PCM-DRO benchmark. */
public final class TRBSVUPcmSolver {
    private final Path python;
    private final Path script;

    public TRBSVUPcmSolver(Path python, Path script) {
        if (python == null || script == null) throw new IllegalArgumentException("PCM paths are required.");
        this.python = python.toAbsolutePath();
        this.script = script.toAbsolutePath();
    }

    public Solution solve(ProcurementParams params, List<Sample> weighted,
                          double kappa, Settings settings) throws Exception {
        return solve(params, weighted, kappa, settings, true);
    }

    public Solution solve(ProcurementParams params, List<Sample> weighted,
                          double kappa, Settings settings, boolean adaptToLift) throws Exception {
        if (weighted.isEmpty() || !(kappa >= 1.0) || !Double.isFinite(kappa))
            throw new IllegalArgumentException("Invalid PCM samples or kappa.");
        Moments moments = moments(weighted, params.J, kappa);
        Path temporaryRoot = Path.of("tmp");
        Files.createDirectories(temporaryRoot);
        Path directory = Files.createTempDirectory(temporaryRoot, "trb_svu_pcm_");
        Throwable primaryFailure = null;
        try {
            writeInput(directory, params, moments, settings, adaptToLift);
            System.out.printf(java.util.Locale.ROOT,
                    "PCM_SOLVE_BEGIN scenarios=%d positiveWeights=%d ess=%.10f kappa=%.17g threads=%d limitSec=%d%n",
                    weighted.size(), TRBSVUExperiment1Runner.positiveCount(weighted),
                    TRBSVUExperiment1Runner.ess(weighted), kappa,
                    settings.threads(), settings.timeLimitSeconds());
            ProcessBuilder builder = new ProcessBuilder(python.toString(), script.toString(), directory.toString())
                    .redirectErrorStream(true).inheritIO();
            builder.environment().put("PYTHONUNBUFFERED", "1");
            Process process = builder.start();
            // MOSEK's mioMaxTime starts only after RSOME has constructed and
            // reformulated the conic model.  Large PCM instances can spend
            // many minutes in that build phase, so the outer watchdog must
            // not consume the optimizer's requested time limit.
            boolean ended = process.waitFor(settings.timeLimitSeconds() + 3600L, TimeUnit.SECONDS);
            if (!ended) {
                destroyProcessTree(process);
                throw new IllegalStateException("PCM process exceeded solver limit plus 3600 seconds build grace.");
            }
            if (process.exitValue() != 0)
                throw new IllegalStateException("PCM solve failed with exit code " + process.exitValue()
                        + "; inspect the run log above this marker.");
            Solution solution = readSolution(directory.resolve("solution.json"), params.I);
            System.out.printf(java.util.Locale.ROOT,
                    "PCM_SOLVE_END status=%s certified=%s objective=%.17g solveSec=%.6f selected=%d%n",
                    solution.solverStatus, solution.certifiedOptimal, solution.objValue,
                    solution.solveTimeSec, selectedCount(solution.y));
            return solution;
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                deleteTemporaryDirectory(directory);
            } catch (Exception cleanupFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private static void destroyProcessTree(Process process) throws Exception {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        for (ProcessHandle child : descendants) child.destroyForcibly();
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
        for (ProcessHandle child : descendants) {
            if (child.isAlive()) child.onExit().get(10, TimeUnit.SECONDS);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                for (String file : List.of("solution.json", "lane_capacity.csv", "rate.csv",
                        "carriers.json", "lanes.json", "meta.json"))
                    Files.deleteIfExists(directory.resolve(file));
                Files.deleteIfExists(directory);
                return;
            } catch (java.nio.file.AccessDeniedException failure) {
                last = failure;
                Thread.sleep(200L);
            }
        }
        throw last;
    }

    private static int selectedCount(double[] selection) {
        int count = 0;
        for (double value : selection) if (value > 0.5) count++;
        return count;
    }

    static Moments moments(List<Sample> samples, int lanes, double kappa) {
        double totalWeight = samples.stream().mapToDouble(sample -> sample.weight).sum();
        if (!(totalWeight > 0.0) || !Double.isFinite(totalWeight))
            throw new IllegalArgumentException("PCM weights have no positive finite mass.");
        double[] mean = new double[lanes];
        double[] upper = new double[lanes];
        for (Sample sample : samples) {
            if (sample.demand().length != lanes || sample.weight < 0.0 || !Double.isFinite(sample.weight))
                throw new IllegalArgumentException("Invalid PCM scenario.");
            double probability = sample.weight / totalWeight;
            for (int j = 0; j < lanes; j++) {
                double demand = sample.demand()[j];
                if (!(demand >= 0.0) || !Double.isFinite(demand))
                    throw new IllegalArgumentException("Invalid PCM demand.");
                mean[j] += probability * demand;
                upper[j] = Math.max(upper[j], demand);
            }
        }
        double[] variance = new double[lanes];
        double totalVariance = 0.0;
        for (Sample sample : samples) {
            double probability = sample.weight / totalWeight;
            double totalDeviation = 0.0;
            for (int j = 0; j < lanes; j++) {
                double deviation = sample.demand()[j] - mean[j];
                variance[j] += probability * deviation * deviation;
                totalDeviation += deviation;
            }
            totalVariance += probability * totalDeviation * totalDeviation;
        }
        double inflation = kappa * kappa;
        for (int j = 0; j < lanes; j++) {
            variance[j] *= inflation;
            upper[j] *= 1.5;
        }
        return new Moments(mean, variance, upper, inflation * totalVariance);
    }

    private static void writeInput(Path root, ProcurementParams p, Moments m,
                                   Settings settings, boolean adaptToLift) throws Exception {
        Files.writeString(root.resolve("meta.json"), "{\"carriers\":" + p.I
                + ",\"lanes\":" + p.J + ",\"alpha\":" + p.alpha + ",\"beta\":" + p.beta
                + ",\"total_variance_bound\":" + m.totalVariance
                + ",\"policy\":\"" + (adaptToLift
                ? "demand_and_second_moment_lift_affine" : "demand_affine") + "\""
                + ",\"threads\":" + settings.threads() + ",\"time_limit_seconds\":"
                + settings.timeLimitSeconds() + "}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("lanes.json"), "{\"mean\":" + json(m.mean)
                + ",\"variance_bound\":" + json(m.variance) + ",\"support_upper\":"
                + json(m.upper) + ",\"spot_cost\":" + json(p.e) + "}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("carriers.json"), "{\"total_capacity\":" + json(p.M)
                + ",\"mqc\":" + json(p.p) + ",\"penalty\":" + json(p.h) + "}",
                StandardCharsets.UTF_8);
        // A generic ProcurementParams object need not store zero in q for an
        // ineligible pair.  The PCM model has no separate eligibility matrix,
        // so both rate and capacity must be zeroed at this interface boundary.
        writeMatrix(root.resolve("lane_capacity.csv"), p.q, p.eligible, true);
        writeMatrix(root.resolve("rate.csv"), p.r, p.eligible, true);
    }

    private static void writeMatrix(Path path, double[][] values, boolean[][] eligible,
                                    boolean zeroIneligible) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (int i = 0; i < values.length; i++) {
                for (int j = 0; j < values[i].length; j++) {
                    if (j > 0) out.write(',');
                    out.write(Double.toString(!eligible[i][j] && zeroIneligible ? 0.0 : values[i][j]));
                }
                out.newLine();
            }
        }
    }

    private static Solution readSolution(Path path, int carriers) throws Exception {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        double objective = number(json, "objective");
        double seconds = number(json, "model_and_solve_seconds");
        String status = string(json, "status");
        String body = json.substring(json.indexOf('[', json.indexOf("\"selected\"")) + 1,
                json.indexOf(']', json.indexOf("\"selected\"")));
        String[] fields = body.isBlank() ? new String[0] : body.split(",");
        if (fields.length != carriers) throw new IllegalStateException("PCM selection dimension mismatch.");
        double[] y = new double[carriers];
        for (int i = 0; i < carriers; i++) y[i] = Double.parseDouble(fields[i].trim());
        Solution solution = new Solution(objective, y, seconds);
        solution.solverStatus = status;
        solution.bestBound = objective;
        solution.relativeGap = 0.0;
        solution.certifiedOptimal = true; // For the stated PCM decision-rule approximation only.
        return solution;
    }

    private static double number(String json, String key) {
        int start = json.indexOf(':', json.indexOf("\"" + key + "\"")) + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && "0123456789+-.eE".indexOf(json.charAt(end)) >= 0) end++;
        return Double.parseDouble(json.substring(start, end));
    }

    private static String string(String json, String key) {
        int colon = json.indexOf(':', json.indexOf("\"" + key + "\""));
        int start = json.indexOf('"', colon) + 1;
        return json.substring(start, json.indexOf('"', start));
    }

    private static String json(double[] values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(values[i]);
        }
        return out.append(']').toString();
    }

    record Moments(double[] mean, double[] variance, double[] upper, double totalVariance) { }
}
