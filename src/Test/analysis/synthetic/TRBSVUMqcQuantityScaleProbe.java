package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.SecondStageEvaluator;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.MultiQueryReplication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/** Free-cardinality diagnostic for larger MQC quantity multipliers. */
public final class TRBSVUMqcQuantityScaleProbe {
    private static final int CARRIERS = 12;
    private static final int LANES = 20;
    private static final int HISTORY = 60;
    private static final double[] SCALES = {1.50, 1.60, 1.70, 1.75};

    private TRBSVUMqcQuantityScaleProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <output-directory>");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        TRBSVUSolveMethods.Settings settings = new TRBSVUSolveMethods.Settings(
                1, 600, 1e-8, RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        List<String> lanes = laneNames();
        List<String> rows = new ArrayList<>();
        rows.add("replication\tmqc_scale\tsample\tdemand_type\ttotal_demand"
                + "\tselected_count\tselected\tobjective\ttransport_cost\tspot_cost"
                + "\tmqc_penalty\tspot_quantity\tmqc_shortfall\tstatus\tcertified\tgap");
        List<String> feasibility = new ArrayList<>();
        feasibility.add("replication\tmaximum_feasible_scale");

        SplittableRandom seeds = new SplittableRandom(20260917L);
        for (int replication = 1; replication <= 3; replication++) {
            TRBSVUSyntheticCase.Seeds paired = new TRBSVUSyntheticCase.Seeds(
                    seeds.nextLong(), seeds.nextLong(), seeds.nextLong(),
                    seeds.nextLong(), seeds.nextLong());
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    LANES, HISTORY, paired.demandParameters(), 1.0,
                    ContextStructure.DENSE_INDEPENDENT_LEVELS, BaseStructure.THREE_LEVEL_WIDE);
            MultiQueryReplication generated = TRBSVUSyntheticDemandGenerator.generateMultiQuery(
                    parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, 1, 1,
                    paired.contexts(), paired.historicalNoise(), paired.oosNoise());
            ProcurementParams current = TRBSVUProcurementGenerator.generate(
                    CARRIERS, parameters.typicalDemand(), paired.procurement());
            ProcurementParams persistent =
                    TRBSVUSingleSampleCardinalityDiagnostic.persistentCarrierRates(current,
                            paired.procurement() ^ 0x5DEECE66DL, 0.7, 1.3);
            double maximumScale = maximumFeasibleScale(persistent);
            feasibility.add(String.format(Locale.ROOT, "%d\t%.10f",
                    replication, maximumScale));
            Files.write(output.resolve("mqc_scale_feasibility.tsv"), feasibility,
                    StandardCharsets.UTF_8);
            for (double scale : SCALES) {
                if (scale > maximumScale + 1e-12) continue;
                ProcurementParams market =
                        TRBSVUSingleSampleCardinalityDiagnostic.scaleMqc(persistent, scale);
                for (int t = 0; t < HISTORY; t++) {
                    Sample observed = generated.history.get(t);
                    solve(rows, replication, scale, t, "REALIZED", observed.theta,
                            observed.demand(), market, lanes, settings);
                    solve(rows, replication, scale, t, "CONDITIONAL_MEAN", observed.theta,
                            parameters.nominalDemand(observed.theta), market, lanes, settings);
                }
                Files.write(output.resolve("mqc_scale_probe.tsv"), rows,
                        StandardCharsets.UTF_8);
            }
        }
    }

    private static double maximumFeasibleScale(ProcurementParams market) {
        double result = Double.POSITIVE_INFINITY;
        for (int i = 0; i < market.I; i++) result = Math.min(result, market.M[i] / market.p[i]);
        return result;
    }

    private static void solve(List<String> rows, int replication, double scale, int sampleId,
                              String type, CovariateVector context, double[] demand,
                              ProcurementParams market, List<String> lanes,
                              TRBSVUSolveMethods.Settings settings) throws Exception {
        Sample sample = unitSample(sampleId, context, demand);
        Solution solution = TRBSVUSolveMethods.solve(market, lanes, List.of(sample), context,
                TRBSVUSolveMethods.Method.NOMINAL, 0.0, settings);
        if (!solution.certifiedOptimal) {
            throw new IllegalStateException("Uncertified MQC scale probe: "
                    + solution.solverStatus);
        }
        SecondStageEvaluator.Result recourse =
                SecondStageEvaluator.evaluate(market, solution.y, demand, true);
        TRBSVUSolveMethods.OosDraw draw = TRBSVUSolveMethods.evaluateDetailed(
                market, solution.y, List.of(sample)).draws().get(0);
        rows.add(String.format(Locale.ROOT,
                "%d\t%.2f\t%d\t%s\t%.10f\t%d\t%s\t%.10f\t%.10f\t%.10f"
                        + "\t%.10f\t%.10f\t%.10f\t%s\t%s\t%.10g",
                replication, scale, sampleId, type, sum(demand), selectedCount(solution.y),
                selected(solution.y), recourse.objective, recourse.transportCost,
                recourse.spotCost, recourse.penaltyCost, draw.spotQuantity(),
                recourse.mqcShortfallQuantity, solution.solverStatus,
                solution.certifiedOptimal, solution.relativeGap));
    }

    private static Sample unitSample(int id, CovariateVector context, double[] demand) {
        LocalDate date = LocalDate.of(2000, 1, 1).plusWeeks(id);
        return new Sample(id, new PeriodData(id, date, date.plusDays(6), demand.clone(),
                0, 0.0, 0.0, 0.0), context.copy(), 1.0);
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static String selected(double[] y) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < y.length; i++) {
            if (y[i] <= 0.5) continue;
            if (!result.isEmpty()) result.append('|');
            result.append(i);
        }
        return result.toString();
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static List<String> laneNames() {
        List<String> result = new ArrayList<>(LANES);
        for (int j = 0; j < LANES; j++) result.add("L" + (j + 1));
        return result;
    }
}
