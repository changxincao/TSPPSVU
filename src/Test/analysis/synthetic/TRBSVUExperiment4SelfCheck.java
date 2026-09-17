package Test.analysis.synthetic;

import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Small connectivity check; it does not use or modify formal Experiment 4 cases. */
public final class TRBSVUExperiment4SelfCheck {
    private TRBSVUExperiment4SelfCheck() { }

    public static void main(String[] args) throws Exception {
        TRBSVUSyntheticCase instance = TRBSVUSyntheticCase.generate(7, 4, 100, 5,
                Distribution.NORMAL, Volatility.LOW,
                new TRBSVUSyntheticCase.Seeds(11, 13, 17, 19, 23));
        Path root = Files.createTempDirectory(Path.of("tmp"), "trb_svu_exp4_check_");
        try {
            Path baseline = root.resolve("baseline").resolve("rep_000");
            Files.createDirectories(baseline.resolve("instance"));
            Files.createDirectories(baseline.resolve("validation"));
            TRBSVUSyntheticCaseIO.saveText(instance,
                    baseline.resolve("instance").resolve("instance.tsv"));
            Files.writeString(baseline.resolve("validation")
                            .resolve("experiment1_selected_context.csv"),
                    "replication,family,validation_selected_B,validation_cost,validation_sd,bandwidth_order\n"
                            + "0,EXPONENTIAL,10.0,1.0,1.0,\"10.0\"\n",
                    StandardCharsets.UTF_8);
            Path output = root.resolve("output");
            String[] run = {"0", root.resolve("baseline").toString(),
                    output.toString(), "1", "120", "0.1"};
            TRBSVUExperiment4Main.main(run);
            // A second identical invocation must restore both method solves safely.
            TRBSVUExperiment4Main.main(run);
            Path replication = output.resolve("rep_000");
            require(Files.isRegularFile(replication.resolve("experiment4_complete.txt")),
                    "Experiment 4 completion marker missing.");
            require(Files.readAllLines(replication.resolve("experiment4_comparison.csv")).size() == 2,
                    "Experiment 4 summary row missing.");
            String summary = Files.readString(replication.resolve("experiment4_comparison.csv"));
            require(summary.startsWith("replication,lambda,effective_B,certificate_status,certificate_holds,")
                            && (summary.contains(",HOLDS,true,") || summary.contains(",FAILS,false,")),
                    "Experiment 4 certificate status columns are missing or inconsistent.");
            require(Files.list(replication.resolve("solve_checkpoints")).count() == 2,
                    "Experiment 4 did not checkpoint both methods.");
            try (var checkpoints = Files.list(replication.resolve("solve_checkpoints"))) {
                require(checkpoints.filter(Files::isRegularFile).allMatch(file -> {
                    try {
                        return Files.readString(file).contains("certifiedOptimal=true");
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }), "Experiment 4 smoke solutions were not certified.");
            }
            require(Files.list(replication.resolve("oos_checkpoints")).count() == 4,
                    "Experiment 4 did not save both OOS summaries and draws.");
            System.out.println("TRBSVUExperiment4SelfCheck PASS");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
