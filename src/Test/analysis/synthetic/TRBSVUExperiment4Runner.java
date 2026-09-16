package Test.analysis.synthetic;

import Basic.Sample;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.ContextualChoice;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.WeightResult;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.OosEvaluation;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.util.List;

/** One-lambda paired comparison of exact RCSAA and contextual modified-chi-square DRO. */
public final class TRBSVUExperiment4Runner {
    public static final double[] LAMBDA = {0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 50, 100};

    public record Certificate(double weightedMean, double weightedSd, double minimum,
                              double denominator, double ratio, boolean holds) { }

    public record Result(double lambda, double effectiveBandwidth,
                         Solution chiSquared, Solution rcsaa,
                         OosEvaluation chiSquaredOos, OosEvaluation rcsaaOos,
                         Certificate certificate, double objectiveGapPercent,
                         boolean sameDecision, double jaccard) { }

    private final Settings settings;
    private final TRBSVUExperiment1Runner contextual;
    private final TRBSVUFinalCheckpoint checkpoint;

    public TRBSVUExperiment4Runner(Settings settings,
                                   TRBSVUExperiment1Runner contextual,
                                   TRBSVUFinalCheckpoint checkpoint) {
        if (settings == null || contextual == null)
            throw new IllegalArgumentException("Experiment 4 settings and contextual adapter are required.");
        this.settings = settings;
        this.contextual = contextual;
        this.checkpoint = checkpoint;
    }

    public Result runOne(TRBSVUSyntheticCase instance, ContextualChoice choice,
                         double lambda) throws Exception {
        if (instance == null || choice == null || !(lambda > 0.0))
            throw new IllegalArgumentException("Invalid Experiment 4 input.");
        WeightResult reference = contextual.contextualWeightResult(instance, instance.history,
                instance.testContext, choice);
        if (reference.weights().isEmpty())
            throw new IllegalStateException("Selected contextual rule has no final support.");
        List<Sample> weights = formalRobustWeights(reference.weights());

        String chiName = key("C-Chi2", lambda);
        Solution chi = restoreOrSolve(chiName, lambda, instance, weights, Method.CHI_SQUARED);
        OosEvaluation chiOos = evaluate(instance, chi, chiName);
        Certificate certificate = chi.y == null ? null
                : certificate(instance, weights, chi.y, lambda);

        String exactName = key("RCSAA", lambda);
        Solution exact = restoreOrSolve(exactName, lambda, instance, weights, Method.RCSAA);
        OosEvaluation exactOos = evaluate(instance, exact, exactName);

        boolean comparableObjectives = chi.certifiedOptimal && exact.certifiedOptimal
                && Double.isFinite(chi.objValue) && Double.isFinite(exact.objValue)
                && Math.abs(chi.objValue) > 1e-12;
        double objectiveGap = comparableObjectives
                ? 100.0 * (exact.objValue - chi.objValue) / chi.objValue : Double.NaN;
        boolean decisionsAvailable = chi.y != null && exact.y != null;
        return new Result(lambda, reference.effectiveBandwidth(), chi, exact, chiOos, exactOos,
                certificate, objectiveGap,
                decisionsAvailable && sameDecision(chi.y, exact.y),
                decisionsAvailable ? jaccard(chi.y, exact.y) : Double.NaN);
    }

    private Solution restoreOrSolve(String name, double lambda, TRBSVUSyntheticCase instance,
                                    List<Sample> weights, Method method) throws Exception {
        Solution solution = checkpoint == null ? null : checkpoint.load(name, lambda).orElse(null);
        if (solution == null) {
            long started = System.nanoTime();
            solution = TRBSVUSolveMethods.solve(instance.params, instance.lanes, weights,
                    instance.testContext, method, lambda, settings);
            solution.solveTimeSec = (System.nanoTime() - started) / 1.0e9;
            if (checkpoint != null) checkpoint.save(name, lambda, solution);
        }
        if (solution.y != null && solution.y.length != instance.params.I)
            throw new IllegalStateException("Experiment 4 decision dimension mismatch for " + name);
        return solution;
    }

    private OosEvaluation evaluate(TRBSVUSyntheticCase instance, Solution solution,
                                   String name) throws Exception {
        if (solution.y == null) return null;
        OosEvaluation evaluation = TRBSVUSolveMethods.evaluateDetailed(
                instance.params, solution.y, instance.oos);
        if (checkpoint != null)
            checkpoint.saveOos(name, evaluation.summary(), evaluation.draws());
        return evaluation;
    }

    private static Certificate certificate(TRBSVUSyntheticCase instance, List<Sample> weights,
                                           double[] selection, double lambda) throws Exception {
        double mean = 0.0, minimum = Double.POSITIVE_INFINITY;
        double[] values = new double[weights.size()];
        for (int s = 0; s < weights.size(); s++) {
            values[s] = TRBSVUSolveMethods.realizedCost(instance.params, selection,
                    weights.get(s).demand());
            mean += weights.get(s).weight * values[s];
            minimum = Math.min(minimum, values[s]);
        }
        double variance = 0.0;
        for (int s = 0; s < weights.size(); s++) {
            double difference = values[s] - mean;
            variance += weights.get(s).weight * difference * difference;
        }
        double sd = Math.sqrt(Math.max(0.0, variance));
        double denominator = mean - minimum;
        double ratio = denominator > 1e-12 ? sd / denominator : Double.POSITIVE_INFINITY;
        return new Certificate(mean, sd, minimum, denominator, ratio,
                ratio + 1e-9 >= lambda);
    }

    /** Exact RCSAA and chi-square use the same pruned/floored reference distribution. */
    private static List<Sample> formalRobustWeights(List<Sample> source) {
        double[] adjusted = new double[source.size()];
        for (int s = 0; s < source.size(); s++) {
            double weight = source.get(s).weight;
            adjusted[s] = weight > 0.0 ? Math.max(weight, 1e-8) : 0.0;
        }
        return TRBSVUScenarioWeights.copyWithWeights(source, adjusted, true);
    }

    private static String key(String method, double lambda) {
        return method + "_lambda_" + Double.toString(lambda);
    }

    private static boolean sameDecision(double[] left, double[] right) {
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++)
            if ((left[i] > 0.5) != (right[i] > 0.5)) return false;
        return true;
    }

    private static double jaccard(double[] left, double[] right) {
        int intersection = 0, union = 0;
        for (int i = 0; i < left.length; i++) {
            boolean a = left[i] > 0.5, b = right[i] > 0.5;
            if (a || b) union++;
            if (a && b) intersection++;
        }
        return union == 0 ? 1.0 : (double) intersection / union;
    }
}
