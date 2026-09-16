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

/** Small paired screen for signal strength, noise, and training-sample count. */
public final class TRBSVUContextMechanismProbe {
    private static final int CARRIERS = 12;
    private static final int LANES = 20;
    private static final int FULL_HISTORY = 100;
    private static final int OOS = 500;

    private TRBSVUContextMechanismProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <output-directory>");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                3713309592801802477L, -1267002810217525190L, 5852259277485902924L,
                802772394441415525L, -6043645983141267018L);
        Parameters marketReference = TRBSVUSyntheticDemandGenerator.sampleParameters(
                LANES, FULL_HISTORY, seeds.demandParameters(), 1.0,
                ContextStructure.SIGNED_CENTERED);
        ProcurementParams market = TRBSVUProcurementGenerator.generate(
                CARRIERS, marketReference.typicalDemand(), seeds.procurement());
        List<String> manifest = new ArrayList<>();
        manifest.add("case\ttraining_samples\tcontext_scale\tvolatility\ttotal_cv"
                + "\tsaa_relative_mae\tcsaa_relative_mae\tsaa_share_tv\tcsaa_share_tv");
        for (double scale : new double[] {1.0, 1.3}) {
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    LANES, FULL_HISTORY, seeds.demandParameters(), scale,
                    ContextStructure.SIGNED_CENTERED);
            for (Volatility volatility : new Volatility[] {Volatility.MEDIUM, Volatility.HIGH}) {
                Replication full = TRBSVUSyntheticDemandGenerator.generate(parameters,
                        Distribution.LOGNORMAL, volatility, OOS, seeds.contexts(),
                        seeds.historicalNoise(), seeds.oosNoise());
                for (int samples : new int[] {60, 100}) {
                    List<Sample> history = full.history.subList(FULL_HISTORY - samples, FULL_HISTORY);
                    String stem = String.format(Locale.ROOT, "n%03d_scale%02d_%s", samples,
                            Math.round(10.0 * scale), volatility.name().toLowerCase(Locale.ROOT));
                    TRBSVUSyntheticCase instance = new TRBSVUSyntheticCase(market, laneNames(),
                            history, full.testContext, full.oos, seeds);
                    TRBSVUSyntheticCaseIO.saveText(instance, root.resolve(stem + ".instance.tsv"));
                    double[] truth = parameters.nominalDemand(full.testContext);
                    double[] saa = weightedMean(TRBSVUScenarioWeights.equal(history));
                    double[] csaa = weightedMean(TRBSVUScenarioWeights.kernel(history,
                            full.testContext, TRBSVUScenarioWeights.Kernel.EXPONENTIAL, 0.5));
                    manifest.add(String.format(Locale.ROOT,
                            "%s\t%d\t%.1f\t%s\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                            stem, samples, scale, volatility, totalCv(full.oos),
                            relativeMae(saa, truth), relativeMae(csaa, truth),
                            tv(shares(saa), shares(truth)), tv(shares(csaa), shares(truth))));
                }
            }
        }
        Files.write(root.resolve("probe_manifest.tsv"), manifest, StandardCharsets.UTF_8);
        manifest.forEach(System.out::println);
    }

    private static List<String> laneNames() {
        List<String> result = new ArrayList<>(LANES);
        for (int j = 0; j < LANES; j++) result.add("L" + (j + 1));
        return result;
    }

    private static double[] weightedMean(List<Sample> samples) {
        double[] result = new double[samples.get(0).demand().length];
        for (Sample sample : samples) {
            for (int j = 0; j < result.length; j++) result[j] += sample.weight * sample.demand()[j];
        }
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
        for (int s = 0; s < samples.size(); s++) {
            for (double value : samples.get(s).demand()) totals[s] += value;
        }
        double mean = 0.0;
        for (double value : totals) mean += value / totals.length;
        double sum = 0.0;
        for (double value : totals) sum += (value - mean) * (value - mean);
        return Math.sqrt(sum / (totals.length - 1.0)) / mean;
    }

    private static double[] shares(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        double[] result = values.clone();
        for (int j = 0; j < result.length; j++) result[j] /= total;
        return result;
    }

    private static double tv(double[] left, double[] right) {
        double result = 0.0;
        for (int j = 0; j < left.length; j++) result += Math.abs(left[j] - right[j]);
        return 0.5 * result;
    }
}
