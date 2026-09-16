package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Replication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/** Probe: preserve system risk while reducing lane-idiosyncratic noise. */
public final class TRBSVUCorrelatedDemandProbe {
    private TRBSVUCorrelatedDemandProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <output-directory>");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        SplittableRandom source = new SplittableRandom(2026091602L);
        List<String> report = new ArrayList<>();
        report.add("replication\ttotal_cv\tsaa_relative_mae\tcsaa_relative_mae"
                + "\tsaa_share_tv\tcsaa_share_tv\tinstance");
        for (int replication = 1; replication <= 3; replication++) {
            TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                    source.nextLong(), source.nextLong(), source.nextLong(), source.nextLong(), source.nextLong());
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    20, 100, seeds.demandParameters(), 1.0,
                    ContextStructure.GROUPED_CENTERED, 0.6, 0.8);
            Replication demand = TRBSVUSyntheticDemandGenerator.generate(parameters,
                    Distribution.LOGNORMAL, Volatility.MEDIUM, 500, seeds.contexts(),
                    seeds.historicalNoise(), seeds.oosNoise());
            ProcurementParams market = TRBSVUProcurementGenerator.generate(
                    12, parameters.typicalDemand(), seeds.procurement());
            String stem = String.format(Locale.ROOT, "rep%02d_grouped_corr", replication);
            TRBSVUSyntheticCase instance = new TRBSVUSyntheticCase(market, laneNames(),
                    demand.history, demand.testContext, demand.oos, seeds);
            TRBSVUSyntheticCaseIO.saveText(instance, root.resolve(stem + ".instance.tsv"));
            double[] truth = parameters.nominalDemand(demand.testContext);
            double[] saa = mean(TRBSVUScenarioWeights.equal(demand.history));
            double[] csaa = mean(TRBSVUScenarioWeights.kernel(demand.history,
                    demand.testContext, TRBSVUScenarioWeights.Kernel.EXPONENTIAL, 0.5));
            report.add(String.format(Locale.ROOT, "%d\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%s",
                    replication, totalCv(demand.oos), relativeMae(saa, truth),
                    relativeMae(csaa, truth), tv(shares(saa), shares(truth)),
                    tv(shares(csaa), shares(truth)), stem + ".instance.tsv"));
        }
        Files.write(root.resolve("probe.tsv"), report, StandardCharsets.UTF_8);
        report.forEach(System.out::println);
    }

    private static List<String> laneNames() {
        List<String> result = new ArrayList<>();
        for (int j = 0; j < 20; j++) result.add("L" + (j + 1));
        return result;
    }

    private static double[] mean(List<Sample> samples) {
        double[] result = new double[samples.get(0).demand().length];
        for (Sample sample : samples)
            for (int j = 0; j < result.length; j++) result[j] += sample.weight * sample.demand()[j];
        return result;
    }

    private static double relativeMae(double[] estimate, double[] truth) {
        double error = 0.0, total = 0.0;
        for (int j = 0; j < truth.length; j++) {
            error += Math.abs(estimate[j] - truth[j]);
            total += truth[j];
        }
        return error / total;
    }

    private static double totalCv(List<Sample> samples) {
        double[] totals = new double[samples.size()];
        for (int s = 0; s < samples.size(); s++)
            for (double value : samples.get(s).demand()) totals[s] += value;
        double mean = 0.0;
        for (double value : totals) mean += value / totals.length;
        double ss = 0.0;
        for (double value : totals) ss += (value - mean) * (value - mean);
        return Math.sqrt(ss / (totals.length - 1.0)) / mean;
    }

    private static double[] shares(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        double[] result = values.clone();
        for (int j = 0; j < result.length; j++) result[j] /= sum;
        return result;
    }

    private static double tv(double[] left, double[] right) {
        double result = 0.0;
        for (int j = 0; j < left.length; j++) result += Math.abs(left[j] - right[j]);
        return 0.5 * result;
    }
}
