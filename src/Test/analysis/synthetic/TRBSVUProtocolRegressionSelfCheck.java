package Test.analysis.synthetic;

import Model.Solution;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.ContextualChoice;
import Test.analysis.synthetic.TRBSVUExperiment4Runner.Certificate;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Fast regression checks for experiment protocol plumbing; it does not invoke a solver. */
public final class TRBSVUProtocolRegressionSelfCheck {
    private TRBSVUProtocolRegressionSelfCheck() { }

    public static void main(String[] args) throws Exception {
        require(Arrays.equals(TRBSVUExperiment2Runner.LAMBDA,
                        new double[]{0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 50, 100}),
                "Experiment 2 lambda grid is not the locked 11-point tuning grid.");
        require(TRBSVUFormalProtocol.CARRIERS == 15
                        && TRBSVUFormalProtocol.LANES == 50
                        && TRBSVUFormalProtocol.HISTORY_PERIODS == 75
                        && TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS == 50
                        && TRBSVUFormalProtocol.VALIDATION_ORIGINS == 25,
                "Formal scale or 50+25 validation protocol drifted.");
        TRBSVUSyntheticCase seedCase = TRBSVUSyntheticCase.generate(7, 4, 75, 1,
                Distribution.NORMAL, Volatility.LOW,
                new TRBSVUSyntheticCase.Seeds(11, 13, 17, 19, 23));
        require(TRBSVUExperiment1Runner.forestSeed(seedCase, seedCase.history)
                        == seedCase.seeds.contexts() + 75L,
                "RF seed is not derived from the final training period.");
        TRBSVUSyntheticCase.ValidationWindow first = seedCase.validationWindow(50, 50);
        TRBSVUSyntheticCase.ValidationWindow last = seedCase.validationWindow(74, 50);
        require(first.train().get(0).period.tIndex == 0
                        && first.train().get(49).period.tIndex == 49
                        && first.realized().period.tIndex == 50
                        && last.train().get(0).period.tIndex == 24
                        && last.train().get(49).period.tIndex == 73
                        && last.realized().period.tIndex == 74,
                "Formal rolling validation windows are not 1:50->51 through 25:74->75.");
        ContextualChoice rf = new ContextualChoice("RF", Double.NaN, 1.0, 0.2,
                List.of(1.0, 2.0, 5.0, 10.0), 5);
        require(TRBSVUExperiment4Main.usesRandomForest(rf),
                "Experiment 4 does not recognize the stored RF family name.");
        require("UNRESOLVED".equals(TRBSVUExperiment4Main.certificateStatus(null))
                        && "NA".equals(TRBSVUExperiment4Main.certificateHolds(null)),
                "Unavailable certificates are not represented as unresolved.");
        require("HOLDS".equals(TRBSVUExperiment4Main.certificateStatus(
                        new Certificate(1, 1, 0, 1, 1, true)))
                        && "FAILS".equals(TRBSVUExperiment4Main.certificateStatus(
                        new Certificate(1, 0.5, 0, 1, 0.5, false))),
                "Resolved certificate statuses are incorrect.");

        Path root = Files.createTempDirectory("trb_svu_protocol_check_");
        try {
            verifyIdeAggregation(root);
            verifyFinalCheckpointInvalidation(root);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
        System.out.println("TRBSVUProtocolRegressionSelfCheck PASS");
    }

    private static void verifyIdeAggregation(Path root) throws Exception {
        Path input = root.resolve("input");
        Path output = root.resolve("output");
        Path instance = input.resolve("rep_000").resolve("instance").resolve("instance.tsv");
        Files.createDirectories(instance.getParent());
        Files.writeString(instance, "frozen-instance", StandardCharsets.UTF_8);
        Path queryManifest = input.resolve("rep_000").resolve("queries").resolve("queries.tsv");
        Files.createDirectories(queryManifest.getParent());
        StringBuilder manifest = new StringBuilder(
                "query_index\tquery_type\tsource_candidate\tdemand_ratio\tinstance_file\n");
        int queryCount = TRBSVUFormalProtocol.RANDOM_QUERIES
                + TRBSVUFormalProtocol.HIGH_R_QUERIES;
        for (int q = 0; q < queryCount; q++) {
            String fileName = String.format("query_%03d.instance.tsv", q);
            Files.writeString(queryManifest.getParent().resolve(fileName),
                    "frozen-instance", StandardCharsets.UTF_8);
            manifest.append(q).append('\t')
                    .append(q < TRBSVUFormalProtocol.RANDOM_QUERIES ? "RANDOM" : "HIGH_R")
                    .append('\t').append(q).append("\t1.0\t").append(fileName).append('\n');
        }
        Files.writeString(queryManifest, manifest, StandardCharsets.UTF_8);
        writeCandidate(output, "CSAA-Exp", new ContextualChoice(
                "EXPONENTIAL", 5.0, 10.0, 1.0, List.of(5.0)));
        writeCandidate(output, "CSAA-Gau", new ContextualChoice(
                "GAUSSIAN", 3.0, 9.0, 1.0, List.of(3.0)));
        writeCandidate(output, "CSAA-Epa", new ContextualChoice(
                "EPANECHNIKOV", 1.0, 8.0, 1.0, List.of(1.0)));
        writeCandidate(output, "CSAA-Tri", new ContextualChoice(
                "TRIANGULAR", 1.0, 7.5, 1.0, List.of(1.0)));
        writeCandidate(output, "RF-CSAA", new ContextualChoice(
                "RF", Double.NaN, 7.0, 1.0, List.of(5.0, 10.0), 5));
        TRBSVUExperiment1IdeMain.aggregateContextualChoices(input, output, Set.of(0));
        ContextualChoice selected = TRBSVUExperiment4Main.loadChoice(output.resolve("rep_000")
                .resolve("validation").resolve("experiment1_selected_context.csv"));
        require("RF".equals(selected.family()), "IDE aggregation did not select the global C*.");
        require(selected.rfMinLeaf() == 5, "IDE aggregation lost the selected RF min leaf.");
        require(Files.mismatch(instance, output.resolve("rep_000").resolve("instance")
                .resolve("instance.tsv")) == -1L, "IDE aggregation did not preserve the frozen instance.");
    }

    private static void writeCandidate(Path output, String method, ContextualChoice choice)
            throws Exception {
        TRBSVUResultWriter.writeContextualChoice(output.resolve("rep_000").resolve(method)
                .resolve("queries").resolve("query_000").resolve("validation")
                .resolve("context_candidate.csv"), 0, choice);
    }

    private static void verifyFinalCheckpointInvalidation(Path root) throws Exception {
        Path solve = root.resolve("solve");
        Path oos = root.resolve("oos");
        Solution solution = new Solution();
        solution.objValue = 1.0;
        solution.y = new double[]{1.0, 0.0};
        solution.solveTimeSec = 0.1;
        solution.solverStatus = "Optimal";
        solution.bestBound = 1.0;
        solution.relativeGap = 0.0;
        solution.certifiedOptimal = true;
        new TRBSVUFinalCheckpoint(solve, oos, "instance", "old-protocol", 0, "2")
                .save("RCSAA", 1.0, solution);
        TRBSVUFinalCheckpoint current = new TRBSVUFinalCheckpoint(
                solve, oos, "instance", "new-protocol", 0, "2");
        require(current.load("RCSAA", 1.0).isEmpty(),
                "A stale final checkpoint was reused instead of treated as a cache miss.");
        current.save("RCSAA", 1.0, solution);
        require(current.load("RCSAA", 1.0).isPresent(),
                "The current protocol could not replace a stale final checkpoint.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
