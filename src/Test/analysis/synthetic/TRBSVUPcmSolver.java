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
        if (weighted.isEmpty() || !(kappa >= 1.0) || !Double.isFinite(kappa))
            throw new IllegalArgumentException("Invalid PCM samples or kappa.");
        Moments moments = moments(weighted, params.J, kappa);
        Path directory = Files.createTempDirectory(Path.of("tmp"), "trb_svu_pcm_");
        try {
            writeInput(directory, params, moments, settings);
            System.out.printf(java.util.Locale.ROOT,
                    "PCM_SOLVE_BEGIN scenarios=%d positiveWeights=%d ess=%.10f kappa=%.17g threads=%d limitSec=%d%n",
                    weighted.size(), TRBSVUExperiment1Runner.positiveCount(weighted),
                    TRBSVUExperiment1Runner.ess(weighted), kappa,
                    settings.threads(), settings.timeLimitSeconds());
            Process process = new ProcessBuilder(python.toString(), script.toString(), directory.toString())
                    .redirectErrorStream(true).inheritIO().start();
            boolean ended = process.waitFor(settings.timeLimitSeconds() + 120L, TimeUnit.SECONDS);
            if (!ended) {
                process.destroyForcibly();
                throw new IllegalStateException("PCM process exceeded solver limit plus 120 seconds.");
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
        } finally {
            for (String file : List.of("solution.json", "lane_capacity.csv", "rate.csv",
                    "carriers.json", "lanes.json", "meta.json"))
                Files.deleteIfExists(directory.resolve(file));
            Files.deleteIfExists(directory);
        }
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
                                   Settings settings) throws Exception {
        Files.writeString(root.resolve("meta.json"), "{\"carriers\":" + p.I
                + ",\"lanes\":" + p.J + ",\"alpha\":" + p.alpha + ",\"beta\":" + p.beta
                + ",\"total_variance_bound\":" + m.totalVariance
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
        solution.certifiedOptimal = true; // For the stated lifted-affine PCM model only.
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
