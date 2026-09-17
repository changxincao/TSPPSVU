package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ConditionalQuery;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.MultiQueryReplication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/** Tests whether tiered-context samples actually imply different carrier decisions. */
public final class TRBSVUCoefficientTierDecisionDiagnostic {
    private static final int CARRIERS = 12;
    private static final int LANES = 20;
    private static final int HISTORY = 60;
    private static final int OOS = 500;
    private static final double BANDWIDTH = 0.5;

    private TRBSVUCoefficientTierDecisionDiagnostic() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException("Usage: <output-directory> [replications] [queries]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        int replications = args.length >= 2 ? Integer.parseInt(args[1]) : 3;
        int queryCount = args.length >= 3 ? Integer.parseInt(args[2]) : 20;
        Files.createDirectories(output);
        Settings settings = new Settings(1, 600, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        List<String> sampleRows = new ArrayList<>();
        List<String> queryRows = new ArrayList<>();
        sampleRows.add("replication\tsample\tdemand_type\ttotal_demand\tselected_count\tselected"
                + "\tcontext_market\tcontext_trend\tcontext_promotion\tcontext_attention");
        queryRows.add("replication\tquery\tnominal_total\ttruth_selected\tsaa_selected\tcsaa_selected"
                + "\ttruth_count\tsaa_count\tcsaa_count\tsaa_regret_pct\tcsaa_regret_pct\tess"
                + "\tsaa_demand_l1_pct\tcsaa_demand_l1_pct\tsaa_share_tv_pct\tcsaa_share_tv_pct"
                + "\tcontext_market\tcontext_trend\tcontext_promotion\tcontext_attention");

        SplittableRandom seeds = new SplittableRandom(20260917L);
        for (int replication = 1; replication <= replications; replication++) {
            TRBSVUSyntheticCase.Seeds paired = new TRBSVUSyntheticCase.Seeds(
                    seeds.nextLong(), seeds.nextLong(), seeds.nextLong(),
                    seeds.nextLong(), seeds.nextLong());
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    LANES, HISTORY, paired.demandParameters(), 1.0,
                    ContextStructure.DENSE_INDEPENDENT_LEVELS, BaseStructure.THREE_LEVEL_WIDE);
            MultiQueryReplication demand = TRBSVUSyntheticDemandGenerator.generateMultiQuery(
                    parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, queryCount, OOS,
                    paired.contexts(), paired.historicalNoise(), paired.oosNoise());
            ProcurementParams market = TRBSVUProcurementGenerator.generate(
                    CARRIERS, parameters.typicalDemand(), paired.procurement());
            List<String> lanes = laneNames();
            Solution saa = solve(market, lanes, TRBSVUScenarioWeights.equal(demand.history),
                    demand.queries.get(0).context, settings);

            for (int t = 0; t < HISTORY; t++) {
                Sample realized = demand.history.get(t);
                Solution realizedOracle = solve(market, lanes,
                        List.of(unitSample(t, realized.theta, realized.demand())),
                        realized.theta, settings);
                writeSample(sampleRows, replication, t, "REALIZED", realized.demand(),
                        realized.theta, realizedOracle);

                double[] nominalDemand = parameters.nominalDemand(realized.theta);
                Solution nominalOracle = solve(market, lanes,
                        List.of(unitSample(t, realized.theta, nominalDemand)),
                        realized.theta, settings);
                writeSample(sampleRows, replication, t, "CONDITIONAL_MEAN", nominalDemand,
                        realized.theta, nominalOracle);
                Files.write(output.resolve("sample_decisions.tsv"), sampleRows,
                        StandardCharsets.UTF_8);
            }

            for (int query = 0; query < queryCount; query++) {
                ConditionalQuery conditional = demand.queries.get(query);
                double[] nominalDemand = parameters.nominalDemand(conditional.context);
                Solution truth = solve(market, lanes,
                        List.of(unitSample(query, conditional.context, nominalDemand)),
                        conditional.context, settings);
                List<Sample> contextual = TRBSVUScenarioWeights.kernel(demand.history,
                        conditional.context, TRBSVUScenarioWeights.Kernel.EXPONENTIAL, BANDWIDTH);
                Solution csaa = solve(market, lanes, contextual, conditional.context, settings);
                double[] saaPrediction = weightedMean(TRBSVUScenarioWeights.equal(demand.history));
                double[] csaaPrediction = weightedMean(contextual);
                double truthCost = TRBSVUSolveMethods.realizedCost(market, truth.y, nominalDemand);
                double saaCost = TRBSVUSolveMethods.realizedCost(market, saa.y, nominalDemand);
                double csaaCost = TRBSVUSolveMethods.realizedCost(market, csaa.y, nominalDemand);
                double[] x = conditional.context.values();
                queryRows.add(String.format(Locale.ROOT,
                        "%d\t%d\t%.10f\t%s\t%s\t%s\t%d\t%d\t%d\t%.10f\t%.10f\t%.10f"
                                + "\t%.10f\t%.10f\t%.10f\t%.10f"
                                + "\t%.10f\t%.10f\t%.10f\t%.10f",
                        replication, query, sum(nominalDemand), selected(truth.y), selected(saa.y),
                        selected(csaa.y), selectedCount(truth.y), selectedCount(saa.y),
                        selectedCount(csaa.y), 100.0 * (saaCost - truthCost) / truthCost,
                        100.0 * (csaaCost - truthCost) / truthCost,
                        TRBSVUExperiment1Runner.ess(contextual),
                        100.0 * normalizedL1(saaPrediction, nominalDemand),
                        100.0 * normalizedL1(csaaPrediction, nominalDemand),
                        100.0 * shareTv(saaPrediction, nominalDemand),
                        100.0 * shareTv(csaaPrediction, nominalDemand),
                        x[0], x[1], x[2], x[3]));
                Files.write(output.resolve("query_decisions.tsv"), queryRows,
                        StandardCharsets.UTF_8);
            }
        }
    }

    private static Solution solve(ProcurementParams market, List<String> lanes,
                                  List<Sample> samples, CovariateVector query,
                                  Settings settings) throws Exception {
        Solution solution = TRBSVUSolveMethods.solve(market, lanes, samples, query,
                Method.NOMINAL, 0.0, settings);
        if (!solution.certifiedOptimal) {
            throw new IllegalStateException("Uncertified decision diagnostic: "
                    + solution.solverStatus);
        }
        return solution;
    }

    private static Sample unitSample(int id, CovariateVector context, double[] demand) {
        LocalDate date = LocalDate.of(2000, 1, 1).plusWeeks(id);
        PeriodData period = new PeriodData(id, date, date.plusDays(6),
                demand.clone(), 0, 0.0, 0.0, 0.0);
        return new Sample(id, period, context.copy(), 1.0);
    }

    private static void writeSample(List<String> rows, int replication, int sample,
                                    String type, double[] demand, CovariateVector context,
                                    Solution solution) {
        double[] x = context.values();
        rows.add(String.format(Locale.ROOT,
                "%d\t%d\t%s\t%.10f\t%d\t%s\t%.10f\t%.10f\t%.10f\t%.10f",
                replication, sample, type, sum(demand), selectedCount(solution.y),
                selected(solution.y), x[0], x[1], x[2], x[3]));
    }

    private static double sum(double[] values) {
        double result = 0.0;
        for (double value : values) result += value;
        return result;
    }

    private static double[] weightedMean(List<Sample> samples) {
        double[] result = new double[LANES];
        for (Sample sample : samples) {
            for (int j = 0; j < LANES; j++) result[j] += sample.weight * sample.demand()[j];
        }
        return result;
    }

    private static double normalizedL1(double[] estimate, double[] truth) {
        double error = 0.0;
        for (int j = 0; j < truth.length; j++) error += Math.abs(estimate[j] - truth[j]);
        return error / sum(truth);
    }

    private static double shareTv(double[] estimate, double[] truth) {
        double estimateTotal = sum(estimate);
        double truthTotal = sum(truth);
        double distance = 0.0;
        for (int j = 0; j < truth.length; j++) {
            distance += Math.abs(estimate[j] / estimateTotal - truth[j] / truthTotal);
        }
        return 0.5 * distance;
    }

    private static int selectedCount(double[] selection) {
        int result = 0;
        for (double value : selection) if (value > 0.5) result++;
        return result;
    }

    private static String selected(double[] selection) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < selection.length; i++) {
            if (selection[i] <= 0.5) continue;
            if (!result.isEmpty()) result.append('|');
            result.append(i);
        }
        return result.toString();
    }

    private static List<String> laneNames() {
        List<String> result = new ArrayList<>(LANES);
        for (int j = 0; j < LANES; j++) result.add("L" + (j + 1));
        return result;
    }
}
