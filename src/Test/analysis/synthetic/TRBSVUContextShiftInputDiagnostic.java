package Test.analysis.synthetic;

import Basic.Sample;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextDistribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.MultiQueryReplication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/** Input-only audit for the paired low-history/high-query diagnostic. */
public final class TRBSVUContextShiftInputDiagnostic {
    private TRBSVUContextShiftInputDiagnostic() { }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <context-shift>");
        double shift = Double.parseDouble(args[0]);
        SplittableRandom seeds = new SplittableRandom(20260917L);
        long parameterSeed = seeds.nextLong();
        seeds.nextLong(); // Procurement seed, not used for demand-only statistics.
        long contextSeed = seeds.nextLong();
        long historyNoiseSeed = seeds.nextLong();
        long oosNoiseSeed = seeds.nextLong();
        Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                50, 50, parameterSeed, 1.95,
                ContextStructure.SQUARED_GAUSSIAN_POSITIVE_CENTERED,
                BaseStructure.THREE_LEVEL_WIDE, 0.2, 0.6);
        MultiQueryReplication demand =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithRegionalFactors(
                        parameters, Distribution.LOGNORMAL, Volatility.MEDIUM,
                        6, 501, contextSeed, historyNoiseSeed, oosNoiseSeed,
                        ContextDistribution.UNIFORM, 5, 0.5);
        demand = TRBSVUSyntheticDemandGenerator.shiftLognormalContexts(demand, shift);
        double historyMean = meanTotal(demand.history);
        double historyMax = maxTotal(demand.history);
        double nominalMean = 0.0;
        for (Sample sample : demand.history)
            nominalMean += total(parameters.nominalDemand(sample.theta)) / demand.history.size();
        System.out.printf(Locale.ROOT,
                "shift=%.2f history_nominal_mean=%.4f history_realized_mean=%.4f"
                        + " history_realized_max=%.4f%n",
                shift, nominalMean, historyMean, historyMax);
        for (int q = 0; q < demand.queries.size(); q++) {
            var query = demand.queries.get(q);
            List<Sample> evaluation = query.oos.subList(1, query.oos.size());
            List<Sample> weighted = TRBSVUScenarioWeights.kernel(demand.history,
                    query.context, TRBSVUScenarioWeights.Kernel.EXPONENTIAL, 0.5);
            double weightedTrainingTotal = 0.0;
            for (Sample sample : weighted)
                weightedTrainingTotal += sample.weight * total(sample.demand());
            long aboveHistoryMax = evaluation.stream()
                    .filter(sample -> total(sample.demand()) > historyMax).count();
            System.out.printf(Locale.ROOT,
                    "query=%d nominal=%.4f weighted_training=%.4f oos_mean=%.4f"
                            + " oos_max=%.4f fraction_above_history_max=%.3f%n",
                    q, total(parameters.nominalDemand(query.context)),
                    weightedTrainingTotal, meanTotal(evaluation), maxTotal(evaluation),
                    aboveHistoryMax / (double) evaluation.size());
        }
    }

    private static double total(double[] values) {
        double result = 0.0;
        for (double value : values) result += value;
        return result;
    }

    private static double meanTotal(List<Sample> samples) {
        double result = 0.0;
        for (Sample sample : samples) result += total(sample.demand()) / samples.size();
        return result;
    }

    private static double maxTotal(List<Sample> samples) {
        double result = Double.NEGATIVE_INFINITY;
        for (Sample sample : samples) result = Math.max(result, total(sample.demand()));
        return result;
    }
}
