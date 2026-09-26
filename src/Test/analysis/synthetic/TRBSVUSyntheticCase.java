package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Replication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One shared market, historical path, final context and conditional OOS pool. */
public final class TRBSVUSyntheticCase {
    public record Generated(TRBSVUSyntheticCase instance, Parameters demandParameters) { }
    public record QueryCase(TRBSVUSyntheticCase instance, String queryType,
                            int sourceCandidate, double demandRatio) { }
    public record GeneratedQueries(List<QueryCase> queries, Parameters demandParameters) {
        public GeneratedQueries { queries = List.copyOf(queries); }
    }

    public record Seeds(long demandParameters, long procurement,
                        long contexts, long historicalNoise, long oosNoise) {
    }

    public record ValidationWindow(List<Sample> train, Sample realized) {
    }

    public final ProcurementParams params;
    public final List<String> lanes;
    public final List<Sample> history;
    public final CovariateVector testContext;
    public final List<Sample> oos;
    public final Seeds seeds;

    private TRBSVUSyntheticCase(ProcurementParams params, List<String> lanes,
                                 Replication demand, Seeds seeds) {
        this(params, lanes, demand.history, demand.testContext, demand.oos, seeds);
    }

    TRBSVUSyntheticCase(ProcurementParams params, List<String> lanes,
                        List<Sample> history, CovariateVector testContext,
                        List<Sample> oos, Seeds seeds) {
        this.params = params;
        this.lanes = List.copyOf(lanes);
        this.history = List.copyOf(history);
        this.testContext = testContext;
        this.oos = List.copyOf(oos);
        this.seeds = seeds;
    }

    public static TRBSVUSyntheticCase generate(int carriers, int lanes, int historicalPeriods,
                                                int oosDraws, Distribution distribution,
                                                Volatility volatility, Seeds seeds) {
        return generateDetailed(carriers, lanes, historicalPeriods, oosDraws,
                distribution, volatility, seeds).instance();
    }

    public static Generated generateDetailed(int carriers, int lanes, int historicalPeriods,
                                               int oosDraws, Distribution distribution,
                                               Volatility volatility, Seeds seeds) {
        if (seeds == null) throw new IllegalArgumentException("Seeds are required.");
        Parameters demandParameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                lanes, historicalPeriods, seeds.demandParameters(), 10.0,
                ContextStructure.DENSE_INDEPENDENT_UNIFORM_POSITIVE,
                BaseStructure.UNIFORM_10_100, 0.1, 0.3);
        TRBSVUSyntheticDemandGenerator.MultiQueryReplication generated =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithLinearTrend(
                        demandParameters, distribution, volatility, 1, oosDraws,
                        seeds.contexts(), seeds.historicalNoise(), seeds.oosNoise(),
                        TRBSVUSyntheticDemandGenerator.ContextDistribution.UNIFORM);
        TRBSVUSyntheticDemandGenerator.ConditionalQuery query = generated.queries.get(0);
        Replication demand = new Replication(demandParameters, generated.history,
                query.context, query.oos);
        ProcurementParams market = TRBSVUProcurementGenerator.generate(
                carriers, demandParameters.linearTrendTypicalDemand(), seeds.procurement());
        List<String> laneNames = new ArrayList<>(lanes);
        for (int j = 0; j < lanes; j++) laneNames.add("L" + (j + 1));
        return new Generated(new TRBSVUSyntheticCase(market, laneNames, demand, seeds),
                demandParameters);
    }

    /** Formal baseline: 20 ordinary queries plus 20 high-R queries selected without OOS outcomes. */
    public static GeneratedQueries generateFormalQueries(int carriers, int lanes,
                                                          int historicalPeriods, int oosDraws,
                                                          Distribution distribution,
                                                          Volatility volatility, Seeds seeds) {
        if (seeds == null) throw new IllegalArgumentException("Seeds are required.");
        Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                lanes, historicalPeriods, seeds.demandParameters(), 10.0,
                ContextStructure.DENSE_INDEPENDENT_UNIFORM_POSITIVE,
                BaseStructure.UNIFORM_10_100, 0.1, 0.3);
        int randomQueries = TRBSVUFormalProtocol.RANDOM_QUERIES;
        int highQueries = TRBSVUFormalProtocol.HIGH_R_QUERIES;
        int candidates = Math.max(randomQueries + highQueries, 100);
        TRBSVUSyntheticDemandGenerator.MultiQueryReplication generated =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithLinearTrend(
                        parameters, distribution, volatility, candidates, oosDraws,
                        seeds.contexts(), seeds.historicalNoise(), seeds.oosNoise(),
                        TRBSVUSyntheticDemandGenerator.ContextDistribution.UNIFORM);
        double[] procurementDemand = parameters.linearTrendTypicalDemand();
        ProcurementParams market = TRBSVUProcurementGenerator.generate(
                carriers, procurementDemand, seeds.procurement());
        List<String> laneNames = new ArrayList<>(lanes);
        for (int j = 0; j < lanes; j++) laneNames.add("L" + (j + 1));
        double typicalTotal = sum(procurementDemand);
        List<Integer> highCandidates = new ArrayList<>();
        for (int q = randomQueries; q < generated.queries.size(); q++) highCandidates.add(q);
        highCandidates.sort(Comparator
                .comparingDouble((Integer q) -> sum(parameters.nominalDemand(
                        generated.queries.get(q).context))).reversed()
                .thenComparingInt(Integer::intValue));
        List<QueryCase> result = new ArrayList<>(randomQueries + highQueries);
        for (int q = 0; q < randomQueries; q++)
            result.add(queryCase(market, laneNames, generated, seeds, q,
                    "RANDOM", typicalTotal));
        for (int rank = 0; rank < highQueries; rank++) {
            int q = highCandidates.get(rank);
            result.add(queryCase(market, laneNames, generated, seeds, q,
                    "HIGH_R", typicalTotal));
        }
        return new GeneratedQueries(result, parameters);
    }

    /** Recreates the ordinary-query candidate stream used by the formal generator. */
    public static GeneratedQueries regenerateFormalRandomCandidates(int carriers, int lanes,
                                                                     int historicalPeriods,
                                                                     int oosDraws,
                                                                     Distribution distribution,
                                                                     Volatility volatility,
                                                                     Seeds seeds, int count) {
        if (seeds == null || count < 1 || count > 100)
            throw new IllegalArgumentException("Invalid formal random-query request.");
        Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                lanes, historicalPeriods, seeds.demandParameters(), 10.0,
                ContextStructure.DENSE_INDEPENDENT_UNIFORM_POSITIVE,
                BaseStructure.UNIFORM_10_100, 0.1, 0.3);
        TRBSVUSyntheticDemandGenerator.MultiQueryReplication generated =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithLinearTrend(
                        parameters, distribution, volatility, 100, oosDraws,
                        seeds.contexts(), seeds.historicalNoise(), seeds.oosNoise(),
                        TRBSVUSyntheticDemandGenerator.ContextDistribution.UNIFORM);
        double[] procurementDemand = parameters.linearTrendTypicalDemand();
        ProcurementParams market = TRBSVUProcurementGenerator.generate(
                carriers, procurementDemand, seeds.procurement());
        List<String> laneNames = new ArrayList<>(lanes);
        for (int j = 0; j < lanes; j++) laneNames.add("L" + (j + 1));
        double typicalTotal = sum(procurementDemand);
        List<QueryCase> result = new ArrayList<>(count);
        for (int q = 0; q < count; q++)
            result.add(queryCase(market, laneNames, generated, seeds, q,
                    "RANDOM", typicalTotal));
        return new GeneratedQueries(result, parameters);
    }

    private static QueryCase queryCase(ProcurementParams market, List<String> laneNames,
                                       TRBSVUSyntheticDemandGenerator.MultiQueryReplication generated,
                                       Seeds seeds, int query, String type, double typicalTotal) {
        TRBSVUSyntheticDemandGenerator.ConditionalQuery selected = generated.queries.get(query);
        TRBSVUSyntheticCase instance = new TRBSVUSyntheticCase(market, laneNames,
                generated.history, selected.context, selected.oos, seeds);
        double ratio = sum(generated.parameters.nominalDemand(selected.context)) / typicalTotal;
        return new QueryCase(instance, type, query, ratio);
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    /** Returns one fixed-length, past-only rolling validation window. */
    public ValidationWindow validationWindow(int realizedIndex, int trainingCount) {
        if (trainingCount <= 0 || realizedIndex < trainingCount
                || realizedIndex >= history.size()) {
            throw new IllegalArgumentException("Invalid rolling validation origin.");
        }
        return new ValidationWindow(history.subList(realizedIndex - trainingCount,
                realizedIndex), history.get(realizedIndex));
    }

    public Data data(List<Sample> weightedSamples, CovariateVector query) {
        return new Data(lanes, weightedSamples, query, params);
    }
}
