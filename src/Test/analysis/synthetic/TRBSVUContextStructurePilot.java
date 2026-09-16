package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.ProcurementParams;
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
import java.util.Random;
import java.util.SplittableRandom;

/** Paired small cases that isolate how context loadings change lane mix. */
public final class TRBSVUContextStructurePilot {
    private static final int CARRIERS = 12;
    private static final int LANES = 20;
    private static final int HISTORY = 60;
    private static final int OOS = 500;
    private static final int REPLICATIONS = 3;

    private TRBSVUContextStructurePilot() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <output-directory>");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        List<String> report = new ArrayList<>();
        report.add("replication\tcontext_structure\ttotal_cv\tmean_context_share_tv"
                + "\tmax_context_share_tv\tmean_loading_cosine\tbase_share"
                + "\tsaa_relative_mae\tcsaa_relative_mae\tsaa_share_tv\tcsaa_share_tv\tinstance");
        SplittableRandom seeds = new SplittableRandom(20260916L);
        for (int replication = 1; replication <= REPLICATIONS; replication++) {
            TRBSVUSyntheticCase.Seeds paired = new TRBSVUSyntheticCase.Seeds(
                    seeds.nextLong(), seeds.nextLong(), seeds.nextLong(), seeds.nextLong(), seeds.nextLong());
            Parameters reference = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    LANES, HISTORY, paired.demandParameters(), 1.0,
                    ContextStructure.DENSE_PROPORTIONAL);
            ProcurementParams market = TRBSVUProcurementGenerator.generate(
                    CARRIERS, reference.typicalDemand(), paired.procurement());
            for (ContextStructure structure : ContextStructure.values()) {
                Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                        LANES, HISTORY, paired.demandParameters(), 1.0, structure);
                Replication demand = TRBSVUSyntheticDemandGenerator.generate(parameters,
                        Distribution.LOGNORMAL, Volatility.HIGH, OOS, paired.contexts(),
                        paired.historicalNoise(), paired.oosNoise());
                TRBSVUSyntheticCase instance = new TRBSVUSyntheticCase(market, laneNames(),
                        demand.history, demand.testContext, demand.oos, paired);
                String stem = String.format(Locale.ROOT, "rep%02d_%s", replication,
                        structure.name().toLowerCase(Locale.ROOT));
                Path file = root.resolve(stem + ".instance.tsv");
                TRBSVUSyntheticCaseIO.saveText(instance, file);
                TRBSVUResultWriter.writeDgpParameters(root.resolve(stem + "_parameters"),
                        parameters, Distribution.LOGNORMAL, Volatility.HIGH);
                Stats stats = stats(parameters, demand);
                report.add(String.format(Locale.ROOT,
                        "%d\t%s\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%s",
                        replication, structure, stats.totalCv, stats.meanContextShareTv,
                        stats.maxContextShareTv, stats.meanLoadingCosine, stats.baseShare,
                        stats.saaRelativeMae, stats.csaaRelativeMae, stats.saaShareTv,
                        stats.csaaShareTv,
                        file.getFileName()));
            }
        }
        Files.write(root.resolve("dgp_diagnostic.tsv"), report, StandardCharsets.UTF_8);
        report.forEach(System.out::println);
    }

    private static Stats stats(Parameters parameters, Replication demand) {
        double[] totals = new double[demand.oos.size()];
        for (int s = 0; s < totals.length; s++) {
            for (double value : demand.oos.get(s).demand()) totals[s] += value;
        }
        Random random = new Random(20260916L);
        double[] typicalShare = shares(parameters.typicalDemand());
        double meanTv = 0.0, maxTv = 0.0;
        int draws = 10000;
        for (int n = 0; n < draws; n++) {
            CovariateVector x = new CovariateVector(new double[] {random.nextDouble(),
                    random.nextDouble(), random.nextDouble(), random.nextDouble()});
            double tv = tv(shares(parameters.nominalDemand(x)), typicalShare);
            meanTv += tv;
            maxTv = Math.max(maxTv, tv);
        }
        double[] base = parameters.base(), typical = parameters.typicalDemand();
        double baseShare = 0.0;
        for (int j = 0; j < base.length; j++) baseShare += base[j] / typical[j];
        double[] truth = parameters.nominalDemand(demand.testContext);
        double[] saa = weightedMean(TRBSVUScenarioWeights.equal(demand.history));
        double[] csaa = weightedMean(TRBSVUScenarioWeights.kernel(demand.history,
                demand.testContext, TRBSVUScenarioWeights.Kernel.EXPONENTIAL, 0.5));
        return new Stats(sd(totals) / mean(totals), meanTv / draws, maxTv,
                meanPairCosine(parameters), baseShare / base.length,
                relativeMae(saa, truth), relativeMae(csaa, truth),
                tv(shares(saa), shares(truth)), tv(shares(csaa), shares(truth)));
    }

    private static double meanPairCosine(Parameters p) {
        double[][] a = {p.market(), p.trend(), p.promotion(), p.attention()};
        double total = 0.0;
        int pairs = 0;
        for (int j = 0; j < p.laneCount(); j++) {
            for (int k = j + 1; k < p.laneCount(); k++) {
                double dot = 0.0, left = 0.0, right = 0.0;
                for (double[] values : a) {
                    double x = values[j] / p.base()[j], y = values[k] / p.base()[k];
                    dot += x * y;
                    left += x * x;
                    right += y * y;
                }
                total += dot / Math.sqrt(left * right);
                pairs++;
            }
        }
        return total / pairs;
    }

    private static double[] weightedMean(List<Basic.Sample> samples) {
        double[] result = new double[samples.get(0).demand().length];
        for (Basic.Sample sample : samples) {
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

    private static List<String> laneNames() {
        List<String> result = new ArrayList<>(LANES);
        for (int j = 0; j < LANES; j++) result.add("L" + (j + 1));
        return result;
    }

    private static double[] shares(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        double[] result = values.clone();
        for (int j = 0; j < result.length; j++) result[j] /= sum;
        return result;
    }

    private static double tv(double[] left, double[] right) {
        double sum = 0.0;
        for (int j = 0; j < left.length; j++) sum += Math.abs(left[j] - right[j]);
        return 0.5 * sum;
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.length;
    }

    private static double sd(double[] values) {
        double mean = mean(values), sum = 0.0;
        for (double value : values) {
            double difference = value - mean;
            sum += difference * difference;
        }
        return Math.sqrt(sum / (values.length - 1.0));
    }

    private record Stats(double totalCv, double meanContextShareTv, double maxContextShareTv,
                         double meanLoadingCosine, double baseShare, double saaRelativeMae,
                         double csaaRelativeMae, double saaShareTv, double csaaShareTv) { }
}
