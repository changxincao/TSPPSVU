package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Model.Solution;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Temporary hand-written fixtures only. Never calls a generator or native optimizer. */
public final class TRBSVUScaleIoSelfCheck {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void rejects(Checked action) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (Exception expected) { rejected = true; }
        check(rejected, "Expected rejection");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass pending configuration path");
        Path temp = Files.createTempDirectory("trb-scale-io-test-");
        try {
            var pending = new TRBSVUScaleExperiment.Configuration(Path.of(args[0]));
            check(pending.cases.size() == 18, "18 cases");
            rejects(pending::requireFrozen);
            Path planned = temp.resolve("plan");
            TRBSVUScaleExperiment.preparePlan(planned, Path.of(args[0]));
            check(Files.readAllLines(planned.resolve("tasks.tsv")).size() == 91, "90 tasks plus header");
            check(!Files.exists(planned.resolve("inputs")), "Plan must not generate inputs");

            Path config = temp.resolve("fixture.properties");
            Files.writeString(config, "protocol=TRBSVU_SCALE_V1\nstate=FROZEN\nsizes=1x1x2\nreplications=1\n"
                    + "solverThreads=4\nlimitSeconds=7200\ndataProtocol=TEST_ONLY\ndataProtocolSource=fixture\n"
                    + "contextFamily=TRIANGULAR\ncontextParameter=1\ncontextParameterSource=fixture\n"
                    + "rcsaaLambda=0.5\nchi2Lambda=0.5\nw1Radius=0.01\nrobustParameterSource=fixture\n");
            Path root = temp.resolve("fixture");
            TRBSVUScaleExperiment.preparePlan(root, config);
            var cfg = new TRBSVUScaleExperiment.Configuration(config);
            var spec = cfg.cases.get(0);
            var params = new ProcurementParams(List.of("C1"), 1, new double[]{5}, new double[]{2},
                    new double[]{1}, new double[][]{{20}}, new double[][]{{1}}, new boolean[][]{{true}}, 1, 1);
            LocalDate day = LocalDate.of(2020, 1, 1);
            List<Sample> history = List.of(
                    new Sample(0, new PeriodData(0, day, day, new double[]{10}, 0, 0, 0, 0),
                            new CovariateVector(new double[]{0, 0, 0, 0}), 0.5),
                    new Sample(1, new PeriodData(1, day.plusDays(7), day.plusDays(7), new double[]{12}, 0, 0, 0, 0),
                            new CovariateVector(new double[]{0.5, 0.5, 0.5, 0.5}), 0.5));
            var data = new TRBSVUSyntheticCase(params, List.of("L1"), history,
                    new CovariateVector(new double[]{0.5, 1, 0.5, 0.5}), List.of(),
                    new TRBSVUSyntheticCase.Seeds(1, 2, 3, 4, 5));
            var weights = TRBSVUScenarioWeights.copyWithWeights(history, new double[]{0, 1}, false);
            TRBSVUScaleExperiment.writeInput(root, cfg, spec, data, weights, "Temporary hand-written fixture, not experiment data\n");
            var loaded = TRBSVUScaleExperiment.readInputs(root, cfg, spec);
            check(loaded.instance().oos.isEmpty(), "No OOS");
            check(loaded.contextual().size() == 2 && loaded.contextual().get(0).weight == 0, "Keep zero-weight pool rows");
            check(loaded.instance().seeds.equals(data.seeds), "Seeds round trip");
            check(TRBSVUScenarioWeights.equal(loaded.instance().history).get(0).weight == 0.5, "SAA equal weights");
            Path instance = root.resolve("inputs").resolve(spec.id()).resolve("instance.tsv");
            rejects(() -> TRBSVUSyntheticCaseIO.loadText(instance));
            check(TRBSVUScaleExperiment.settings(cfg).switchedCompactDual(), "Switched compact flag");
            check(!TRBSVUScaleExperiment.settings(cfg).repairCuts(), "No repair cuts");

            Path output = temp.resolve("output");
            Files.createDirectories(output);
            TRBSVUResultWriter.writeFinalWeights(output.resolve("weights.csv"), 0, "SCALE", Map.of("CSAA", weights));
            Solution result = new Solution(100, new double[]{1}, 12);
            result.bestBound = 99; result.relativeGap = 0.01; result.optimizerTimeSec = 10;
            result.solverStatus = "TEST_FEASIBLE";
            TRBSVUScaleExperiment.writeOutcome(cfg, spec, loaded, "CSAA", weights, output, result);
            TRBSVUScaleExperiment.verifyOutput(output, loaded, "CSAA");
            var checkpoint = new TRBSVUFinalCheckpoint(output.resolve("checkpoint"), output.resolve("checkpoint"),
                    loaded.fingerprint(), TRBSVUScaleExperiment.VERSION, 0, "SCALE");
            Solution restored = checkpoint.load("CSAA", 0).orElseThrow();
            check(restored.objValue == 100 && restored.y[0] == 1 && restored.solveTimeSec == 12
                    && restored.optimizerTimeSec == 10, "Decision/times/checkpoint round trip");
            Path checkpointFile;
            try (var files = Files.list(output.resolve("checkpoint"))) { checkpointFile = files.findFirst().orElseThrow(); }
            String checkpointText = Files.readString(checkpointFile);
            Files.writeString(checkpointFile, checkpointText + "corrupt\n");
            rejects(() -> TRBSVUScaleExperiment.verifyOutput(output, loaded, "CSAA"));
            Files.writeString(checkpointFile, checkpointText);
            Files.delete(output.resolve("result.csv"));
            rejects(() -> TRBSVUScaleExperiment.verifyOutput(output, loaded, "CSAA"));

            Solution noIncumbent = new Solution(0, null, 7200);
            noIncumbent.solverStatus = "TEST_TIME_LIMIT";
            noIncumbent.relativeGap = 0;
            TRBSVUScaleExperiment.writeOutcome(cfg, spec, loaded, "CSAA", weights, output, noIncumbent);
            check(Double.isNaN(noIncumbent.objValue) && Double.isNaN(noIncumbent.relativeGap), "No fake objective/gap");
            Solution inconsistent = new Solution(100, new double[]{1}, 12);
            inconsistent.bestBound = 101; inconsistent.relativeGap = 0; inconsistent.certifiedOptimal = true;
            TRBSVUScaleExperiment.writeOutcome(cfg, spec, loaded, "CSAA", weights, output, inconsistent);
            check(!inconsistent.certifiedOptimal && Double.isNaN(inconsistent.relativeGap), "Inconsistent bounds not clipped to zero");
            TRBSVUScaleExperiment.verifyOutput(output, loaded, "CSAA");
            Path weightFile = root.resolve("inputs").resolve(spec.id()).resolve("context_weights.tsv");
            Files.writeString(weightFile, "tampered");
            rejects(() -> TRBSVUScaleExperiment.readInputs(root, cfg, spec));
            System.out.println("SCALE_IO_SELF_CHECK_PASS (no native solves, no formal data)");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
