package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Replication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.util.ArrayList;
import java.util.List;

/** One shared market, historical path, final context and conditional OOS pool. */
public final class TRBSVUSyntheticCase {
    public record Generated(TRBSVUSyntheticCase instance, Parameters demandParameters) { }

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
                lanes, historicalPeriods, seeds.demandParameters());
        Replication demand = TRBSVUSyntheticDemandGenerator.generate(demandParameters,
                distribution, volatility, oosDraws, seeds.contexts(),
                seeds.historicalNoise(), seeds.oosNoise());
        ProcurementParams market = TRBSVUProcurementGenerator.generate(
                carriers, demandParameters.typicalDemand(), seeds.procurement());
        List<String> laneNames = new ArrayList<>(lanes);
        for (int j = 0; j < lanes; j++) laneNames.add("L" + (j + 1));
        return new Generated(new TRBSVUSyntheticCase(market, laneNames, demand, seeds),
                demandParameters);
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
