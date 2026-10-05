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

/** Stage RCSAA grids without changing the frozen solver or silently invalidating saved origins. */
public final class TRBSVURcsaaStagedGridMain {
    private TRBSVURcsaaStagedGridMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 8 || !("run".equals(args[0]) || "check".equals(args[0])))
            throw new IllegalArgumentException("run|check input baseline output rep choice oldGrid newGrid");
        boolean check = "check".equals(args[0]);
        Path input = Path.of(args[1]), baseline = Path.of(args[2]), output = Path.of(args[3]);
        Path choiceFile = Path.of(args[5]);
        if (baseline.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Stages must use distinct output directories");
        double[] oldGrid = grid(args[6]), newGrid = grid(args[7]);
        var queries = TRBSVUExperiment1IdeMain.loadQueries(input);
        if (queries.size() != 40) throw new IllegalArgumentException("Expected 40 frozen queries");
        String pool = TRBSVUExperiment1IdeMain.queryPoolFingerprint(queries);
        Method fingerprint = TRBSVUExperiment2IdeMain.class.getDeclaredMethod("sourceFingerprint", Path.class);
        fingerprint.setAccessible(true);
        String source = (String) fingerprint.invoke(null, Path.of("src"));
        String selected = TRBSVUExperiment4Main.loadChoice(choiceFile).toString();
        String oldProtocol = protocol(pool, source, selected, oldGrid);
        String newProtocol = protocol(pool, source, selected, newGrid);
        Path oldDirectory = baseline.resolve("validation_checkpoints");
        int imported = 0;
        StringBuilder audit = new StringBuilder("candidate,origin,source_sha256\n");
        if (Files.isDirectory(oldDirectory)) {
            var instance = TRBSVUSyntheticCaseIO.loadText(queries.get(0).file());
            var old = new TRBSVUValidationCheckpoint(oldDirectory, pool, oldProtocol);
            var target = check ? null : new TRBSVUValidationCheckpoint(
                    output.resolve("validation_checkpoints"), pool, newProtocol);
            for (double candidate : newGrid) {
                if (Arrays.stream(oldGrid).noneMatch(v -> v == candidate)) continue;
                for (int origin = 50; origin < 75; origin++) {
                    Path file = oldDirectory.resolve("RCSAA_p" + Long.toUnsignedString(
                            Double.doubleToLongBits(candidate), 16) + "_o" + origin + ".checkpoint");
                    if (!Files.isRegularFile(file)) continue;
                    var saved = old.load("RCSAA", candidate, origin).orElseThrow(
                            () -> new IllegalStateException("Frozen input/choice/source/settings mismatch: " + file));
                    var window = instance.validationWindow(origin, 50);
                    if (saved.trainingStart() != window.train().get(0).period.tIndex
                            || saved.trainingEnd() != window.train().get(49).period.tIndex
                            || saved.decision() == null || saved.decision().length != instance.params.I
                            || Arrays.stream(saved.decision()).anyMatch(v -> !Double.isFinite(v))
                            || !Double.isFinite(saved.realizedValidationCost()))
                        throw new IllegalStateException("Invalid saved origin: " + file);
                    if (target != null && target.load("RCSAA", candidate, origin).isEmpty()) target.save(saved);
                    imported++;
                    audit.append(candidate).append(',').append(origin).append(',')
                            .append(hash(Files.readAllBytes(file))).append('\n');
                }
            }
        }
        if (!check) {
            TRBSVUCompletionMarker.writeAtomically(output.resolve("validation_import.csv"), audit.toString());
            TRBSVUCompletionMarker.writeAtomically(output.resolve("validation_import.txt"),
                    "baseline=" + baseline + "\nsourceSha256=" + source + "\nqueryPool=" + pool
                            + "\noldProtocol=" + oldProtocol + "\nnewProtocol=" + newProtocol
                            + "\nreusedOrigins=" + imported + "\noldGrid=" + args[6] + "\nnewGrid=" + args[7] + "\n");
            Path complete = baseline.resolve("complete.txt");
            if (Files.isRegularFile(complete)) {
                var metadata = metadata(complete);
                if (!oldProtocol.equals(metadata.get("protocol")) || !source.equals(metadata.get("sourceSha256"))
                        || !"40".equals(metadata.get("queryCount")))
                    throw new IllegalStateException("Completed baseline protocol mismatch: " + complete);
                System.setProperty("trb.svu.finalReuseInput", input.toAbsolutePath().toString());
                System.setProperty("trb.svu.finalReuseBaseline", baseline.toAbsolutePath().toString());
                System.setProperty("trb.svu.finalReuseTarget", output.toAbsolutePath().toString());
                System.setProperty("trb.svu.finalReuseProtocol", oldProtocol);
            }
        }
        System.out.println("RCSAA_STAGE checked=" + check + " reusedOrigins=" + imported
                + " grid=" + Arrays.toString(newGrid) + " protocol=" + newProtocol);
        if (!check) TRBSVUExperiment2IdeMain.main(new String[]{"--worker", input.toString(), choiceFile.toString(),
                output.toString(), args[4], "4", "0", "PRIMARY", "RCSAA", args[7]});
    }

    private static String protocol(String pool, String source, String selected, double[] lambda) throws Exception {
        return hash((TRBSVUFormalProtocol.EXPERIMENT12_VERSION
                + "|experiment=2|phase=primary|methods=[RCSAA]|queryPool=" + pool
                + "|selected=" + selected + "|lambda=" + Arrays.toString(lambda)
                + "|w1=" + Arrays.toString(TRBSVUExperiment2Runner.W1_RADIUS)
                + "|momentKappa=" + Arrays.toString(TRBSVUExperiment2Runner.MOMENT_KAPPA)
                + "|momentValidationLimit=" + TRBSVUExperiment2Runner.MOMENT_VALIDATION_LIMIT_SECONDS
                + "|momentQueryLimit=" + TRBSVUExperiment2Runner.MOMENT_QUERY_LIMIT_SECONDS
                + "|rcsaaCompactFormulation=SWITCHED_COMPACT|threads=4|limit=0|source=" + source
                + "|pcmScript=NOT_USED|mosekAdapter=NOT_USED|momentPythonEnvironment=NOT_USED")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static double[] grid(String csv) {
        double[] values = Arrays.stream(csv.split(",")).mapToDouble(Double::parseDouble).toArray();
        if (values.length == 0 || Arrays.stream(values).anyMatch(v -> !Double.isFinite(v) || v <= 0)
                || Arrays.stream(values).distinct().count() != values.length)
            throw new IllegalArgumentException("Invalid positive lambda grid");
        return values;
    }

    private static Map<String, String> metadata(Path file) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int split = line.indexOf('=');
            if (split <= 0 || values.put(line.substring(0, split), line.substring(split + 1)) != null)
                throw new IllegalStateException("Invalid metadata: " + file);
        }
        return values;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
