package Test.analysis.synthetic;

import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.ContextualChoice;
import Test.analysis.synthetic.TRBSVUExperiment4Runner.Certificate;
import Test.analysis.synthetic.TRBSVUExperiment4Runner.Result;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs Experiment 4 on one frozen Experiment 1/2 replication. */
public final class TRBSVUExperiment4Main {
    private TRBSVUExperiment4Main() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 6)
            throw new IllegalArgumentException("Usage: <replicationIndex> <baselineRoot> <outputRoot> "
                    + "[threads=4] [limitSec=14400] [lambdaCsv=full-grid]");
        int replication = Integer.parseInt(args[0]);
        Path baselineRoot = Path.of(args[1]).toAbsolutePath().normalize();
        Path outputRoot = Path.of(args[2]).toAbsolutePath().normalize();
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        int limitSeconds = args.length > 4 ? Integer.parseInt(args[4]) : 14400;
        double[] lambdas = args.length > 5 ? parseLambdas(args[5])
                : TRBSVUExperiment4Runner.LAMBDA.clone();
        if (replication < 0 || threads < 1 || limitSeconds < 1)
            throw new IllegalArgumentException("Invalid Experiment 4 settings.");

        Path baselineReplication = baselineRoot.resolve(String.format("rep_%03d", replication));
        Path instanceFile = baselineReplication.resolve("instance").resolve("instance.tsv");
        Path contextFile = baselineReplication.resolve("validation")
                .resolve("experiment1_selected_context.csv");
        if (!Files.isRegularFile(instanceFile))
            throw new IllegalStateException("Missing frozen baseline instance: " + instanceFile);
        if (!Files.isRegularFile(contextFile))
            throw new IllegalStateException("Run Experiment 1 first; selected context is missing: " + contextFile);
        TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(instanceFile);
        ContextualChoice choice = loadChoice(contextFile);

        Settings settings = new Settings(threads, limitSeconds, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        Path python = Path.of(".venv-rsome", "Scripts", "python.exe").toAbsolutePath();
        Path rfScript = Path.of("analysis", "trb_svu", "rf_leaf_weights.py").toAbsolutePath();
        TRBSVUExperiment1Runner contextual = new TRBSVUExperiment1Runner(settings,
                new TRBSVUForestWeights(python.toString(), rfScript),
                TRBSVUFormalProtocol.VALIDATION_ORIGINS);

        Path resultRoot = outputRoot.resolve(String.format("rep_%03d", replication));
        Files.createDirectories(resultRoot);
        String javaSourceSha256 = javaSourceFingerprint(Path.of("src"));
        boolean usesRandomForest = usesRandomForest(choice);
        String rfScriptSha256 = usesRandomForest ? sha256(rfScript) : "NOT_USED";
        String pythonEnvironment = usesRandomForest ? pythonEnvironment(python) : "NOT_USED";
        String protocol = sha256("TRBSVU_EXP4_V1|instance=" + sha256(instanceFile)
                + "|context=" + sha256(contextFile) + "|threads=" + threads
                + "|limit=" + limitSeconds + "|tolerance=1e-4|compact=true|lambda="
                + Arrays.toString(lambdas) + "|positiveWeightFloor=1e-8|javaSource="
                + javaSourceSha256 + "|rfScript=" + rfScriptSha256
                + "|pythonEnvironment=" + pythonEnvironment);
        TRBSVUFinalCheckpoint checkpoint = new TRBSVUFinalCheckpoint(
                resultRoot.resolve("solve_checkpoints"), resultRoot.resolve("oos_checkpoints"),
                sha256(instanceFile), protocol, replication, "4");
        TRBSVUExperiment4Runner runner = new TRBSVUExperiment4Runner(settings, contextual, checkpoint);

        writeAtomically(resultRoot.resolve("run_manifest.txt"),
                "experiment=4\nreplication=" + replication + "\ninstance=" + instanceFile + "\n"
                        + "selectedContext=" + contextFile + "\ncontextFamily=" + choice.family() + "\n"
                        + "validationSelectedB=" + choice.bandwidth() + "\nthreads=" + threads + "\n"
                        + "limitSecondsPerSolve=" + limitSeconds + "\nlambdaGrid="
                        + Arrays.toString(lambdas) + "\njavaSourceSha256=" + javaSourceSha256 + "\n"
                        + "rfScriptSha256=" + rfScriptSha256 + "\npythonEnvironment="
                        + pythonEnvironment + "\nprotocolFingerprint=" + protocol + "\n");

        List<Result> results = new ArrayList<>();
        for (double lambda : lambdas) {
            Result result = runner.runOne(instance, choice, lambda);
            results.add(result);
            writeSummary(resultRoot.resolve("experiment4_comparison.csv"), replication, results);
            System.out.println("Experiment 4 complete replication=" + replication
                    + " lambda=" + lambda + " exact=" + result.rcsaa().solverStatus
                    + " chi2=" + result.chiSquared().solverStatus);
        }
        writeAtomically(resultRoot.resolve("experiment4_complete.txt"),
                "format=TRBSVU_EXP4_V1\nreplication=" + replication + "\n"
                        + "protocolFingerprint=" + protocol + "\ncompletedLambdaCount="
                        + results.size() + "\n");
    }

    private static void writeSummary(Path target, int replication, List<Result> results)
            throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("replication,lambda,effective_B,certificate_status,certificate_holds,certificate_ratio,"
                + "certificate_mean,certificate_sd,certificate_min,certificate_denominator,"
                + "objective_gap_percent,same_decision,jaccard,"
                + "chi2_status,chi2_certified,chi2_objective,chi2_bound,chi2_gap,chi2_time,"
                + "chi2_selected,chi2_y,chi2_oos_mean,chi2_oos_sd,chi2_oos_q95,chi2_oos_cvar95,chi2_oos_max,"
                + "rcsaa_status,rcsaa_certified,rcsaa_objective,rcsaa_bound,rcsaa_gap,rcsaa_time,"
                + "rcsaa_selected,rcsaa_y,rcsaa_oos_mean,rcsaa_oos_sd,rcsaa_oos_q95,rcsaa_oos_cvar95,rcsaa_oos_max\n");
        for (Result result : results) {
            Certificate c = result.certificate();
            out.append(String.format(Locale.ROOT,
                    "%d,%.17g,%.17g,%s,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%s,%.17g,",
                    replication, result.lambda(), result.effectiveBandwidth(),
                    certificateStatus(c), certificateHolds(c), c == null ? Double.NaN : c.ratio(),
                    c == null ? Double.NaN : c.weightedMean(),
                    c == null ? Double.NaN : c.weightedSd(),
                    c == null ? Double.NaN : c.minimum(),
                    c == null ? Double.NaN : c.denominator(), result.objectiveGapPercent(),
                    result.sameDecision(), result.jaccard()));
            appendMethod(out, result.chiSquared(), result.chiSquaredOos() == null
                    ? null : result.chiSquaredOos().summary());
            out.append(',');
            appendMethod(out, result.rcsaa(), result.rcsaaOos() == null
                    ? null : result.rcsaaOos().summary());
            out.append('\n');
        }
        writeAtomically(target, out.toString());
    }

    private static void appendMethod(StringBuilder out, Solution solution, Oos oos) {
        out.append(csv(solution.solverStatus)).append(',').append(solution.certifiedOptimal).append(',')
                .append(solution.objValue).append(',').append(solution.bestBound).append(',')
                .append(solution.relativeGap).append(',').append(solution.solveTimeSec).append(',')
                .append(selectedCount(solution.y)).append(',').append(csv(decision(solution.y))).append(',');
        if (oos == null) out.append("NaN,NaN,NaN,NaN,NaN");
        else out.append(oos.mean()).append(',').append(oos.standardDeviation()).append(',')
                .append(oos.q95()).append(',').append(oos.cvar95()).append(',').append(oos.maximum());
    }

    static String certificateStatus(Certificate certificate) {
        return certificate == null ? "UNRESOLVED" : certificate.holds() ? "HOLDS" : "FAILS";
    }

    static String certificateHolds(Certificate certificate) {
        return certificate == null ? "NA" : Boolean.toString(certificate.holds());
    }

    static boolean usesRandomForest(ContextualChoice choice) {
        return "RF".equals(choice.family());
    }

    static ContextualChoice loadChoice(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() != 2) throw new IllegalStateException("Invalid selected-context file: " + file);
        List<String> header = parseCsv(lines.get(0)), row = parseCsv(lines.get(1));
        if (header.size() != row.size()) throw new IllegalStateException("Selected-context column mismatch.");
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) values.put(header.get(i), row.get(i));
        String family = required(values, "family");
        double bandwidth = Double.parseDouble(required(values, "validation_selected_B"));
        int minLeaf = values.containsKey("validation_selected_min_leaf")
                ? Integer.parseInt(values.get("validation_selected_min_leaf"))
                : "RF".equals(family) ? 1 : 0;
        double cost = Double.parseDouble(required(values, "validation_cost"));
        double sd = Double.parseDouble(required(values, "validation_sd"));
        List<Double> order = new ArrayList<>();
        String text = "RF".equals(family) && values.containsKey("min_leaf_order")
                ? values.get("min_leaf_order") : required(values, "bandwidth_order");
        if (!text.isBlank()) for (String item : text.split(";")) order.add(Double.parseDouble(item));
        return new ContextualChoice(family, bandwidth, cost, sd, order, minLeaf);
    }

    private static List<String> parseCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                fields.add(field.toString()); field.setLength(0);
            } else field.append(ch);
        }
        if (quoted) throw new IllegalStateException("Unclosed CSV quote.");
        fields.add(field.toString());
        return fields;
    }

    private static double[] parseLambdas(String csv) {
        String[] fields = csv.split(",");
        double[] result = new double[fields.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = Double.parseDouble(fields[i].trim());
            if (!(result[i] > 0.0)) throw new IllegalArgumentException("Lambda must be positive.");
        }
        return result;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) throw new IllegalStateException("Missing selected-context column: " + key);
        return value;
    }

    private static int selectedCount(double[] y) {
        if (y == null) return 0;
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static String decision(double[] y) {
        if (y == null) return "NA";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < y.length; i++) {
            if (i > 0) result.append(';');
            result.append(y[i] > 0.5 ? '1' : '0');
        }
        return result.toString();
    }

    private static String csv(String value) {
        if (value == null) return "NA";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String javaSourceFingerprint(Path sourceRoot) throws Exception {
        Path absoluteRoot = sourceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(absoluteRoot))
            throw new IllegalStateException("Java source root not found: " + absoluteRoot);
        List<Path> sources;
        try (var stream = Files.walk(absoluteRoot)) {
            sources = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> absoluteRoot.relativize(path)
                            .toString().replace('\\', '/')))
                    .toList();
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path source : sources) {
            digest.update(absoluteRoot.relativize(source).toString().replace('\\', '/')
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(source));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String pythonEnvironment(Path python) throws Exception {
        String code = "import sys; from importlib.metadata import version; "
                + "print('python='+sys.version.split()[0]+'|numpy='+version('numpy')"
                + "+'|scikit-learn='+version('scikit-learn'))";
        Process process = new ProcessBuilder(python.toString(), "-c", code)
                .redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out while reading the frozen Python environment.");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0 || output.isEmpty() || output.indexOf('\n') >= 0
                || output.indexOf('\r') >= 0)
            throw new IllegalStateException("Cannot identify the frozen Python environment: " + output);
        return output;
    }

    private static void writeAtomically(Path target, String content) throws Exception {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "pending_", ".txt");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
