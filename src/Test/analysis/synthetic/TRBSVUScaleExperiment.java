package Test.analysis.synthetic;

import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.Solution;
import Model.SolverTerminationException;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Computation-only scale experiment: shared frozen input, one model per process, no CV/OOS. */
public final class TRBSVUScaleExperiment {
    static final List<String> METHODS = List.of("SAA", "CSAA", "C-Chi2", "RCSAA", "C-W1");
    static final String VERSION = "TRBSVU_SCALE_V1";

    record CaseSpec(String id, int carriers, int lanes, int samples, int replication) { }
    record Inputs(TRBSVUSyntheticCase instance, List<Sample> contextual, String fingerprint) { }

    static final class Configuration {
        final Properties values;
        final List<CaseSpec> cases = new ArrayList<>();
        final int threads;
        final int limitSeconds;

        Configuration(Path file) throws Exception {
            values = readProperties(file);
            if (!VERSION.equals(required(values, "protocol")))
                throw new IllegalArgumentException("Unknown scale protocol");
            threads = Integer.parseInt(required(values, "solverThreads"));
            limitSeconds = Integer.parseInt(required(values, "limitSeconds"));
            int replications = Integer.parseInt(required(values, "replications"));
            if (threads < 1 || limitSeconds < 1 || replications < 1)
                throw new IllegalArgumentException("Invalid resources/replications");
            for (String size : required(values, "sizes").split(",")) {
                String[] f = size.trim().split("x");
                if (f.length != 3) throw new IllegalArgumentException("Expected IxJxS: " + size);
                int i = Integer.parseInt(f[0]), j = Integer.parseInt(f[1]), s = Integer.parseInt(f[2]);
                if (i < 1 || j < 1 || s < 1) throw new IllegalArgumentException("Invalid size");
                for (int r = 0; r < replications; r++) {
                    String id = String.format(java.util.Locale.ROOT, "I%d_J%d_S%d_rep_%03d", i, j, s, r);
                    if (cases.stream().anyMatch(c -> c.id.equals(id)))
                        throw new IllegalArgumentException("Duplicate case: " + id);
                    cases.add(new CaseSpec(id, i, j, s, r));
                }
            }
        }

        void requireFrozen() {
            if (!"FROZEN".equals(required(values, "state")))
                throw new IllegalStateException("Main-experiment settings are pending; freeze configuration before preparing inputs/running.");
            for (String key : List.of("dataProtocol", "dataProtocolSource", "contextFamily", "contextParameter",
                    "contextParameterSource", "rcsaaLambda", "chi2Lambda", "w1Radius", "robustParameterSource")) {
                String v = required(values, key);
                if (v.equals("UNSET")) throw new IllegalArgumentException("Unresolved setting: " + key);
            }
            String family = required(values, "contextFamily");
            if (!List.of("RF", "EXPONENTIAL", "GAUSSIAN", "EPANECHNIKOV", "TRIANGULAR").contains(family))
                throw new IllegalArgumentException("Unknown contextFamily " + family);
            double context = positive(values, "contextParameter");
            if (family.equals("RF") && context != Math.rint(context))
                throw new IllegalArgumentException("RF contextParameter is integer min_samples_leaf");
            for (String key : List.of("rcsaaLambda", "chi2Lambda", "w1Radius")) positive(values, key);
        }

        double parameter(String method) {
            return switch (method) {
                case "RCSAA" -> positive(values, "rcsaaLambda");
                case "C-Chi2" -> positive(values, "chi2Lambda");
                case "C-W1" -> positive(values, "w1Radius");
                default -> 0.0;
            };
        }

        CaseSpec find(String id) {
            return cases.stream().filter(c -> c.id.equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown case: " + id));
        }
    }

    private TRBSVUScaleExperiment() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException(
                "plan <root> <config> | audit <root> | worker <root> <caseId> <method> <attemptDir> | verify <root> <caseId> <method> <attemptDir>");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "plan" -> {
                if (args.length != 3) throw new IllegalArgumentException("plan needs config");
                preparePlan(root, Path.of(args[2]));
            }
            case "audit" -> {
                Configuration cfg = new Configuration(root.resolve("experiment.properties"));
                cfg.requireFrozen();
                for (CaseSpec spec : cfg.cases) readInputs(root, cfg, spec);
                System.out.println("SCALE_INPUT_AUDIT_PASS cases=" + cfg.cases.size());
            }
            case "worker", "verify" -> {
                if (args.length != 5) throw new IllegalArgumentException("worker/verify needs case, method, attemptDir");
                Configuration cfg = new Configuration(root.resolve("experiment.properties"));
                cfg.requireFrozen();
                CaseSpec spec = cfg.find(args[2]);
                requireMethod(args[3]);
                Inputs input = readInputs(root, cfg, spec);
                Path output = Path.of(args[4]).toAbsolutePath().normalize();
                if (args[0].equals("verify")) verifyOutput(output, input, args[3]);
                else solve(root, cfg, spec, input, args[3], output);
            }
            default -> throw new IllegalArgumentException("Unknown operation " + args[0]);
        }
    }

    static void preparePlan(Path root, Path source) throws Exception {
        Configuration cfg = new Configuration(source);
        Files.createDirectories(root);
        Path target = root.resolve("experiment.properties");
        if (Files.exists(target)) {
            if (!sha256(target).equals(sha256(source)))
                throw new IllegalStateException("Use a new root for a changed configuration: " + root);
        } else Files.copy(source, target);
        StringBuilder tasks = new StringBuilder("case_id\tI\tJ\tS\treplication\tmethod\tinput\tweights\toutput\tthreads\tlimit_seconds\n");
        for (CaseSpec c : cfg.cases) for (String method : METHODS)
            tasks.append(c.id).append('\t').append(c.carriers).append('\t').append(c.lanes)
                    .append('\t').append(c.samples).append('\t').append(c.replication).append('\t')
                    .append(method).append("\tinputs/").append(c.id).append("/instance.tsv\tinputs/")
                    .append(c.id).append("/context_weights.tsv\tresults/").append(c.id).append('/')
                    .append(method).append('\t').append(cfg.threads).append('\t').append(cfg.limitSeconds).append('\n');
        atomicText(root.resolve("tasks.tsv"), tasks.toString());
        System.out.println("SCALE_PLAN_READY cases=" + cfg.cases.size() + " tasks=" + cfg.cases.size() * METHODS.size()
                + " state=" + required(cfg.values, "state") + " generatedInstances=0");
    }

    /** Future data preparation calls this with the existing generator and weighting adapters. */
    static void writeInput(Path root, Configuration cfg, CaseSpec spec, TRBSVUSyntheticCase instance,
                           List<Sample> weighted, String generationDetails) throws Exception {
        cfg.requireFrozen();
        if (!instance.oos.isEmpty()) throw new IllegalArgumentException("Scale inputs must omit OOS");
        if (instance.params.I != spec.carriers || instance.params.J != spec.lanes || instance.history.size() != spec.samples)
            throw new IllegalArgumentException("Case dimensions do not match plan");
        if (weighted.size() != instance.history.size())
            throw new IllegalArgumentException("Keep all training rows including zero weights");
        StringBuilder weights = new StringBuilder("row_index\tsample_id\tweight\n");
        for (int s = 0; s < weighted.size(); s++) {
            Sample a = instance.history.get(s), b = weighted.get(s);
            if (a.id != b.id || !java.util.Arrays.equals(a.demand(), b.demand())
                    || !java.util.Arrays.equals(a.theta.values(), b.theta.values()))
                throw new IllegalArgumentException("Weights changed training data/order");
            weights.append(s).append('\t').append(a.id).append('\t').append(b.weight).append('\n');
        }
        Path dir = root.resolve("inputs").resolve(spec.id);
        if (Files.exists(dir)) throw new IllegalStateException("Refusing to overwrite frozen input " + dir);
        Files.createDirectories(dir);
        TRBSVUSyntheticCaseIO.saveText(instance, dir.resolve("instance.tsv"));
        atomicText(dir.resolve("context_weights.tsv"), weights.toString());
        atomicText(dir.resolve("generation.txt"), generationDetails);
        atomicText(dir.resolve("input.properties"), "instanceSha256=" + sha256(dir.resolve("instance.tsv"))
                + "\nweightsSha256=" + sha256(dir.resolve("context_weights.tsv"))
                + "\nconfigurationSha256=" + sha256(root.resolve("experiment.properties"))
                + "\ngenerationSha256=" + sha256(dir.resolve("generation.txt")) + "\n");
        readInputs(root, cfg, spec);
    }

    static Inputs readInputs(Path root, Configuration cfg, CaseSpec spec) throws Exception {
        Path dir = root.resolve("inputs").resolve(spec.id);
        Properties receipt = readProperties(dir.resolve("input.properties"));
        for (String[] pair : new String[][]{{"instance.tsv", "instanceSha256"},
                {"context_weights.tsv", "weightsSha256"}, {"generation.txt", "generationSha256"}})
            if (!sha256(dir.resolve(pair[0])).equals(required(receipt, pair[1])))
                throw new IllegalStateException("Input hash mismatch: " + dir.resolve(pair[0]));
        String configHash = sha256(root.resolve("experiment.properties"));
        if (!configHash.equals(required(receipt, "configurationSha256")))
            throw new IllegalStateException("Input uses a different configuration: " + spec.id);
        TRBSVUSyntheticCase data = TRBSVUSyntheticCaseIO.loadTextForSolve(dir.resolve("instance.tsv"));
        if (data.params.I != spec.carriers || data.params.J != spec.lanes
                || data.history.size() != spec.samples || !data.oos.isEmpty())
            throw new IllegalStateException("Scale input dimensions/OOS mismatch " + spec.id);
        List<String> lines = Files.readAllLines(dir.resolve("context_weights.tsv"), StandardCharsets.UTF_8);
        if (lines.size() != spec.samples + 1 || !lines.get(0).equals("row_index\tsample_id\tweight"))
            throw new IllegalStateException("Invalid weight table " + spec.id);
        double[] weights = new double[spec.samples];
        double sum = 0.0;
        for (int s = 0; s < spec.samples; s++) {
            String[] f = lines.get(s + 1).split("\t", -1);
            if (f.length != 3 || Integer.parseInt(f[0]) != s || Integer.parseInt(f[1]) != data.history.get(s).id)
                throw new IllegalStateException("Weight row order/id mismatch " + spec.id);
            weights[s] = Double.parseDouble(f[2]);
            if (!Double.isFinite(weights[s]) || weights[s] < 0) throw new IllegalStateException("Invalid weight");
            sum += weights[s];
        }
        if (Math.abs(sum - 1) > 1e-10) throw new IllegalStateException("Weights must sum to one");
        String runtime = System.getProperty("trb.scale.runtimeFingerprint", "LOCAL_UNPACKAGED");
        String fingerprint = VERSION + ":" + configHash + ":" + required(receipt, "instanceSha256")
                + ":" + required(receipt, "weightsSha256") + ":" + runtime;
        return new Inputs(data, TRBSVUScenarioWeights.copyWithWeights(data.history, weights, false), fingerprint);
    }

    static Method model(String method) {
        return switch (method) {
            case "SAA", "CSAA" -> Method.NOMINAL;
            case "RCSAA" -> Method.RCSAA;
            case "C-Chi2" -> Method.CHI_SQUARED;
            case "C-W1" -> Method.WASSERSTEIN;
            default -> throw new IllegalArgumentException("Unknown method " + method);
        };
    }

    static Settings settings(Configuration cfg) {
        return new Settings(cfg.threads, cfg.limitSeconds, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true, true);
    }

    private static void solve(Path root, Configuration cfg, CaseSpec spec, Inputs input,
                              String method, Path output) throws Exception {
        if (Files.exists(output)) throw new IllegalStateException("Use a new attempt directory: " + output);
        Files.createDirectories(output);
        List<Sample> weighted = method.equals("SAA") ? TRBSVUScenarioWeights.equal(input.instance.history) : input.contextual;
        atomicText(output.resolve("run.properties"), "protocol=" + VERSION + "\ncase=" + spec.id
                + "\nmethod=" + method + "\ninputFingerprint=" + input.fingerprint + "\nthreads=" + cfg.threads
                + "\nlimitSeconds=" + cfg.limitSeconds + "\nparameter=" + cfg.parameter(method)
                + "\nrcsaaFormulation=SWITCHED_COMPACT_INTEGRATED\nw1Algorithm=CCG\ncv=false\noos=false\n");
        TRBSVUResultWriter.writeFinalWeights(output.resolve("weights.csv"), spec.replication, "SCALE", Map.of(method, weighted));
        atomicText(output.resolve("solve_started.txt"), Instant.now().toString() + "\n");
        long start = System.nanoTime();
        Solution solution;
        try {
            solution = TRBSVUSolveMethods.solve(input.instance.params, input.instance.lanes, weighted,
                    input.instance.testContext, model(method), cfg.parameter(method), settings(cfg));
        } catch (SolverTerminationException ex) {
            solution = new Solution(Double.NaN, null, 0.0);
            solution.solverStatus = ex.solverStatus;
            solution.bestBound = ex.bestBound;
            solution.nodeCount = ex.nodeCount;
            // No incumbent means no meaningful relative optimality gap.
            solution.relativeGap = Double.NaN;
        } catch (Exception ex) {
            atomicText(output.resolve("failure.txt"), ex.getClass().getName() + ": " + ex.getMessage()
                    + "\nwallSeconds=" + (System.nanoTime() - start) / 1e9 + "\n");
            throw ex;
        }
        solution.solveTimeSec = (System.nanoTime() - start) / 1e9;
        writeOutcome(cfg, spec, input, method, weighted, output, solution);
        System.out.println("SCALE_TASK_FINISHED case=" + spec.id + " method=" + method
                + " status=" + solution.solverStatus + " incumbent=" + (solution.y != null));
    }

    static void writeOutcome(Configuration cfg, CaseSpec spec, Inputs input, String method,
                             List<Sample> weighted, Path output, Solution solution) throws Exception {
        if (solution.y == null) {
            solution.objValue = Double.NaN;
            solution.relativeGap = Double.NaN;
            solution.certifiedOptimal = false;
        } else if (solution.y.length != input.instance.params.I || !Double.isFinite(solution.objValue)) {
            throw new IllegalStateException("Invalid incumbent dimensions/objective");
        } else if (!Double.isFinite(solution.bestBound)) {
            solution.relativeGap = Double.NaN;
        } else if (Double.isFinite(solution.bestBound) && Double.isFinite(solution.objValue)
                && solution.bestBound > solution.objValue + 1e-8 * Math.max(1, Math.abs(solution.objValue))
                && !Model.RCSAABoundDiagnostics.acceptedOverlap(solution)) {
            solution.relativeGap = Double.NaN;
            solution.certifiedOptimal = false;
            solution.solverStatus += "/BOUND_INCONSISTENT";
        }
        String family = method.equals("SAA") ? "UNCONDITIONAL" : required(cfg.values, "contextFamily");
        double context = method.equals("SAA") || family.equals("RF") ? Double.NaN : positive(cfg.values, "contextParameter");
        String type = method.equals("C-W1") ? "EPSILON" : method.equals("RCSAA") || method.equals("C-Chi2") ? "LAMBDA" : "NONE";
        Path temporary = output.resolve("result.pending.csv");
        TRBSVUResultWriter.writeFinalSolves(temporary, spec.replication, "SCALE", input.instance.params,
                Map.of(method, solution), Map.of(), Map.of(method, cfg.parameter(method)), Map.of(method, type),
                Map.of(method, family), Map.of(method, context), Map.of(method, context), Map.of(method, weighted));
        move(temporary, output.resolve("result.csv"));
        TRBSVUFinalCheckpoint checkpoint = new TRBSVUFinalCheckpoint(output.resolve("checkpoint"),
                output.resolve("checkpoint"), input.fingerprint, VERSION, spec.replication, "SCALE");
        checkpoint.save(method, cfg.parameter(method), solution);
        List<Path> checkpointFiles;
        try (var files = Files.list(output.resolve("checkpoint"))) {
            checkpointFiles = files.filter(Files::isRegularFile).toList();
        }
        if (checkpointFiles.size() != 1) throw new IllegalStateException("Expected one solve checkpoint");
        atomicText(output.resolve("complete.properties"), "inputFingerprint=" + input.fingerprint + "\nmethod=" + method
                + "\nresultSha256=" + sha256(output.resolve("result.csv")) + "\nweightsSha256=" + sha256(output.resolve("weights.csv"))
                + "\ncheckpointName=" + checkpointFiles.get(0).getFileName()
                + "\ncheckpointSha256=" + sha256(checkpointFiles.get(0))
                + "\nfinished=" + Instant.now() + "\nstatus=" + solution.solverStatus + "\n");
    }

    static void verifyOutput(Path output, Inputs input, String method) throws Exception {
        Properties marker = readProperties(output.resolve("complete.properties"));
        Path checkpointRoot = output.resolve("checkpoint").toAbsolutePath().normalize();
        Path checkpoint = checkpointRoot.resolve(required(marker, "checkpointName")).normalize();
        if (!checkpoint.getParent().equals(checkpointRoot)) throw new IllegalStateException("Invalid checkpoint path");
        if (!input.fingerprint.equals(required(marker, "inputFingerprint")) || !method.equals(required(marker, "method"))
                || !sha256(output.resolve("result.csv")).equals(required(marker, "resultSha256"))
                || !sha256(output.resolve("weights.csv")).equals(required(marker, "weightsSha256"))
                || !sha256(checkpoint).equals(required(marker, "checkpointSha256")))
            throw new IllegalStateException("Incomplete/mismatched scale output " + output);
        System.out.println("SCALE_OUTPUT_VERIFIED " + output);
    }

    static Properties readProperties(Path file) throws Exception {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        return p;
    }

    static String required(Properties p, String name) {
        String result = p.getProperty(name);
        if (result == null || result.isBlank()) throw new IllegalArgumentException("Missing setting " + name);
        return result.trim();
    }

    private static double positive(Properties p, String name) {
        double value = Double.parseDouble(required(p, name));
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }

    private static void requireMethod(String method) {
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Unknown method " + method);
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static void atomicText(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "pending_", ".txt");
        try { Files.writeString(temporary, text, StandardCharsets.UTF_8); move(temporary, file); }
        finally { Files.deleteIfExists(temporary); }
    }

    private static void move(Path source, Path target) throws Exception {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
