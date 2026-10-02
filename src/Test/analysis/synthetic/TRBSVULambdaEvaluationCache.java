package Test.analysis.synthetic;

import Basic.Sample;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Per-query fixed-decision evaluations, shared across lambdas and the two methods. */
final class TRBSVULambdaEvaluationCache {
    record Evaluation(Oos summary, Path directory) { }
    private final Path root;
    private final TRBSVUSyntheticCase instance;
    private final List<Sample> reference;
    private final String fingerprint;

    TRBSVULambdaEvaluationCache(Path root, TRBSVUSyntheticCase instance, List<Sample> reference, String fingerprint) {
        this.root = root; this.instance = instance; this.reference = reference; this.fingerprint = fingerprint;
    }

    static String decisionKey(double[] y) {
        StringBuilder key = new StringBuilder();
        for (double value : y) key.append(value > 0.5 ? '1' : '0');
        return key.toString();
    }

    Evaluation oos(Solution solution) throws Exception {
        Path dir = root.resolve(decisionKey(solution.y));
        Files.createDirectories(dir);
        Path summary = dir.resolve("decision_summary.csv"), draws = dir.resolve("decision_draws.csv");
        Path receipt = dir.resolve("oos_complete.properties");
        if (Files.exists(receipt)) verify(receipt, summary, draws);
        else {
            var evaluation = TRBSVUSolveMethods.evaluateDetailed(instance.params, solution.y, instance.oos);
            var checkpoint = new TRBSVUFinalCheckpoint(dir, dir, fingerprint, fingerprint, 0, "FIXED_DECISION");
            // Cached outcomes have no method-specific solver status; each lambda's solve CSV holds that status.
            checkpoint.saveOos("decision", evaluation.summary(), evaluation.draws());
            TRBSVUScaleExperiment.atomicText(receipt, "fingerprint=" + fingerprint
                    + "\nsummarySha=" + TRBSVUScaleExperiment.sha256(summary)
                    + "\ndrawsSha=" + TRBSVUScaleExperiment.sha256(draws) + "\n");
        }
        List<String> lines = Files.readAllLines(summary);
        if (lines.size() != 2) throw new IllegalStateException("Incomplete cached OOS summary");
        // Fixed method/status strings contain no commas. Columns 7..21 are the common writer's 15 OOS metrics.
        String[] fields = lines.get(1).split(",", -1);
        if (fields.length != 22 || !lines.get(0).startsWith("replication,experiment,method,solve_status,"))
            throw new IllegalStateException("Unknown cached OOS summary format");
        double[] v = new double[15];
        for (int i = 0; i < v.length; i++) {
            v[i] = Double.parseDouble(fields[i + 7]);
            if (!Double.isFinite(v[i])) throw new IllegalStateException("Nonfinite cached OOS metric");
        }
        return new Evaluation(new Oos(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7],
                v[8], v[9], v[10], v[11], v[12], v[13], v[14]), dir);
    }

    TRBSVUExperiment4Runner.Certificate certificate(Solution chi, double lambda) throws Exception {
        Path dir = root.resolve(decisionKey(chi.y));
        Files.createDirectories(dir);
        Path file = dir.resolve("training_costs.tsv"), receipt = dir.resolve("training_complete.properties");
        if (Files.exists(receipt)) {
            Properties p = TRBSVUScaleExperiment.readProperties(receipt);
            if (!fingerprint.equals(p.getProperty("fingerprint"))
                    || !TRBSVUScaleExperiment.sha256(file).equals(p.getProperty("sha")))
                throw new IllegalStateException("Training-cost cache mismatch");
        } else {
            StringBuilder text = new StringBuilder("sample_id\tweight\trecourse_cost\n");
            for (Sample sample : reference) text.append(sample.id).append('\t').append(sample.weight).append('\t')
                    .append(TRBSVUSolveMethods.realizedCost(instance.params, chi.y, sample.demand())).append('\n');
            TRBSVUScaleExperiment.atomicText(file, text.toString());
            TRBSVUScaleExperiment.atomicText(receipt, "fingerprint=" + fingerprint + "\nsha="
                    + TRBSVUScaleExperiment.sha256(file) + "\n");
        }
        List<String> lines = Files.readAllLines(file);
        if (lines.size() != reference.size() + 1) throw new IllegalStateException("Training-cost row count mismatch");
        double[] costs = new double[reference.size()], weights = new double[reference.size()];
        for (int s = 0; s < reference.size(); s++) {
            String[] f = lines.get(s + 1).split("\t", -1);
            if (f.length != 3 || Integer.parseInt(f[0]) != reference.get(s).id
                    || Double.parseDouble(f[1]) != reference.get(s).weight)
                throw new IllegalStateException("Training-cost sample/weight mismatch");
            weights[s] = reference.get(s).weight; costs[s] = Double.parseDouble(f[2]);
        }
        return TRBSVUExperiment4Runner.certificateFromCosts(costs, weights, lambda);
    }

    private void verify(Path receipt, Path summary, Path draws) throws Exception {
        Properties p = TRBSVUScaleExperiment.readProperties(receipt);
        if (!fingerprint.equals(p.getProperty("fingerprint"))
                || !TRBSVUScaleExperiment.sha256(summary).equals(p.getProperty("summarySha"))
                || !TRBSVUScaleExperiment.sha256(draws).equals(p.getProperty("drawsSha")))
            throw new IllegalStateException("OOS cache mismatch");
        if (Files.readAllLines(draws).size() != instance.oos.size() + 1)
            throw new IllegalStateException("OOS draws incomplete");
    }
}
