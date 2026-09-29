package Test.analysis.synthetic;

import Basic.Sample;
import Test.analysis.synthetic.TRBSVUScenarioWeights.Kernel;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ConditionalQuery;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextDistribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.MultiQueryReplication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/** Pure-weight diagnostic for the Experiment 1 bandwidth grid; no optimization is run. */
public final class TRBSVUBandwidthWeightDiagnostic {
    private static final int REPLICATIONS = 10;
    private static final int LANES = 50;
    private static final int HISTORY = 75;
    private static final int RANDOM_QUERIES = 20;
    private static final int CONSTRUCTED_QUERIES = 20;
    private static final int QUERY_POOL = 400;
    private static final double R_MIN = 1.10;
    private static final double R_MAX = 1.60;
    private static final double[] BANDWIDTHS = {
            0.1, 0.25, 0.5, 1, 2, 3, 5, 10, 30, 50, 100
    };

    private TRBSVUBandwidthWeightDiagnostic() { }

    public static void main(String[] args) {
        Map<String, Map<Kernel, Map<Double, Summary>>> summaries = new java.util.LinkedHashMap<>();
        summaries.put("VALIDATION", emptySummaries());
        summaries.put("RANDOM", emptySummaries());
        summaries.put("CONSTRUCTED", emptySummaries());

        SplittableRandom seeds = new SplittableRandom(20260917L);
        for (int replication = 0; replication < REPLICATIONS; replication++) {
            long parameterSeed = seeds.nextLong();
            long contextSeed = seeds.nextLong();
            long historyNoiseSeed = seeds.nextLong();
            long oosNoiseSeed = seeds.nextLong();
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    LANES, HISTORY, parameterSeed, 9.0,
                    ContextStructure.DENSE_INDEPENDENT_UNIFORM_POSITIVE,
                    BaseStructure.THREE_LEVEL_10_30_50_70, 0.1, 0.3);
            MultiQueryReplication generated =
                    TRBSVUSyntheticDemandGenerator.generateMultiQueryWithLinearTrend(
                            parameters, Distribution.NORMAL, Volatility.LOW,
                            QUERY_POOL, 1, contextSeed, historyNoiseSeed, oosNoiseSeed,
                            ContextDistribution.UNIFORM);

            List<ConditionalQuery> random = generated.queries.subList(0, RANDOM_QUERIES);
            List<ConditionalQuery> constructed = selectConstructed(parameters, generated.queries);
            accumulateValidation(generated.history, summaries);
            accumulate("RANDOM", generated.history, random, summaries);
            accumulate("CONSTRUCTED", generated.history, constructed, summaries);
        }

        System.out.println("group,kernel,B,count,meanESS,meanMaxWeight,meanTVToEqual");
        for (var group : summaries.entrySet()) {
            for (Kernel kernel : Kernel.values()) {
                for (double bandwidth : BANDWIDTHS) {
                    Summary summary = group.getValue().get(kernel).get(bandwidth);
                    System.out.printf(Locale.ROOT, "%s,%s,%.8g,%d,%.8f,%.8f,%.8f%n",
                            group.getKey(), kernel, bandwidth, summary.count,
                            summary.meanEss(), summary.meanMaxWeight(), summary.meanTv());
                }
            }
        }
    }

    private static void accumulateValidation(List<Sample> history,
                                             Map<String, Map<Kernel, Map<Double, Summary>>> summaries) {
        for (int origin = 50; origin < history.size(); origin++) {
            List<Sample> training = history.subList(origin - 50, origin);
            for (Kernel kernel : Kernel.values()) {
                for (double bandwidth : BANDWIDTHS) {
                    List<Sample> weighted = TRBSVUScenarioWeights.kernel(
                            training, history.get(origin).theta, kernel, bandwidth);
                    addSummary("VALIDATION", kernel, bandwidth, weighted, summaries);
                }
            }
        }
    }

    private static List<ConditionalQuery> selectConstructed(Parameters parameters,
                                                              List<ConditionalQuery> pool) {
        double typicalTotal = sum(parameters.typicalDemand());
        List<ConditionalQuery> eligible = new ArrayList<>();
        for (ConditionalQuery query : pool) {
            double ratio = sum(parameters.nominalDemand(query.context)) / typicalTotal;
            if (ratio >= R_MIN && ratio <= R_MAX) eligible.add(query);
        }
        eligible.sort(Comparator.comparingDouble(query ->
                sum(parameters.nominalDemand(query.context)) / typicalTotal));
        if (eligible.size() < CONSTRUCTED_QUERIES) {
            throw new IllegalStateException("Only " + eligible.size()
                    + " constructed queries fall in the R window.");
        }
        List<ConditionalQuery> selected = new ArrayList<>(CONSTRUCTED_QUERIES);
        for (int q = 0; q < CONSTRUCTED_QUERIES; q++) {
            int index = (int) Math.round(q * (eligible.size() - 1.0)
                    / (CONSTRUCTED_QUERIES - 1.0));
            selected.add(eligible.get(index));
        }
        return selected;
    }

    private static void accumulate(String group, List<Sample> history,
                                   List<ConditionalQuery> queries,
                                   Map<String, Map<Kernel, Map<Double, Summary>>> summaries) {
        for (ConditionalQuery query : queries) {
            for (Kernel kernel : Kernel.values()) {
                for (double bandwidth : BANDWIDTHS) {
                    List<Sample> weighted = TRBSVUScenarioWeights.kernel(
                            history, query.context, kernel, bandwidth);
                    addSummary(group, kernel, bandwidth, weighted, summaries);
                }
            }
        }
    }

    private static void addSummary(String group, Kernel kernel, double bandwidth,
                                   List<Sample> weighted,
                                   Map<String, Map<Kernel, Map<Double, Summary>>> summaries) {
        if (weighted.isEmpty()) return;
        double sumSquares = 0.0;
        double maximum = 0.0;
        double tv = 0.0;
        double equal = 1.0 / weighted.size();
        for (Sample sample : weighted) {
            sumSquares += sample.weight * sample.weight;
            maximum = Math.max(maximum, sample.weight);
            tv += Math.abs(sample.weight - equal);
        }
        summaries.get(group).get(kernel).get(bandwidth)
                .add(1.0 / sumSquares, maximum, 0.5 * tv);
    }

    private static Map<Kernel, Map<Double, Summary>> emptySummaries() {
        Map<Kernel, Map<Double, Summary>> result = new EnumMap<>(Kernel.class);
        for (Kernel kernel : Kernel.values()) {
            Map<Double, Summary> byBandwidth = new java.util.LinkedHashMap<>();
            for (double bandwidth : BANDWIDTHS) byBandwidth.put(bandwidth, new Summary());
            result.put(kernel, byBandwidth);
        }
        return result;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static final class Summary {
        private int count;
        private double ess;
        private double maxWeight;
        private double tv;

        void add(double valueEss, double valueMaxWeight, double valueTv) {
            count++;
            ess += valueEss;
            maxWeight += valueMaxWeight;
            tv += valueTv;
        }

        double meanEss() { return count == 0 ? Double.NaN : ess / count; }
        double meanMaxWeight() { return count == 0 ? Double.NaN : maxWeight / count; }
        double meanTv() { return count == 0 ? Double.NaN : tv / count; }
    }
}
