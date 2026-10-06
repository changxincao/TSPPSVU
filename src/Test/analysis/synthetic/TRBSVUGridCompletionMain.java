package Test.analysis.synthetic;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit cross-grid import: unchanged inputs, solver source and settings are required. */
public final class TRBSVUGridCompletionMain {
    private static final double[] B = {.1, .25, .5, .8, .9, 1, 2, 3, 5, 10, 30, 50, 100};
    private static final double[] LEAF = {1, 2, 5, 10};
    // Keep aligned with the revised synthetic grid; do not overwrite historical nine-point runs.
    private static final double[] LAMBDA = {.1, .25, .5, 1, 2, 5, 10};
    private TRBSVUGridCompletionMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Expected exp, check, chi or lognormal.");
        if ("fingerprints".equals(args[0])) {
            for (Class<?> owner : new Class<?>[]{TRBSVUExperiment1IdeMain.class, TRBSVUExperiment2IdeMain.class})
                System.out.println(owner.getSimpleName() + "=" + invoke(owner, "sourceFingerprint", Path.class, Path.of("src")));
            return;
        }
        if ("lognormal".equals(args[0])) {
            generateLognormal(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        Path input = Path.of(args[1]), baseline = Path.of(args[2]), output = Path.of(args[3]);
        int rep = Integer.parseInt(args[4]);
        String method = args[5];
        String pool = TRBSVUExperiment1IdeMain.queryPoolFingerprint(
                TRBSVUExperiment1IdeMain.loadQueries(input));
        String source = (String) invoke(("C-Chi2".equals(method) || "RCSAA".equals(method))
                ? TRBSVUExperiment2IdeMain.class : TRBSVUExperiment1IdeMain.class,
                "sourceFingerprint", Path.class, Path.of("src"));
        String script = hash(Files.readAllBytes(Path.of("analysis/trb_svu/rf_leaf_weights.py")));
        String environment = "RF-CSAA".equals(method)
                ? (String) invoke("pythonEnvironment", Path.class,
                    Path.of(System.getProperty("trb.svu.python"))) : "NOT_USED";
        if ("exp".equals(args[0]) || "check".equals(args[0])) {
            double[] oldB = "CSAA-Tri".equals(method) ? new double[]{.8, .9, 1, 2}
                    : new double[]{.1, .25, .5, .8};
            String oldProtocol = expProtocol(method, pool, source, script, environment,
                    oldB, new double[]{1, 2, 5});
            String newProtocol = expProtocol(method, pool, source, script, environment, B, LEAF);
            if (!baseline.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()))
                importValidation(baseline, output, input, method, pool, source, oldProtocol,
                        newProtocol, "RF-CSAA".equals(method) ? new double[]{1, 2, 5} : oldB,
                        "check".equals(args[0]));
            if ("check".equals(args[0])) return;
            System.setProperty("trb.svu.bandwidthGrid", csv(B));
            System.setProperty("trb.svu.rfLeafGrid", csv(LEAF));
            enableFinalReuse(input, baseline, output, oldProtocol);
            TRBSVUExperiment1IdeMain.main(new String[]{"--worker", input.toString(), output.toString(),
                    Integer.toString(rep), method, "25", "4", "14400"});
        } else if ("chi".equals(args[0]) || "check-chi".equals(args[0])) {
            Path oldChoiceFile = Path.of(args[6]), newChoiceFile = Path.of(args[7]);
            var oldChoice = TRBSVUExperiment4Main.loadChoice(oldChoiceFile);
            var newChoice = TRBSVUExperiment4Main.loadChoice(newChoiceFile);
            // Validate the RF recipe too, not just its selected leaf and the DRO solver source.
            Path oldRfRoot = oldChoiceFile.getParent().getParent().getParent().getParent();
            String rfSource = (String) invoke("sourceFingerprint", Path.class, Path.of("src"));
            String rfEnvironment = (String) invoke("pythonEnvironment", Path.class,
                    Path.of(System.getProperty("trb.svu.python")));
            var rf = new TRBSVUExperiment2IdeMain.RfFingerprint(script, rfEnvironment);
            String oldRfProtocol = expProtocol("RF-CSAA", pool, rfSource, script, rfEnvironment,
                    new double[]{.1, .25, .5, .8}, new double[]{1, 2, 5});
            if (!oldRfProtocol.equals(metadata(oldRfRoot.resolve("complete.txt")).get("protocol")))
                throw new IllegalStateException("Baseline RF recipe/source/environment mismatch: " + oldRfRoot);
            // The ranked leaf list/validation score may change when extending the grid;
            // only the selected RF leaf determines the scenario weights.
            boolean sameWeights = "RF".equals(oldChoice.family()) && "RF".equals(newChoice.family())
                    && oldChoice.rfMinLeaf() == newChoice.rfMinLeaf();
            boolean sameSource = Files.isRegularFile(baseline.resolve("complete.txt"))
                    && source.equals(metadata(baseline.resolve("complete.txt")).get("sourceSha256"));
            boolean checkOnly = "check-chi".equals(args[0]);
            String oldProtocol = chiProtocol(pool, source, oldChoice.toString(), new double[]{.1, .25, .5, 1}, rf);
            if (sameWeights && sameSource) {
                importValidation(baseline, output, input, "C-Chi2", pool, source,
                        oldProtocol,
                        chiProtocol(pool, source, newChoice.toString(), LAMBDA, rf),
                        new double[]{.1, .25, .5, 1}, checkOnly);
                if (!checkOnly) enableFinalReuse(input, baseline, output, oldProtocol);
            } else {
                if (checkOnly) throw new IllegalStateException("Baseline weights/source not reusable: " + baseline);
                Files.createDirectories(output);
                Files.writeString(output.resolve("validation_import.txt"),
                        "reusedOrigins=0\nreason=" + (!sameWeights ? "RF selected leaf changed" : "solver source fingerprint differs") + "\nold=" + oldChoice
                                + "\nnew=" + newChoice + "\n", StandardCharsets.UTF_8);
            }
            if (checkOnly) return;
            TRBSVUExperiment2IdeMain.main(new String[]{"--worker", input.toString(),
                    newChoiceFile.toString(), output.toString(), Integer.toString(rep),
                    "4", "14400", "PRIMARY", "C-Chi2", csv(LAMBDA)});
        } else if ("rcsaa".equals(args[0])) {
            if (!"RCSAA".equals(method)) throw new IllegalArgumentException("Expected RCSAA method");
            TRBSVUExperiment2IdeMain.main(new String[]{"--worker", input.toString(),
                    args[6], output.toString(), Integer.toString(rep),
                    "4", "14400", "PRIMARY", "RCSAA", csv(LAMBDA)});
        } else throw new IllegalArgumentException("Unknown operation " + args[0]);
    }

    private static void importValidation(Path baseline, Path output, Path input, String method,
            String pool, String source, String oldProtocol, String newProtocol,
            double[] oldCandidates, boolean checkOnly) throws Exception {
        Path complete = baseline.resolve("complete.txt");
        if (!Files.isRegularFile(complete)) {
            System.out.println("IMPORT method=" + method + " origins=0 reason=no completed baseline");
            return;
        }
        Map<String, String> metadata = metadata(complete);
        if (!source.equals(metadata.get("sourceSha256"))
                || !oldProtocol.equals(metadata.get("protocol"))
                || !"40".equals(metadata.get("queryCount"))) {
            throw new IllegalStateException("Baseline source/protocol mismatch; refusing cross-grid import: " + baseline);
        }
        var instance = TRBSVUSyntheticCaseIO.loadText(
                TRBSVUExperiment1IdeMain.loadQueries(input).get(0).file());
        TRBSVUValidationCheckpoint old = new TRBSVUValidationCheckpoint(
                baseline.resolve("validation_checkpoints"), pool, oldProtocol);
        TRBSVUValidationCheckpoint target = checkOnly ? null : new TRBSVUValidationCheckpoint(
                output.resolve("validation_checkpoints"), pool, newProtocol);
        int count = 0;
        for (double candidate : oldCandidates) for (int origin = 50; origin < 75; origin++) {
            var saved = old.load(method, candidate, origin);
            if (saved.isEmpty()) continue;
            var trace = saved.get();
            var window = instance.validationWindow(origin, 50);
            if (trace.trainingStart() != window.train().get(0).period.tIndex
                    || trace.trainingEnd() != window.train().get(49).period.tIndex
                    || (!"EMPTY_KERNEL_SUPPORT".equals(trace.solverStatus())
                        && (trace.decision() == null || trace.decision().length != instance.params.I)))
                throw new IllegalStateException("Invalid imported window or decision at " + origin);
            // Preserve already finished validation on a retry; final/OOS reuse is checked separately per query.
            if (target != null && target.load(method, candidate, origin).isEmpty()) target.save(trace);
            count++;
        }
        String receipt = "baseline=" + baseline.toAbsolutePath() + "\nqueryPool=" + pool
                + "\nsourceSha256=" + source + "\noldProtocol=" + oldProtocol
                + "\nnewProtocol=" + newProtocol + "\nreusedOrigins=" + count
                + "\nfinalAndOosReuse=CHECK_PARAMETER_AND_ACTUAL_WEIGHTS_PER_QUERY\n";
        if (!checkOnly) TRBSVUCompletionMarker.writeAtomically(output.resolve("validation_import.txt"), receipt);
        System.out.println("IMPORT method=" + method + " origins=" + count + " checked=" + checkOnly);
    }

    private static String expProtocol(String method, String pool, String source, String script,
            String env, double[] bandwidth, double[] leaf) throws Exception {
        return hash(("TRBSVU_EXP1_METHOD_V3|method=" + method + "|origins=25"
                + "|validationTrainingPeriods=50|threads=4|limit=14400|queryPool=" + pool
                + "|retention=" + Arrays.toString(TRBSVUExperiment1Runner.RETENTION)
                + "|bandwidth=" + Arrays.toString(bandwidth) + "|rfMinLeaf=" + Arrays.toString(leaf)
                + "|source=" + source + "|rfScript=" + script + "|pythonEnvironment=" + env)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String chiProtocol(String pool, String source, String selected, double[] lambda,
            TRBSVUExperiment2IdeMain.RfFingerprint rf)
            throws Exception {
        return hash((TRBSVUFormalProtocol.EXPERIMENT12_VERSION
                + "|experiment=2|phase=primary|methods=[C-Chi2]|queryPool=" + pool
                + "|selected=" + selected + "|lambda=" + Arrays.toString(lambda)
                + "|w1=" + Arrays.toString(TRBSVUExperiment2Runner.W1_RADIUS)
                + "|momentKappa=" + Arrays.toString(TRBSVUExperiment2Runner.MOMENT_KAPPA)
                + "|momentValidationLimit=" + TRBSVUExperiment2Runner.MOMENT_VALIDATION_LIMIT_SECONDS
                + "|momentQueryLimit=" + TRBSVUExperiment2Runner.MOMENT_QUERY_LIMIT_SECONDS
                + "|rcsaaCompactFormulation=SWITCHED_COMPACT|threads=4|limit=14400|source=" + source
                + "|pcmScript=NOT_USED|mosekAdapter=NOT_USED|momentPythonEnvironment=NOT_USED"
                + rf.protocolSuffix())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void generateLognormal(Path oldInputRoot, Path newRoot) throws Exception {
        for (int rep = 1; rep <= 5; rep++) {
            Path original = oldInputRoot.resolve(String.format("rep_%03d", rep));
            Map<String, String> m = metadata(original.resolve("instance/manifest.txt"));
            var seeds = TRBSVUSyntheticCaseIO.loadText(original.resolve("instance/instance.tsv")).seeds;
            TRBSVUGenerateModerateCommonCasesMain.generate(newRoot.resolve(String.format("rep_%03d", rep)),
                    TRBSVUSyntheticDemandGenerator.Volatility.MEDIUM,
                    Long.parseLong(m.get("batchSeed")), Long.parseLong(m.get("caseSeed")), rep,
                    Double.valueOf(m.get("cvLower")), Double.valueOf(m.get("cvUpper")),
                    "paired-distribution-v1", seeds, TRBSVUSyntheticDemandGenerator.Distribution.LOGNORMAL);
            var before = TRBSVUSyntheticCaseIO.loadText(original.resolve("instance/instance.tsv"));
            var after = TRBSVUSyntheticCaseIO.loadText(newRoot.resolve(String.format("rep_%03d/instance/instance.tsv", rep)));
            // Demands change, but procurement and every observed context must remain paired.
            if (!Arrays.deepEquals(before.params.q, after.params.q)
                    || !Arrays.deepEquals(before.params.r, after.params.r)
                    || !Arrays.equals(before.params.e, after.params.e)
                    || !Arrays.equals(before.params.p, after.params.p)
                    || !Arrays.equals(before.params.h, after.params.h)
                    || !Arrays.deepEquals(before.params.eligible, after.params.eligible)
                    || before.params.alpha != after.params.alpha || before.params.beta != after.params.beta
                    || !Arrays.equals(before.testContext.values(), after.testContext.values()))
                throw new IllegalStateException("Unpaired lognormal market/query " + rep);
            for (int t = 0; t < before.history.size(); t++)
                if (!Arrays.equals(before.history.get(t).theta.values(), after.history.get(t).theta.values()))
                    throw new IllegalStateException("Unpaired historical context " + t);
            StringBuilder pairs = new StringBuilder("query\tnormal_sha256\tlognormal_sha256\n");
            for (int q = 0; q < 40; q++) {
                String file = String.format("queries/query_%03d.instance.tsv", q);
                Path normalFile = original.resolve(file);
                Path lognormalFile = newRoot.resolve(String.format("rep_%03d", rep)).resolve(file);
                var normal = TRBSVUSyntheticCaseIO.loadText(normalFile);
                var lognormal = TRBSVUSyntheticCaseIO.loadText(lognormalFile);
                if (!Arrays.equals(normal.testContext.values(), lognormal.testContext.values())
                        || !normal.seeds.equals(lognormal.seeds))
                    throw new IllegalStateException("Unpaired lognormal query/seed " + q);
                pairs.append(q).append('\t').append(hash(Files.readAllBytes(normalFile)))
                        .append('\t').append(hash(Files.readAllBytes(lognormalFile))).append('\n');
            }
            Files.writeString(newRoot.resolve(String.format("rep_%03d/distribution_pairing.tsv", rep)),
                    pairs, StandardCharsets.UTF_8);
            System.out.println("LOGNORMAL_PAIRED rep=" + rep + " seed=" + m.get("caseSeed"));
        }
    }

    private static Map<String, String> metadata(Path file) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int split = line.indexOf('=');
            if (split > 0) result.put(line.substring(0, split), line.substring(split + 1));
        }
        return result;
    }
    private static Object invoke(String name, Class<?> type, Object argument) throws Exception {
        return invoke(TRBSVUExperiment1IdeMain.class, name, type, argument);
    }
    private static Object invoke(Class<?> owner, String name, Class<?> type, Object argument) throws Exception {
        Method method = owner.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(null, argument);
    }
    private static void enableFinalReuse(Path input, Path baseline, Path output, String oldProtocol) throws Exception {
        if (baseline.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                || !Files.isRegularFile(baseline.resolve("complete.txt"))) return;
        if (!oldProtocol.equals(metadata(baseline.resolve("complete.txt")).get("protocol")))
            throw new IllegalStateException("Final reuse requires the already verified baseline protocol");
        System.setProperty("trb.svu.finalReuseInput", input.toAbsolutePath().toString());
        System.setProperty("trb.svu.finalReuseBaseline", baseline.toAbsolutePath().toString());
        System.setProperty("trb.svu.finalReuseTarget", output.toAbsolutePath().toString());
        System.setProperty("trb.svu.finalReuseProtocol", oldProtocol);
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String csv(double[] grid) {
        return String.join(",", Arrays.stream(grid).mapToObj(Double::toString).toList());
    }
}
