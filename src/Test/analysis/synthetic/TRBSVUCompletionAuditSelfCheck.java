package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Model.Solution;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic files only: exercises read-only completion entry points without any solver calls. */
public final class TRBSVUCompletionAuditSelfCheck {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("trb-completion-audit-");
        try {
            Path input = temp.resolve("input");
            Files.createDirectories(input.resolve("queries"));
            Path instance = input.resolve("queries/fixture.tsv");
            TRBSVUSyntheticCaseIO.saveText(fixture(), instance);
            Files.createDirectories(input.resolve("instance"));
            Files.copy(instance, input.resolve("instance/instance.tsv"));
            StringBuilder manifest = new StringBuilder("query_index\tquery_type\tsource_candidate\tdemand_ratio\tinstance_file\n");
            for (int q = 0; q < 40; q++) manifest.append(q).append("\tRANDOM\t").append(q).append("\t1\tfixture.tsv\n");
            Files.writeString(input.resolve("queries/queries.tsv"), manifest);
            Path choice = temp.resolve("choice.csv");
            Files.writeString(choice, "family,validation_selected_B,validation_cost,validation_sd,bandwidth_order\nTRIANGULAR,1,10,1,1;2\n");
            String pool = TRBSVUExperiment1IdeMain.queryPoolFingerprint(TRBSVUExperiment1IdeMain.loadQueries(input));
            var sourceMethod = TRBSVUExperiment1IdeMain.class.getDeclaredMethod("sourceFingerprint", Path.class);
            sourceMethod.setAccessible(true);
            String source = (String) sourceMethod.invoke(null, Path.of("src"));
            var robustSourceMethod = TRBSVUExperiment2IdeMain.class.getDeclaredMethod("sourceFingerprint", Path.class);
            robustSourceMethod.setAccessible(true);
            String robustSource = (String) robustSourceMethod.invoke(null, Path.of("src"));
            String baseProtocol = hash("TRBSVU_EXP1_METHOD_V3|method=D|origins=25|validationTrainingPeriods=50"
                    + "|threads=4|limit=14400|queryPool=" + pool
                    + "|retention=" + Arrays.toString(TRBSVUExperiment1Runner.RETENTION)
                    + "|bandwidth=" + Arrays.toString(TRBSVUExperiment1Runner.BANDWIDTH)
                    + "|rfMinLeaf=" + Arrays.toString(TRBSVUExperiment1Runner.RF_MIN_LEAF)
                    + "|source=" + source + "|rfScript=" + TRBSVUScaleExperiment.sha256(Path.of("analysis/trb_svu/rf_leaf_weights.py"))
                    + "|pythonEnvironment=NOT_USED");
            String robustProtocol = hash(TRBSVUFormalProtocol.EXPERIMENT12_VERSION
                    + "|experiment=2|phase=primary|methods=[C-Chi2]|queryPool=" + pool
                    + "|selected=" + TRBSVUExperiment4Main.loadChoice(choice)
                    + "|lambda=" + Arrays.toString(TRBSVUExperiment2Runner.LAMBDA)
                    + "|w1=" + Arrays.toString(TRBSVUExperiment2Runner.W1_RADIUS)
                    + "|momentKappa=" + Arrays.toString(TRBSVUExperiment2Runner.MOMENT_KAPPA)
                    + "|momentValidationLimit=" + TRBSVUExperiment2Runner.MOMENT_VALIDATION_LIMIT_SECONDS
                    + "|momentQueryLimit=" + TRBSVUExperiment2Runner.MOMENT_QUERY_LIMIT_SECONDS
                    + "|rcsaaCompactFormulation=SWITCHED_COMPACT|threads=4|limit=0|source=" + robustSource
                    + "|pcmScript=NOT_USED|mosekAdapter=NOT_USED|momentPythonEnvironment=NOT_USED");
            for (boolean robust : List.of(false, true)) {
                Path output = temp.resolve(robust ? "robust" : "base");
                String[] workerArgs = robust ? new String[]{input.toString(), choice.toString(), output.toString(), "0", "4", "0", "PRIMARY", "C-Chi2"}
                        : new String[]{input.toString(), output.toString(), "0", "D", "25", "4", "14400"};
                Class<?> entry = robust ? TRBSVUExperiment2IdeMain.class : TRBSVUExperiment1IdeMain.class;
                rejects(() -> auditWorker(entry, workerArgs));
                check(!Files.exists(output), "Read-only check created output/locks/checkpoints");
                createQueries(output, robust);
                String protocol = robust ? robustProtocol : baseProtocol;
                TRBSVUCompletionMarker.writeAtomically(output.resolve("complete.txt"), "protocol=" + protocol + "\nallRequestedMethodsCompleted=true\n");
                Map<String, String> before = snapshot(output);
                auditWorker(entry, workerArgs);
                check(before.equals(snapshot(output)), "Successful audit changed saved output");
                Path weights = output.resolve("queries/query_039/solve/" + (robust ? "experiment2_final_weights.csv" : "final_weights.csv"));
                Files.delete(weights);
                before = snapshot(output);
                rejects(() -> auditWorker(entry, workerArgs));
                check(before.equals(snapshot(output)), "Incomplete audit changed saved output");
                Files.writeString(output.resolve("complete.txt"), "protocol=old-protocol\n");
                before = snapshot(output);
                rejects(() -> auditWorker(entry, workerArgs));
                check(before.equals(snapshot(output)), "Protocol rejection changed saved output");
            }
            checkComparison(temp.resolve("comparison"));
            System.out.println("TRBSVUCompletionAuditSelfCheck PASS: base/robust/comparison, read-only, missing artifacts, protocol and cache integrity");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    private static void checkComparison(Path output) throws Exception {
        Files.createDirectories(output.resolve("evaluations/1"));
        Files.writeString(output.resolve("input_fingerprint.txt"), "test\n");
        Files.writeString(output.resolve("complete.txt"), "fingerprint=test\nlambdaCount=1\n");
        Solution s = new Solution(10, new double[]{1}, 1);
        Files.writeString(output.resolve("comparison.csv"), TRBSVULambdaDecisionComparison.header()
                + TRBSVULambdaDecisionComparison.row("test", "q0", .5, s, s, null, null, null));
        Path cache = output.resolve("evaluations/1");
        Files.writeString(cache.resolve("decision_summary.csv"), "method,mean\ndecision,10\n");
        Path draws = cache.resolve("decision_draws.csv");
        Files.writeString(draws, draws("decision", 4));
        Files.writeString(cache.resolve("oos_complete.properties"), "fingerprint=test\nsummarySha="
                + TRBSVUScaleExperiment.sha256(cache.resolve("decision_summary.csv"))
                + "\ndrawsSha=" + TRBSVUScaleExperiment.sha256(draws) + "\n");
        Path dir = output.resolve("lambda_0.5");
        Files.createDirectories(dir);
        for (String method : TRBSVULambdaDecisionComparison.METHODS) {
            Files.writeString(dir.resolve(method + "_solve.csv"), "method,selected_parameter,decision_vector\n" + method + ",0.5,1\n");
            Files.writeString(dir.resolve(method + "_oos_summary.csv"), "method,mean\n" + method + ",10\n");
            Files.writeString(dir.resolve(method + "_oos_details.txt"), "relativeDirectory=../evaluations/1\ndraws=decision_draws.csv\n");
        }
        var before = snapshot(output);
        TRBSVULambdaDecisionComparison.auditComplete(output, "test", new double[]{.5}, 4);
        check(before.equals(snapshot(output)), "Comparison audit changed output");
        rejects(() -> TRBSVULambdaDecisionComparison.auditComplete(output, "test", new double[]{.5}, 1000));
        Files.writeString(draws, draws("decision", 4).replace(",10,20", ",10,21"));
        rejects(() -> TRBSVULambdaDecisionComparison.auditComplete(output, "test", new double[]{.5}, 4));
        // No-incumbent outcomes are recorded, not zero-cost evaluations or failures.
        for (String method : TRBSVULambdaDecisionComparison.METHODS)
            Files.writeString(dir.resolve(method + "_solve.csv"), "method,selected_parameter,decision_vector\n" + method + ",0.5,NA\n");
        TRBSVULambdaDecisionComparison.auditComplete(output, "test", new double[]{.5}, 4);
        Files.writeString(dir.resolve("RCSAA_failure.txt"), "test failure\n");
        rejects(() -> TRBSVULambdaDecisionComparison.auditComplete(output, "test", new double[]{.5}, 4));
    }

    private static void createQueries(Path output, boolean robust) throws Exception {
        String method = robust ? "C-Chi2" : "D", prefix = robust ? "experiment2_" : "";
        for (int q = 0; q < 40; q++) {
            Path dir = output.resolve(String.format("queries/query_%03d", q));
            for (String sub : List.of("validation", "solve", "oos")) Files.createDirectories(dir.resolve(sub));
            Files.writeString(dir.resolve("query_metadata.txt"), "completedMethods=" + method + "\nmissingMethods=\n");
            for (String file : List.of("validation/summary.csv", "validation/details.csv", "solve/" + prefix + "final_solves.csv", "solve/" + prefix + "final_weights.csv", "oos/" + prefix + "summary.csv"))
                Files.writeString(dir.resolve(file), "test_column\ntest_value\n");
            if (!robust) Files.move(dir.resolve("solve/final_solves.csv"), dir.resolve("solve/final_solve.csv"));
            Files.writeString(dir.resolve("oos/" + prefix + "draws.csv"), draws(method, 1000));
        }
    }

    private static String draws(String method, int count) {
        StringBuilder text = new StringBuilder("method,draw_index,total_demand,total_cost\n");
        for (int i = 0; i < count; i++) text.append(method).append(',').append(i).append(",10,20\n");
        return text.toString();
    }

    private static TRBSVUSyntheticCase fixture() {
        List<String> carriers = new ArrayList<>(), lanes = new ArrayList<>();
        double[] spot = new double[50], mqc = new double[15], penalty = new double[15];
        Arrays.fill(spot, 5);
        double[][] laneCapacity = new double[15][50], rate = new double[15][50];
        boolean[][] eligible = new boolean[15][50];
        for (int i = 0; i < 15; i++) {
            carriers.add("C" + i); mqc[i] = 10; penalty[i] = 1;
            Arrays.fill(laneCapacity[i], 100); Arrays.fill(rate[i], 1); Arrays.fill(eligible[i], true);
        }
        for (int j = 0; j < 50; j++) lanes.add("L" + j);
        List<Sample> history = new ArrayList<>(), oos = new ArrayList<>();
        for (int t = 0; t < 1075; t++) {
            double[] demand = new double[50]; Arrays.fill(demand, 10);
            LocalDate day = LocalDate.of(2020, 1, 1).plusDays(t);
            Sample sample = new Sample(t, new PeriodData(t, day, day, demand, 0, 0, 0, 0),
                    new CovariateVector(new double[]{.5, .5, .5, .5}), 1);
            (t < 75 ? history : oos).add(sample);
        }
        return new TRBSVUSyntheticCase(new ProcurementParams(carriers, 50, spot, mqc, penalty,
                laneCapacity, rate, eligible, 5, 12), lanes, history,
                new CovariateVector(new double[]{.5, 1, .5, .5}), oos,
                new TRBSVUSyntheticCase.Seeds(1, 2, 3, 4, 5));
    }

    private static void auditWorker(Class<?> entry, String[] args) throws Exception {
        var method = entry.getDeclaredMethod("runWorker", String[].class, boolean.class);
        method.setAccessible(true);
        try { method.invoke(null, args, true); }
        catch (InvocationTargetException ex) { throw (Exception) ex.getCause(); }
    }

    private static Map<String, String> snapshot(Path output) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        try (var paths = Files.walk(output)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().toList())
                values.put(output.relativize(p).toString(), TRBSVUScaleExperiment.sha256(p) + ":" + Files.getLastModifiedTime(p));
        }
        return values;
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void rejects(TRBSVULambdaComparisonSelfCheck.Checked action) throws Exception {
        TRBSVULambdaComparisonSelfCheck.rejects(action);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
