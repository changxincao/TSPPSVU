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
import java.util.Properties;

/** Unit checks; --native additionally solves a one-carrier hand-written fixture, never formal data. */
public final class TRBSVULambdaComparisonSelfCheck {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void rejects(Checked action) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (Exception expected) { rejected = true; }
        check(rejected, "Expected rejection");
    }
    static Sample sample(int id, double demand) {
        LocalDate day = LocalDate.of(2020, 1, 1).plusDays(id);
        return new Sample(id, new PeriodData(id, day, day, new double[]{demand}, 0, 0, 0, 0),
                new CovariateVector(new double[]{0.5, 0.5, 0.5, 0.5}), 0.5);
    }
    static Properties config() {
        Properties p = new Properties();
        p.setProperty("protocol", TRBSVULambdaDecisionComparison.VERSION);
        p.setProperty("state", "FROZEN");
        p.setProperty("caseId", "test"); p.setProperty("queryId", "q0"); p.setProperty("replication", "0");
        p.setProperty("contextFamily", "TRIANGULAR"); p.setProperty("contextParameter", "1");
        p.setProperty("validationContextParameter", "1"); p.setProperty("weightSource", "HAND_WRITTEN_TEST");
        p.setProperty("runtimeFingerprint", "TEST_ONLY"); p.setProperty("lambdaGrid", "0.5,2");
        p.setProperty("solverThreads", "4"); p.setProperty("timeLimit", "UNLIMITED");
        return p;
    }
    public static void main(String[] args) throws Exception {
        var c = TRBSVUExperiment4Runner.certificateFromCosts(new double[]{0, 2}, new double[]{0.5, 0.5}, 1);
        check(c.holds() && c.ratio() == 1 && c.weightedMean() == 1 && c.weightedSd() == 1, "Boundary equality");
        check(!TRBSVUExperiment4Runner.certificateFromCosts(new double[]{0, 2}, new double[]{0.5, 0.5}, 2).holds(), "Above threshold");
        check(TRBSVUExperiment4Runner.certificateFromCosts(new double[]{5, 5, -9}, new double[]{0.2, 0.8, 0}, 100).holds(), "Constant costs and zero weights");
        rejects(() -> TRBSVUExperiment4Runner.certificateFromCosts(new double[]{0, 2}, new double[]{0, 0}, 1));
        rejects(() -> TRBSVULambdaDecisionComparison.parseLambdas("0.1,NaN"));
        rejects(() -> TRBSVULambdaDecisionComparison.parseLambdas("0.1,0.10"));
        Properties p = config();
        check(TRBSVULambdaDecisionComparison.validateConfiguration(p).length == 2, "Configuration");
        p.setProperty("timeLimit", "7200"); rejects(() -> TRBSVULambdaDecisionComparison.validateConfiguration(p));
        p.setProperty("timeLimit", "UNLIMITED"); p.setProperty("state", "PENDING_MAIN_EXPERIMENT");
        rejects(() -> TRBSVULambdaDecisionComparison.validateConfiguration(p));
        var settings = TRBSVUSolveMethods.Settings.unlimitedRobust(4);
        check(settings.timeLimitSeconds() == 0 && settings.switchedCompactDual() && !settings.repairCuts(), "Unlimited switched compact");
        Solution exact = new Solution(10, new double[]{1}, 1); exact.certifiedOptimal = true;
        Solution chi = new Solution(10, new double[]{1}, 1); chi.certifiedOptimal = true;
        int columns = TRBSVULambdaDecisionComparison.header().trim().split(",", -1).length;
        String paired = TRBSVULambdaDecisionComparison.row("r0", "q0", 1, chi, exact, null, null, c);
        check(paired.trim().split(",", -1).length == columns && paired.contains("BOTH_CERTIFIED,true,true,HOLDS"), "Paired CSV");
        String failed = TRBSVULambdaDecisionComparison.row("r0", "q0", 1, null, exact, null, null, null);
        check(failed.trim().split(",", -1).length == columns && failed.contains("MODEL_FAILURE,false,NA,UNRESOLVED"), "Missing is not disagreement");
        chi.certifiedOptimal = false;
        rejects(() -> TRBSVULambdaDecisionComparison.row("r0", "q0", 1, chi, exact, null, null, c));
        Path temp = Files.createTempDirectory("trb-lambda-test-");
        try {
            List<Sample> history = List.of(sample(0, 10), sample(1, 12));
            Path weights = temp.resolve("weights.tsv");
            Files.writeString(weights, "row_index\tsample_id\tweight\n0\t0\t0.5\n1\t1\t0.5\n");
            check(TRBSVULambdaDecisionComparison.readWeights(weights, history).size() == 2, "Weight input");
            var input = new TRBSVUSyntheticCase(new ProcurementParams(List.of("C0"), 1, new double[]{5},
                    new double[]{2}, new double[]{1}, new double[][]{{20}}, new double[][]{{1}}, new boolean[][]{{true}}, 1, 1),
                    List.of("L0"), history, new CovariateVector(new double[]{0.5, 1, 0.5, 0.5}),
                    List.of(sample(2, 8), sample(3, 10), sample(4, 12), sample(5, 14)),
                    new TRBSVUSyntheticCase.Seeds(1, 2, 3, 4, 5));
            Path instance = temp.resolve("instance.tsv"), configFile = temp.resolve("test.properties");
            TRBSVUSyntheticCaseIO.saveText(input, instance);
            StringBuilder text = new StringBuilder();
            for (String key : config().stringPropertyNames()) text.append(key).append('=').append(config().getProperty(key)).append('\n');
            Files.writeString(configFile, text.toString());
            if (args.length == 1 && args[0].equals("--native")) {
                var limited = new TRBSVUSolveMethods.Settings(1, 60, 1e-4,
                        Model.RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true, true);
                for (var method : List.of(TRBSVUSolveMethods.Method.CHI_SQUARED, TRBSVUSolveMethods.Method.RCSAA)) {
                    var finiteResult = TRBSVUSolveMethods.solve(input.params, input.lanes, history,
                            input.testContext, method, 0.5, limited);
                    check(Math.abs(finiteResult.objValue - 11.5) < 0.001, "Existing positive-limit route");
                }
                Path output = temp.resolve("run");
                String[] runArgs = {configFile.toString(), instance.toString(), weights.toString(), output.toString()};
                TRBSVULambdaDecisionComparison.main(runArgs);
                check(Files.exists(output.resolve("complete.txt")), "Full native sweep");
                var cp = new TRBSVUFinalCheckpoint(output.resolve("checkpoints"), output.resolve("checkpoints"),
                        TRBSVUScaleExperiment.sha256(instance), Files.readString(output.resolve("input_fingerprint.txt")).trim(), 0, "LAMBDA_COMPARISON");
                check(Math.abs(cp.load("RCSAA_lambda_2.0", 2).orElseThrow().objValue - 13) < 0.001, "Exact mean+2sd=13");
                check(Math.abs(cp.load("C-Chi2_lambda_2.0", 2).orElseThrow().objValue - 12) < 0.001, "DRO max=12");
                List<String> rows = Files.readAllLines(output.resolve("comparison.csv"));
                check(rows.size() == 3 && rows.get(1).contains(",HOLDS,") && rows.get(2).contains(",FAILS,"), "Native theoretical condition");
                Path drawFile = output.resolve("evaluations/1/decision_draws.csv");
                var savedTime = Files.getLastModifiedTime(drawFile);
                Path solveFile = output.resolve("checkpoints/RCSAA_lambda_2_0.checkpoint");
                var solveTime = Files.getLastModifiedTime(solveFile);
                TRBSVULambdaDecisionComparison.main(runArgs);
                check(Files.getLastModifiedTime(drawFile).equals(savedTime) && Files.getLastModifiedTime(solveFile).equals(solveTime), "Resume reuses solve/OOS");
                check(Files.readAllLines(drawFile).size() == 5, "Four paired OOS draws");
                var cache = new TRBSVULambdaEvaluationCache(output.resolve("evaluations"), input,
                        TRBSVUExperiment4Runner.comparisonReference(history), Files.readString(output.resolve("input_fingerprint.txt")).trim());
                check(Math.abs(cache.oos(exact).summary().mean() - 11) < 1e-5, "OOS mean");
                Files.writeString(drawFile, "corrupt"); rejects(() -> cache.oos(exact));
            }
            Files.writeString(weights, "row_index\tsample_id\tweight\n0\t999\t0.5\n1\t1\t0.5\n");
            rejects(() -> TRBSVULambdaDecisionComparison.readWeights(weights, history));
            System.out.println("LAMBDA_COMPARISON_SELF_CHECK_PASS native=" + (args.length == 1));
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
