package Test.analysis.synthetic;

import Basic.Sample;
import Model.Solution;
import Model.SolverTerminationException;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.ContextualChoice;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.WeightResult;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.OosEvaluation;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.util.List;

/** One-lambda paired comparison of exact RCSAA and contextual modified-chi-square DRO. */
public final class TRBSVUExperiment4Runner {
    public static final double[] LAMBDA = {0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10};

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

    /** Frozen-weight comparison does not need a contextual fitter or validation runner. */
    public TRBSVUExperiment4Runner(Settings settings, TRBSVUFinalCheckpoint checkpoint) {
        if (settings == null) throw new IllegalArgumentException("Settings are required");
        if (!settings.compactDual() || !settings.switchedCompactDual() || settings.repairCuts())
            throw new IllegalArgumentException("Decision comparison requires switched compact without repair");
        this.settings = settings;
        this.contextual = null;
        this.checkpoint = checkpoint;
    }

    /** One model on already frozen weights: no fitting, CV, recourse diagnostics, or OOS. */
    public Solution solveDecisionOnly(TRBSVUSyntheticCase instance, List<Sample> frozenWeights,
                                       double lambda, Method method) throws Exception {
        if (instance == null || frozenWeights == null || frozenWeights.isEmpty()
                || !Double.isFinite(lambda) || lambda <= 0
                || (method != Method.RCSAA && method != Method.CHI_SQUARED))
            throw new IllegalArgumentException("Invalid paired comparison input");
        String name = key(method == Method.RCSAA ? "RCSAA" : "C-Chi2", lambda);
        return restoreOrSolve(name, lambda, instance, frozenWeights, method);
    }

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
        if (instance == null || choice == null || !(lambda > 0.0) || contextual == null)
            throw new IllegalArgumentException("Invalid Experiment 4 input.");
        WeightResult reference = contextual.contextualWeightResult(instance, instance.history,
                instance.testContext, choice);
        if (reference.weights().isEmpty())
            throw new IllegalStateException("Selected contextual rule has no final support.");
        List<Sample> weights = formalRobustWeights(reference.weights());

        String chiName = key("C-Chi2", lambda);
        Solution chi = restoreOrSolve(chiName, lambda, instance, weights, Method.CHI_SQUARED);
        OosEvaluation chiOos = evaluate(instance, chi, chiName);
        Certificate certificate = !chi.certifiedOptimal || chi.y == null ? null
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
        System.out.println("EXPERIMENT4_SOLVE method=" + name + " restored=" + (solution != null));
        if (solution == null) {
            long started = System.nanoTime();
            try {
                solution = TRBSVUSolveMethods.solve(instance.params, instance.lanes, weights,
                        instance.testContext, method, lambda, settings);
            } catch (SolverTerminationException ex) {
                solution = new Solution(Double.NaN, null, 0.0);
                solution.solverStatus = ex.solverStatus;
                solution.bestBound = ex.bestBound;
                solution.nodeCount = ex.nodeCount;
                solution.relativeGap = Double.NaN;
            }
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
        double[] values = new double[weights.size()];
        for (int s = 0; s < weights.size(); s++) {
            values[s] = TRBSVUSolveMethods.realizedCost(instance.params, selection,
                    weights.get(s).demand());
        }
        return certificateFromCosts(values, weights.stream().mapToDouble(s -> s.weight).toArray(), lambda);
    }

    /** Matches the shared solve adapter, DROModel canonicalization, and defensive solver floor. */
    static List<Sample> comparisonReference(List<Sample> frozen) {
        List<Sample> reference = TRBSVUScenarioWeights.copyWithWeights(frozen,
                frozen.stream().mapToDouble(s -> s.weight).toArray(), true);
        return formalRobustWeights(formalRobustWeights(reference));
    }

    static Certificate certificateFromCosts(double[] values, double[] weights, double lambda) {
        if (values.length == 0 || values.length != weights.length || !Double.isFinite(lambda) || lambda <= 0)
            throw new IllegalArgumentException("Invalid certificate data");
        double mean = 0, mass = 0, minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
        for (int s = 0; s < values.length; s++) {
            if (!Double.isFinite(weights[s]) || weights[s] < 0 || !Double.isFinite(values[s]))
                throw new IllegalArgumentException("Nonfinite cost/weight");
            if (weights[s] == 0) continue;
            mean += weights[s] * values[s]; mass += weights[s];
            minimum = Math.min(minimum, values[s]); maximum = Math.max(maximum, values[s]);
        }
        if (Math.abs(mass - 1) > 1e-10) throw new IllegalArgumentException("Certificate weights must sum to one");
        mean /= mass;
        double variance = 0.0;
        for (int s = 0; s < weights.length; s++) {
            double difference = values[s] - mean;
            variance += weights[s] / mass * difference * difference;
        }
        double sd = Math.sqrt(Math.max(0.0, variance));
        if (!Double.isFinite(mean) || !Double.isFinite(sd))
            throw new IllegalArgumentException("Certificate statistics overflow");
        double denominator = mean - minimum;
        if (maximum == minimum) return new Certificate(minimum, 0, minimum, 0, Double.POSITIVE_INFINITY, true);
        double ratio = sd / denominator;
        return new Certificate(mean, sd, minimum, denominator, ratio,
                lambda * denominator <= sd);
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
