package Test.analysis.synthetic;

import Model.RCSAASolverVariant;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.ContextualChoice;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Split W1 CV across hosts; merge only compatible saved origins, never solve the small grid twice. */
public final class TRBSVUSplitW1Main {
    static final double[] SMALL = {0.0001, 0.00025, 0.0005, 0.001, 0.0025, 0.005};
    static final double[] TAIL = {0.01, 0.025, 0.05, 0.1};
    // Candidate solves are grid-independent. Retain their original checkpoint identity
    // so a budget-driven truncation never repeats completed validation solves.
    static final double[] LEGACY_TAIL = {0.01, 0.025, 0.05, 0.1, 0.25, 0.5};
    private static final String NAME = "C-W1";
    private record Context(String pool, String source, ContextualChoice choice,
                           TRBSVUExperiment2IdeMain.RfFingerprint rf,
                           TRBSVUSyntheticCase instance) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5 && args.length != 7)
            throw new IllegalArgumentException("validate|check-validation|fingerprint|merge|check-merge input choice output rep [smallOutput tailOutput]");
        Path input = Path.of(args[1]), choice = Path.of(args[2]), output = Path.of(args[3]);
        Context context = context(input, choice);
        switch (args[0]) {
            case "fingerprint" -> System.out.println("W1_SPLIT pool=" + context.pool
                    + " source=" + context.source + " smallProtocol=" + protocol(context, SMALL)
                    + " tailProtocol=" + protocol(context, TAIL));
            case "validate" -> {
                try (var lock = TRBSVUWorkerLock.acquire(output)) { validate(context, output); }
            }
            case "check-validation" -> checkValidation(context, output);
            case "finish-validation" -> {
                // For a controller that has stopped an old, wider-grid worker.
                requireOrigins(context, output, TAIL);
                finishValidation(context, output);
            }
            case "check-merge" -> standard(input, choice, output, args[4], true);
            case "merge" -> {
                if (args.length != 7) throw new IllegalArgumentException("Missing completed small/tail outputs");
                merge(context, input, choice, output, args[4], Path.of(args[5]), Path.of(args[6]));
            }
            default -> throw new IllegalArgumentException("Unknown split W1 mode");
        }
    }

    private static Context context(Path input, Path choiceFile) throws Exception {
        var queries = TRBSVUExperiment1IdeMain.loadQueries(input);
        if (queries.size() != 40) throw new IllegalArgumentException("Expected all 40 frozen queries");
        var instance = TRBSVUSyntheticCaseIO.loadText(queries.get(0).file());
        TRBSVUExperiment1IdeMain.verifyFormalDimensions(instance);
        for (var query : queries)
            TRBSVUExperiment1IdeMain.verifySharedTrainingCore(instance,
                    TRBSVUSyntheticCaseIO.loadText(query.file()), query.index());
        var source = TRBSVUExperiment2IdeMain.class.getDeclaredMethod("sourceFingerprint", Path.class);
        source.setAccessible(true);
        var choice = TRBSVUExperiment4Main.loadChoice(choiceFile);
        var rf = TRBSVUExperiment2IdeMain.rfFingerprint(choice, Path.of(System.getProperty("trb.svu.python")));
        return new Context(TRBSVUExperiment1IdeMain.queryPoolFingerprint(queries),
                (String) source.invoke(null, Path.of("src")), choice, rf, instance);
    }

    private static void validate(Context context, Path output) throws Exception {
        String fingerprint = protocol(context, TAIL);
        Path marker = output.resolve("validation_only_complete.txt");
        if (Files.isRegularFile(marker)) { checkValidation(context, output); return; }
        var checkpoint = new TRBSVUValidationCheckpoint(output.resolve("validation_checkpoints"),
                context.pool, checkpointProtocol(context, TAIL));
        var settings = settings();
        var forest = new TRBSVUForestWeights(System.getProperty("trb.svu.python"),
                Path.of("analysis", "trb_svu", "rf_leaf_weights.py"));
        var contextual = new TRBSVUExperiment1Runner(settings, forest, 25);
        var runner = new TRBSVUExperiment2Runner(settings, contextual, 25,
                TRBSVUExperiment2Runner.LAMBDA, TAIL, checkpoint, null);
        var method = TRBSVUExperiment2Runner.class.getDeclaredMethod("validate",
                TRBSVUSyntheticCase.class, ContextualChoice.class, boolean.class,
                Method.class, double.class, String.class, List.class);
        method.setAccessible(true);
        var traces = new ArrayList<TRBSVUValidationTrace>();
        StringBuilder curve = new StringBuilder("radius,origins,mean,sd\n");
        for (double radius : TAIL) {
            try { method.invoke(runner, context.instance, context.choice, true,
                    Method.WASSERSTEIN, radius, NAME, traces); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception original) throw original;
                throw failure;
            }
            var summary = TRBSVUStatistics.summarize(traces.stream()
                    .filter(t -> t.candidateParameter() == radius)
                    .mapToDouble(TRBSVUValidationTrace::realizedValidationCost).toArray());
            curve.append(radius).append(",25,").append(summary.mean()).append(',')
                    .append(summary.sampleStandardDeviation()).append('\n');
            TRBSVUCompletionMarker.writeAtomically(output.resolve("validation_curve.csv"), curve.toString());
            System.out.println("W1_TAIL_CANDIDATE_COMPLETE radius=" + radius + " origins=25");
        }
        finishValidation(context, output);
    }

    private static void finishValidation(Context context, Path output) throws Exception {
        requireOrigins(context, output, TAIL);
        var checkpoint = new TRBSVUValidationCheckpoint(output.resolve("validation_checkpoints"),
                context.pool, checkpointProtocol(context, TAIL));
        StringBuilder curve = new StringBuilder("radius,origins,mean,sd\n");
        for (double radius : TAIL) {
            double[] costs = new double[25];
            for (int origin = 50; origin < 75; origin++)
                costs[origin-50] = checkpoint.load(NAME, radius, origin).orElseThrow().realizedValidationCost();
            var summary = TRBSVUStatistics.summarize(costs);
            curve.append(radius).append(",25,").append(summary.mean()).append(',')
                    .append(summary.sampleStandardDeviation()).append('\n');
        }
        TRBSVUCompletionMarker.writeAtomically(output.resolve("validation_curve.csv"), curve.toString());
        int count = TAIL.length * 25;
        Path marker = output.resolve("validation_only_complete.txt");
        String fingerprint = protocol(context, TAIL);
        TRBSVUCompletionMarker.writeAtomically(marker, "protocol=" + fingerprint
                + "\nqueryPool=" + context.pool + "\nsourceSha256=" + context.source
                + "\nretainedRadii=" + csv(TAIL) + "\ncheckpointProtocol=" + checkpointProtocol(context, TAIL)
                + "\noriginCount=" + count + "\nartifactSha256=" + originHash(output, TAIL) + "\n");
        System.out.println("W1_TAIL_VALIDATION_COMPLETE origins=" + count + " finalSolves=0");
    }

    private static void checkValidation(Context context, Path output) throws Exception {
        var values = metadata(output.resolve("validation_only_complete.txt"));
        if (!protocol(context, TAIL).equals(values.get("protocol"))
                || !Integer.toString(TAIL.length * 25).equals(values.get("originCount")))
            throw new IllegalStateException("Tail completion protocol mismatch: " + output);
        requireOrigins(context, output, TAIL);
        if (!originHash(output, TAIL).equals(values.get("artifactSha256")))
            throw new IllegalStateException("Tail checkpoint artifact mismatch: " + output);
    }

    private static void merge(Context context, Path input, Path choice, Path output,
                              String rep, Path small, Path tail) throws Exception {
        if (output.toAbsolutePath().equals(small.toAbsolutePath())
                || output.toAbsolutePath().equals(tail.toAbsolutePath()))
            throw new IllegalArgumentException("Merge target must be separate");
        String smallProtocol = protocol(context, SMALL);
        if (!smallProtocol.equals(metadata(small.resolve("complete.txt")).get("protocol")))
            throw new IllegalStateException("Small-grid completion protocol mismatch");
        // Read-only standard audit checks all final and OOS artifacts before reuse.
        TRBSVUExperiment2IdeMain.main(new String[]{"--check-complete", input.toString(), choice.toString(),
                small.toString(), rep, "4", "0", "PRIMARY", NAME, csv(SMALL)});
        requireOrigins(context, small, SMALL);
        checkValidation(context, tail);
        var target = new TRBSVUValidationCheckpoint(output.resolve("validation_checkpoints"),
                context.pool, protocol(context, fullGrid()));
        StringBuilder audit = new StringBuilder("radius,origin,source,source_sha256\n");
        for (var stage : List.of(Map.entry(small, SMALL), Map.entry(tail, TAIL))) {
            var saved = new TRBSVUValidationCheckpoint(stage.getKey().resolve("validation_checkpoints"),
                    context.pool, checkpointProtocol(context, stage.getValue()));
            for (double radius : stage.getValue()) for (int origin = 50; origin < 75; origin++) {
                var trace = saved.load(NAME, radius, origin).orElseThrow();
                var existing = target.load(NAME, radius, origin);
                if (existing.isEmpty()) target.save(trace);
                else if (!sameOrigin(file(output, radius, origin), file(stage.getKey(), radius, origin)))
                    throw new IllegalStateException("Conflicting imported validation origin");
                Path file = file(stage.getKey(), radius, origin);
                audit.append(radius).append(',').append(origin).append(',').append(file)
                        .append(',').append(hash(Files.readAllBytes(file))).append('\n');
            }
        }
        TRBSVUCompletionMarker.writeAtomically(output.resolve("validation_import.csv"), audit.toString());
        System.setProperty("trb.svu.finalReuseInput", input.toAbsolutePath().toString());
        System.setProperty("trb.svu.finalReuseBaseline", small.toAbsolutePath().toString());
        System.setProperty("trb.svu.finalReuseTarget", output.toAbsolutePath().toString());
        System.setProperty("trb.svu.finalReuseProtocol", smallProtocol);
        System.out.println("W1_MERGED origins=" + fullGrid().length * 25
                + "; selecting from " + fullGrid().length + " radii; unchanged parameter/weights reuse final+OOS");
        standard(input, choice, output, rep, false);
    }

    private static void standard(Path input, Path choice, Path output, String rep, boolean check) throws Exception {
        TRBSVUExperiment2IdeMain.main(new String[]{check ? "--check-complete" : "--worker",
                input.toString(), choice.toString(), output.toString(), rep, "4", "0", "PRIMARY",
                NAME, csv(fullGrid())});
    }

    private static Settings settings() {
        return new Settings(4, 0, 1e-4, RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true, true);
    }

    private static void requireOrigins(Context context, Path output, double[] grid) throws Exception {
        if (!Files.isDirectory(output.resolve("validation_checkpoints")))
            throw new IllegalStateException("Missing validation checkpoint directory: " + output);
        var saved = new TRBSVUValidationCheckpoint(output.resolve("validation_checkpoints"),
                context.pool, checkpointProtocol(context, grid));
        for (double radius : grid) for (int origin = 50; origin < 75; origin++) {
            var trace = saved.load(NAME, radius, origin).orElseThrow(
                    () -> new IllegalStateException("Missing/mismatched origin: " + output));
            if (trace.trainingStart() != origin-50 || trace.trainingEnd() != origin-1
                    || trace.decision() == null || trace.decision().length != context.instance.params.I
                    || Arrays.stream(trace.decision()).anyMatch(v -> !Double.isFinite(v)
                            || Math.abs(v-Math.rint(v)) > 1e-5 || v < 0 || v > 1)
                    || !Double.isFinite(trace.realizedValidationCost()))
                throw new IllegalStateException("Invalid saved validation origin");
        }
    }

    private static boolean sameOrigin(Path a, Path b) throws Exception {
        var first = metadata(a);
        var second = metadata(b);
        first.remove("protocolFingerprint");
        second.remove("protocolFingerprint");
        return first.equals(second);
    }

    private static String checkpointProtocol(Context context, double[] grid) throws Exception {
        return protocol(context, Arrays.equals(grid, TAIL) ? LEGACY_TAIL : grid);
    }

    static double[] fullGrid() {
        double[] result = Arrays.copyOf(SMALL, SMALL.length + TAIL.length);
        System.arraycopy(TAIL, 0, result, SMALL.length, TAIL.length);
        return result;
    }

    private static String protocol(Context c, double[] grid) throws Exception {
        return hash((TRBSVUFormalProtocol.EXPERIMENT12_VERSION
                + "|experiment=2|phase=primary|methods=[C-W1]|queryPool=" + c.pool
                + "|selected=" + c.choice + "|lambda=" + Arrays.toString(TRBSVUExperiment2Runner.LAMBDA)
                + "|w1=" + Arrays.toString(grid) + "|momentKappa=" + Arrays.toString(TRBSVUExperiment2Runner.MOMENT_KAPPA)
                + "|momentValidationLimit=" + TRBSVUExperiment2Runner.MOMENT_VALIDATION_LIMIT_SECONDS
                + "|momentQueryLimit=" + TRBSVUExperiment2Runner.MOMENT_QUERY_LIMIT_SECONDS
                + "|rcsaaCompactFormulation=SWITCHED_COMPACT|threads=4|limit=0|source=" + c.source
                + "|pcmScript=NOT_USED|mosekAdapter=NOT_USED|momentPythonEnvironment=NOT_USED"
                + c.rf.protocolSuffix()).getBytes(StandardCharsets.UTF_8));
    }

    private static Path file(Path output, double radius, int origin) {
        return output.resolve("validation_checkpoints/C-W1_p" + Long.toUnsignedString(
                Double.doubleToLongBits(radius), 16) + "_o" + origin + ".checkpoint");
    }

    private static String originHash(Path output, double[] grid) throws Exception {
        StringBuilder values = new StringBuilder();
        for (double radius : grid) for (int origin = 50; origin < 75; origin++)
            values.append(radius).append(':').append(origin).append(':')
                    .append(hash(Files.readAllBytes(file(output, radius, origin)))).append('\n');
        return hash(values.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> metadata(Path file) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            int split = line.indexOf('=');
            if (split <= 0 || result.put(line.substring(0, split), line.substring(split+1)) != null)
                throw new IllegalStateException("Invalid metadata: " + file);
        }
        return result;
    }

    private static String csv(double[] grid) {
        return String.join(",", Arrays.stream(grid).mapToObj(Double::toString).toList());
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
