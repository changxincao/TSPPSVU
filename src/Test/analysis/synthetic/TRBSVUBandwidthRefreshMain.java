package Test.analysis.synthetic;

import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.ContextualChoice;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.WeightResult;
import Test.analysis.synthetic.TRBSVUScenarioWeights.Kernel;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Incrementally merges the already-computed Experiment 1 validation grid with
 * B=0.8/0.9 and refreshes final/OOS results only when the selected bandwidth changes.
 */
public final class TRBSVUBandwidthRefreshMain {
    private static final String EXPERIMENT = "1-B-REFRESH";
    private static final double[] EXTENSION = {0.8, 0.9};

    private TRBSVUBandwidthRefreshMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 8) throw new IllegalArgumentException(
                "Usage: <replication-input> <old-method-output> <extension.tsv> "
                        + "<refresh-output> <replication> <method> <solver-threads> <limit-seconds>");
        Path input = Path.of(args[0]);
        Path oldOutput = Path.of(args[1]);
        Path extension = Path.of(args[2]);
        Path output = Path.of(args[3]);
        int replication = Integer.parseInt(args[4]);
        String method = args[5];
        int threads = Integer.parseInt(args[6]);
        int limitSeconds = Integer.parseInt(args[7]);
        Kernel kernel = kernel(method);
        if (threads < 1 || limitSeconds < 1) throw new IllegalArgumentException("Invalid solve settings.");

        List<Score> oldScores = loadOldScores(oldOutput, method);
        ContextualChoice oldChoice = TRBSVUExperiment4Main.loadChoice(oldOutput.resolve("queries")
                .resolve("query_000").resolve("validation").resolve("context_candidate.csv"));
        if (!kernel.name().equals(oldChoice.family()))
            throw new IllegalStateException("Old contextual family does not match " + method
                    + ": " + oldChoice.family());
        List<Score> rankedOld = oldScores.stream().filter(Score::valid).sorted(SCORE_ORDER).toList();
        if (rankedOld.isEmpty() || Double.doubleToLongBits(rankedOld.get(0).bandwidth())
                != Double.doubleToLongBits(oldChoice.bandwidth()))
            throw new IllegalStateException("Old selected bandwidth is inconsistent with old CV: method="
                    + method + ", choice=" + oldChoice.bandwidth() + ", ranked=" + rankedOld);
        List<Score> allScores = merge(oldScores, loadExtension(extension, kernel));
        List<Score> ranked = allScores.stream().filter(Score::valid).sorted(SCORE_ORDER).toList();
        if (ranked.isEmpty()) throw new IllegalStateException("No valid bandwidth for " + method);
        Score selected = ranked.get(0);
        List<Double> order = ranked.stream().map(Score::bandwidth).toList();
        ContextualChoice newChoice = new ContextualChoice(kernel.name(), selected.bandwidth(),
                selected.mean(), selected.sd(), order);
        boolean changed = Double.doubleToLongBits(oldChoice.bandwidth())
                != Double.doubleToLongBits(newChoice.bandwidth());
        Files.createDirectories(output);
        writeSelection(output.resolve("selection.csv"), replication, method, oldChoice, newChoice,
                changed, allScores);
        TRBSVUResultWriter.writeContextualChoice(output.resolve("context_candidate.csv"),
                replication, newChoice);
        if (!changed) {
            Files.writeString(output.resolve("complete.txt"), "changed=false\n", StandardCharsets.UTF_8);
            System.out.printf(Locale.ROOT, "UNCHANGED rep=%d method=%s B=%.17g%n",
                    replication, method, oldChoice.bandwidth());
            return;
        }

        Settings settings = new Settings(threads, limitSeconds, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        TRBSVUExperiment1Runner contextual = new TRBSVUExperiment1Runner(settings,
                (training, query, seed, minLeaf) -> {
                    throw new UnsupportedOperationException("RF is not used by bandwidth refresh.");
                }, TRBSVUFormalProtocol.VALIDATION_ORIGINS);
        List<TRBSVUExperiment1IdeMain.QueryInput> queries =
                TRBSVUExperiment1IdeMain.loadQueries(input);
        TRBSVUSyntheticCase reference = TRBSVUSyntheticCaseIO.loadText(queries.get(0).file());
        TRBSVUExperiment1IdeMain.verifyFormalDimensions(reference);
        String protocol = sha256(("TRBSVU_BANDWIDTH_REFRESH_V1|method=" + method
                + "|B=" + selected.bandwidth() + "|rank=" + order
                + "|threads=" + threads + "|limit=" + limitSeconds
                + "|queryPool=" + TRBSVUExperiment1IdeMain.queryPoolFingerprint(queries))
                .getBytes(StandardCharsets.UTF_8));
        for (TRBSVUExperiment1IdeMain.QueryInput query : queries) {
            TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(query.file());
            TRBSVUExperiment1IdeMain.verifyFormalDimensions(instance);
            TRBSVUExperiment1IdeMain.verifySharedTrainingCore(reference, instance, query.index());
            WeightResult weightResult = contextual.contextualWeightResult(instance, instance.history,
                    instance.testContext, newChoice);
            Path queryOutput = output.resolve("queries")
                    .resolve(String.format(Locale.ROOT, "query_%03d", query.index()));
            String queryHash = sha256(Files.readAllBytes(query.file()));
            TRBSVUFinalCheckpoint checkpoint = new TRBSVUFinalCheckpoint(
                    queryOutput.resolve("solve_checkpoints"), queryOutput.resolve("oos_checkpoints"),
                    queryHash, protocol, replication, EXPERIMENT);
            Solution solution = checkpoint.load(method, selected.bandwidth()).orElse(null);
            if (solution == null) {
                long started = System.nanoTime();
                solution = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                        weightResult.weights(), instance.testContext, Method.NOMINAL, 0.0, settings);
                solution.solveTimeSec = (System.nanoTime() - started) / 1.0e9;
                requireIncumbent(solution, instance.params.I, method, query.index());
                checkpoint.save(method, selected.bandwidth(), solution);
            }
            requireIncumbent(solution, instance.params.I, method, query.index());
            TRBSVUSolveMethods.OosEvaluation evaluation = TRBSVUSolveMethods.evaluateDetailed(
                    instance.params, solution.y, instance.oos);
            checkpoint.saveOos(method, evaluation.summary(), evaluation.draws(), solution);
            writeQuery(queryOutput, replication, instance, method, newChoice,
                    weightResult, solution, evaluation, query);
        }
        Files.writeString(output.resolve("complete.txt"), "changed=true\noldB="
                + oldChoice.bandwidth() + "\nnewB=" + newChoice.bandwidth() + "\n",
                StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "REFRESHED rep=%d method=%s oldB=%.17g newB=%.17g%n",
                replication, method, oldChoice.bandwidth(), newChoice.bandwidth());
    }

    private static void writeQuery(Path output, int replication, TRBSVUSyntheticCase instance,
                                   String method, ContextualChoice choice, WeightResult weights,
                                   Solution solution, TRBSVUSolveMethods.OosEvaluation evaluation,
                                   TRBSVUExperiment1IdeMain.QueryInput query) throws Exception {
        Map<String, Solution> decisions = Map.of(method, solution);
        Map<String, List<Sample>> finalWeights = Map.of(method, weights.weights());
        TRBSVUResultWriter.writeFinalSolves(output.resolve("solve").resolve("final_solve.csv"),
                replication, EXPERIMENT, instance.params, decisions,
                Map.of(method, choice.validationCost()), Map.of(method, choice.bandwidth()),
                Map.of(method, "BANDWIDTH"), Map.of(method, choice.family()),
                Map.of(method, choice.bandwidth()), Map.of(method, weights.effectiveBandwidth()),
                finalWeights);
        TRBSVUResultWriter.writeFinalWeights(output.resolve("solve").resolve("final_weights.csv"),
                replication, EXPERIMENT, finalWeights);
        TRBSVUResultWriter.writeOosSummary(output.resolve("oos").resolve("summary.csv"),
                replication, EXPERIMENT, Map.of(method, evaluation.summary()), decisions);
        TRBSVUResultWriter.writeOosDetails(output.resolve("oos").resolve("draws.csv"),
                replication, EXPERIMENT, Map.of(method, evaluation.draws()), decisions);
        Files.writeString(output.resolve("query_metadata.txt"), "queryIndex=" + query.index()
                + "\nqueryType=" + query.type() + "\nsourceCandidate=" + query.sourceCandidate()
                + "\ndemandRatio=" + query.demandRatio() + "\n", StandardCharsets.UTF_8);
    }

    private static List<Score> loadOldScores(Path oldOutput, String method) throws Exception {
        Path file = oldOutput.resolve("queries").resolve("query_000")
                .resolve("validation").resolve("summary.csv");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Score> result = new ArrayList<>();
        for (int row = 1; row < lines.size(); row++) {
            String[] f = lines.get(row).split(",", -1);
            if (f.length < 11 || !method.equals(f[2])) continue;
            result.add(new Score(Double.parseDouble(f[3]), Double.parseDouble(f[4]),
                    Double.parseDouble(f[5]), Boolean.parseBoolean(f[10])));
        }
        if (result.isEmpty()) throw new IllegalStateException("Missing old validation summary: " + file);
        return result;
    }

    private static List<Score> loadExtension(Path file, Kernel kernel) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Score> result = new ArrayList<>();
        for (int row = 1; row < lines.size(); row++) {
            String[] f = lines.get(row).split("\\t", -1);
            if (f.length < 6 || !kernel.name().equals(f[0])) continue;
            double bandwidth = Double.parseDouble(f[1]);
            if (!isExtension(bandwidth)) continue;
            result.add(new Score(bandwidth, Double.parseDouble(f[3]),
                    Double.parseDouble(f[4]), Boolean.parseBoolean(f[2])));
        }
        if (result.size() != EXTENSION.length)
            throw new IllegalStateException("Expected B=0.8/0.9 in " + file + "; found " + result);
        return result;
    }

    private static List<Score> merge(List<Score> oldScores, List<Score> extension) {
        Map<Long, Score> merged = new LinkedHashMap<>();
        for (Score score : oldScores) merged.put(Double.doubleToLongBits(score.bandwidth()), score);
        for (Score score : extension) merged.put(Double.doubleToLongBits(score.bandwidth()), score);
        return List.copyOf(merged.values());
    }

    private static void writeSelection(Path file, int replication, String method,
                                       ContextualChoice oldChoice, ContextualChoice newChoice,
                                       boolean changed, List<Score> scores) throws Exception {
        StringBuilder out = new StringBuilder("replication,method,old_B,new_B,changed,candidate,valid,mean,sd\n");
        for (Score score : scores) out.append(String.format(Locale.ROOT,
                "%d,%s,%.17g,%.17g,%s,%.17g,%s,%.17g,%.17g%n",
                replication, method, oldChoice.bandwidth(), newChoice.bandwidth(), changed,
                score.bandwidth(), score.valid(), score.mean(), score.sd()));
        Files.writeString(file, out, StandardCharsets.UTF_8);
    }

    private static void requireIncumbent(Solution solution, int carriers,
                                         String method, int query) {
        if (solution == null || solution.y == null || solution.y.length != carriers
                || !Double.isFinite(solution.objValue))
            throw new IllegalStateException("No usable incumbent for " + method + " query=" + query);
        for (double value : solution.y) {
            if (!Double.isFinite(value) || Math.abs(value - Math.rint(value)) > 1e-5)
                throw new IllegalStateException("Invalid first-stage decision for " + method
                        + " query=" + query + ": " + value);
        }
    }

    private static Kernel kernel(String method) {
        return switch (method) {
            case "CSAA-Exp" -> Kernel.EXPONENTIAL;
            case "CSAA-Gau" -> Kernel.GAUSSIAN;
            case "CSAA-Epa" -> Kernel.EPANECHNIKOV;
            case "CSAA-Tri" -> Kernel.TRIANGULAR;
            default -> throw new IllegalArgumentException("Not a kernel method: " + method);
        };
    }

    private static boolean isExtension(double bandwidth) {
        for (double value : EXTENSION)
            if (Double.doubleToLongBits(value) == Double.doubleToLongBits(bandwidth)) return true;
        return false;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Score(double bandwidth, double mean, double sd, boolean valid) { }

    private static final Comparator<Score> SCORE_ORDER = (left, right) -> {
        if (TRBSVUStatistics.better(left.mean(), left.sd(), left.bandwidth(),
                right.mean(), right.sd(), right.bandwidth())) return -1;
        if (TRBSVUStatistics.better(right.mean(), right.sd(), right.bandwidth(),
                left.mean(), left.sd(), left.bandwidth())) return 1;
        return 0;
    };
}
