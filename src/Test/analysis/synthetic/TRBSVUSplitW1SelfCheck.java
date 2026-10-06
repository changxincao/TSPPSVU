package Test.analysis.synthetic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Isolated, native-solver-free split-CV recovery and integrity regression. */
public final class TRBSVUSplitW1SelfCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("input choice existingSmallOutput");
        Path root = Files.createTempDirectory("w1-split-check-");
        try {
            var build = TRBSVUSplitW1Main.class.getDeclaredMethod("context", Path.class, Path.class);
            build.setAccessible(true);
            Object context = build.invoke(null, Path.of(args[0]), Path.of(args[1]));
            var protocol = TRBSVUSplitW1Main.class.getDeclaredMethod("protocol", context.getClass(), double[].class);
            protocol.setAccessible(true);
            var poolMethod = context.getClass().getDeclaredMethod("pool"); poolMethod.setAccessible(true);
            String pool = (String) poolMethod.invoke(context);
            String smallProtocol = (String) protocol.invoke(null, context, TRBSVUSplitW1Main.SMALL);
            String tailProtocol = (String) protocol.invoke(null, context, TRBSVUSplitW1Main.TAIL);
            var old = new TRBSVUValidationCheckpoint(Path.of(args[2]).resolve("validation_checkpoints"), pool, smallProtocol);
            var sample = old.load("C-W1", TRBSVUSplitW1Main.SMALL[0], 50).orElseThrow();
            var checkpoint = new TRBSVUValidationCheckpoint(root.resolve("validation_checkpoints"), pool, tailProtocol);
            expectFailure(() -> TRBSVUSplitW1Main.main(new String[]{"check-validation", args[0], args[1], root.toString(), "1"}));
            for (double radius : TRBSVUSplitW1Main.TAIL) for (int origin = 50; origin < 75; origin++) {
                checkpoint.save(new TRBSVUValidationTrace("C-W1", radius, origin, origin-50, origin-1,
                        sample.effectiveContextBandwidth(), sample.scenarioCount(), sample.positiveWeightCount(),
                        sample.effectiveSampleSize(), sample.trainingObjective(), sample.solverStatus(), sample.bestBound(),
                        sample.relativeGap(), sample.solveTimeSec(), sample.optimizerTimeSec(), sample.certifiedOptimal(),
                        sample.decision(), sample.realizedValidationCost()));
            }
            String[] call = {"validate", args[0], args[1], root.toString(), "1"};
            TRBSVUSplitW1Main.main(call); // all restored: no optimizer call, no final/OOS call
            call[0]="check-validation"; TRBSVUSplitW1Main.main(call);
            if (Files.exists(root.resolve("queries"))) throw new AssertionError("Validation-only ran finals");
            Path file = root.resolve("validation_checkpoints/C-W1_p"
                    + Long.toUnsignedString(Double.doubleToLongBits(TRBSVUSplitW1Main.TAIL[0]),16) + "_o50.checkpoint");
            byte[] original = Files.readAllBytes(file);
            Files.writeString(file, Files.readString(file).replace("protocolFingerprint="+tailProtocol,
                    "protocolFingerprint=wrong-protocol"));
            expectFailure(() -> TRBSVUSplitW1Main.main(call));
            Files.write(file, original);
            Files.writeString(file, Files.readString(file).replace("solveTimeSec=", "solveTimeSec=1"));
            expectFailure(() -> TRBSVUSplitW1Main.main(call));
            System.out.println("PASS: compatible existing small protocol; 150 restored tail origins; no final solve; "
                    + "missing marker, wrong protocol and tampered artifact rejected");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked task) throws Exception {
        try { task.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected rejection");
    }
}
