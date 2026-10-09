package Test.analysis.synthetic;

import Model.RCSAASolverVariant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

/** One-query PCM runtime pilot on unchanged formal inputs; no parameter validation. */
public final class TRBSVUMomentSingleQueryMain {
    public static void main(String[] args) throws Exception {
        boolean dryRun = args.length > 0 && args[0].equals("--dry-run");
        boolean checkOnly = args.length > 0 && args[0].equals("--check-complete");
        if (dryRun || checkOnly) args = Arrays.copyOfRange(args, 1, args.length);
        if (args.length != 5)
            throw new IllegalArgumentException("Usage: [--dry-run] <input> <choice> <output> <rep> <threads>");
        Path input = Path.of(args[0]), choiceFile = Path.of(args[1]), output = Path.of(args[2]);
        int rep = Integer.parseInt(args[3]), threads = Integer.parseInt(args[4]);
        if (threads < 1) throw new IllegalArgumentException("Threads must be positive");
        var query = TRBSVUExperiment1IdeMain.loadQueries(input).get(0);
        var instance = TRBSVUSyntheticCaseIO.loadText(query.file());
        TRBSVUExperiment1IdeMain.verifyFormalDimensions(instance);
        var choice = TRBSVUExperiment4Main.loadChoice(choiceFile);
        int limit = TRBSVUExperiment2Runner.MOMENT_QUERY_LIMIT_SECONDS;
        System.out.println("PCM_SINGLE_QUERY_PLAN rep=" + rep + " query=" + query.index()
                + " kappa=1 validation=false threads=" + threads + " limitSec=" + limit);
        if (dryRun) return;
        String caseHash = digest(Files.readAllBytes(query.file()));
        String recipe = "PCM_SINGLE_QUERY_V1|case=" + caseHash + "|choice="
                + digest(Files.readAllBytes(choiceFile)) + "|threads=" + threads + "|limit=" + limit
                + "|python=" + TRBSVUExperiment2Runner.momentPython()
                + "|pcm=" + digest(Files.readAllBytes(Path.of("analysis/trb_svu/solve_pcm.py")))
                + "|mosek=" + digest(Files.readAllBytes(Path.of("analysis/trb_svu/msk_feasible_solver.py")))
                + "|rf=" + digest(Files.readAllBytes(Path.of("analysis/trb_svu/rf_leaf_weights.py")));
        try (var classBytes = TRBSVUMomentSingleQueryMain.class.getResourceAsStream("TRBSVUMomentSingleQueryMain.class")) {
            recipe += "|pilotClass=" + digest(classBytes.readAllBytes());
        }
        String protocol = digest(recipe.getBytes(StandardCharsets.UTF_8));
        Path queryOutput = output.resolve("queries/query_000");
        if (checkOnly) {
            if (!TRBSVUCompletionMarker.matches(output.resolve("complete.txt"), "protocol=" + protocol, "probeOnly=true")
                    || !TRBSVUCompletionMarker.queryArtifactsComplete(output, java.util.List.of(0),
                    "query_metadata.txt", "solve/experiment2_final_solves.csv",
                    "solve/experiment2_final_weights.csv", "oos/experiment2_summary.csv", "oos/experiment2_draws.csv"))
                throw new IllegalStateException("PCM single-query outputs incomplete or protocol mismatched");
            return;
        }
        try (var lock = TRBSVUWorkerLock.acquire(output)) {
            var marker = output.resolve("complete.txt");
            if (TRBSVUCompletionMarker.matches(marker, "protocol=" + protocol)
                    && TRBSVUCompletionMarker.queryArtifactsComplete(output, java.util.List.of(0),
                    "query_metadata.txt", "solve/experiment2_final_solves.csv",
                    "solve/experiment2_final_weights.csv", "oos/experiment2_summary.csv",
                    "oos/experiment2_draws.csv")) return;
            TRBSVUCompletionMarker.invalidate(marker);
            var settings = new TRBSVUSolveMethods.Settings(threads, limit, 1e-4,
                    RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true, true);
            var forest = new TRBSVUForestWeights(TRBSVUExperiment2Runner.momentPython().toString(),
                    Path.of("analysis/trb_svu/rf_leaf_weights.py").toAbsolutePath());
            var contextual = new TRBSVUExperiment1Runner(settings, forest, 25);
            var runner = new TRBSVUExperiment2Runner(settings, contextual, 25,
                    TRBSVUExperiment2Runner.LAMBDA, TRBSVUExperiment2Runner.W1_RADIUS, null,
                    new TRBSVUFinalCheckpoint(queryOutput.resolve("solve_checkpoints"),
                            queryOutput.resolve("oos_checkpoints"), caseHash, protocol, rep, "2"));
            var result = runner.runWithSelectedParameters(instance, choice,
                    Map.of("C-PCM", 1.0), Map.of(), Map.of());
            if (!result.decisions().containsKey("C-PCM"))
                throw new IllegalStateException("PCM single-query solve did not return a usable decision");
            Path solve = queryOutput.resolve("solve"), oos = queryOutput.resolve("oos");
            TRBSVUResultWriter.writeFinalSolves(solve.resolve("experiment2_final_solves.csv"), rep, "2",
                    instance.params, result.decisions(), Map.of(), result.selectedParameter(),
                    Map.of("C-PCM", "MOMENT_KAPPA"), Map.of("C-PCM", choice.family()), Map.of(),
                    result.effectiveContextBandwidth(), result.finalWeights());
            TRBSVUResultWriter.writeFinalWeights(solve.resolve("experiment2_final_weights.csv"), rep, "2", result.finalWeights());
            TRBSVUResultWriter.writeMarginalMomentInputs(solve.resolve("experiment2_moment_inputs.csv"), rep, "2",
                    instance.params.J, result.finalWeights(), result.selectedParameter());
            TRBSVUResultWriter.writeOosSummary(oos.resolve("experiment2_summary.csv"), rep, "2", result.oos(), result.decisions());
            TRBSVUResultWriter.writeOosDetails(oos.resolve("experiment2_draws.csv"), rep, "2", result.oosDetails(), result.decisions());
            Files.writeString(queryOutput.resolve("query_metadata.txt"), "queryIndex=0\nqueryType=" + query.type()
                    + "\nquerySha256=" + caseHash + "\ncompletedMethods=C-PCM\nselection=FIXED_NO_CV\nkappa=1\n", StandardCharsets.UTF_8);
            TRBSVUCompletionMarker.requireQueryArtifacts(output, java.util.List.of(0), "query_metadata.txt",
                    "solve/experiment2_final_solves.csv", "oos/experiment2_summary.csv", "oos/experiment2_draws.csv");
            TRBSVUCompletionMarker.writeAtomically(marker, "protocol=" + protocol
                    + "\nqueryCount=1\nprobeOnly=true\nmethod=C-PCM\nkappa=1\nselection=FIXED_NO_CV\n");
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
