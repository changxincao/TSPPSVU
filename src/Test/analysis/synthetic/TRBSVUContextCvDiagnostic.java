package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
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

/** Controlled cases for context-coefficient and marginal-CV mechanism tests. */
public final class TRBSVUContextCvDiagnostic {
    private TRBSVUContextCvDiagnostic() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <output-directory>");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        SplittableRandom source = new SplittableRandom(20260915L);
        TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                source.nextLong(), source.nextLong(), source.nextLong(), source.nextLong(), source.nextLong());
        List<String> report = new ArrayList<>();
        report.add("case\tcontext_scale\tdistribution\tvolatility\ttotal_mean\ttotal_cv\tmean_lane_cv"
                + "\tmean_oos_draw_share_tv\tmax_oos_draw_share_tv\tmean_context_share_tv"
                + "\tmax_context_share_tv\tbase_share");
        for (double scale : new double[] {1.0, 2.0}) {
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    50, 100, seeds.demandParameters(), scale);
            ProcurementParams market = TRBSVUProcurementGenerator.generate(
                    15, parameters.typicalDemand(), seeds.procurement());
            List<String> lanes = laneNames(50);
            Distribution[] distributions = {Distribution.NORMAL, Distribution.NORMAL, Distribution.LOGNORMAL};
            Volatility[] volatilities = {Volatility.LOW, Volatility.HIGH, Volatility.HIGH};
            for (int cell = 0; cell < distributions.length; cell++) {
                Distribution distribution = distributions[cell];
                Volatility volatility = volatilities[cell];
                Replication demand = TRBSVUSyntheticDemandGenerator.generate(parameters,
                        distribution, volatility, 1000, seeds.contexts(),
                        seeds.historicalNoise(), seeds.oosNoise());
                String name = String.format(Locale.ROOT, "scale%.0f_%s_%s", scale,
                        distribution.name().toLowerCase(Locale.ROOT),
                        volatility.name().toLowerCase(Locale.ROOT));
                TRBSVUSyntheticCase instance = new TRBSVUSyntheticCase(
                        market, lanes, demand.history, demand.testContext, demand.oos, seeds);
                TRBSVUSyntheticCaseIO.saveText(instance, root.resolve(name + ".instance.tsv"));
                Stats stats = stats(demand, parameters);
                report.add(String.format(Locale.ROOT,
                        "%s\t%.1f\t%s\t%s\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                        name, scale, distribution, volatility, stats.totalMean, stats.totalCv,
                        stats.meanLaneCv, stats.meanOosShareTv, stats.maxOosShareTv,
                        stats.meanContextShareTv, stats.maxContextShareTv, stats.baseShare));
            }
            Path parameterDirectory = root.resolve(String.format(Locale.ROOT, "scale%.0f_parameters", scale));
            TRBSVUResultWriter.writeDgpParameters(parameterDirectory, parameters,
                    Distribution.NORMAL, Volatility.LOW);
        }
        Files.write(root.resolve("diagnostic.tsv"), report, StandardCharsets.UTF_8);
        report.forEach(System.out::println);
    }

    private static Stats stats(Replication replication, Parameters parameters) {
        int n = replication.oos.size(), lanes = parameters.laneCount();
        double[] totals = new double[n], laneMeans = new double[lanes], laneSs = new double[lanes];
        double[] nominalShare = shares(parameters.nominalDemand(replication.testContext));
        double shareTv = 0.0, maxShareTv = 0.0;
        for (int s = 0; s < n; s++) {
            double[] demand = replication.oos.get(s).demand();
            for (int j = 0; j < lanes; j++) {
                totals[s] += demand[j];
                laneMeans[j] += demand[j] / n;
            }
            double tv = tv(shares(demand), nominalShare);
            shareTv += tv;
            maxShareTv = Math.max(maxShareTv, tv);
        }
        for (Sample sample : replication.oos) {
            for (int j = 0; j < lanes; j++) {
                double difference = sample.demand()[j] - laneMeans[j];
                laneSs[j] += difference * difference;
            }
        }
        double laneCv = 0.0;
        for (int j = 0; j < lanes; j++) laneCv += Math.sqrt(laneSs[j] / (n - 1.0)) / laneMeans[j];
        double[] base = parameters.base(), typical = parameters.typicalDemand();
        double baseShare = 0.0;
        for (int j = 0; j < lanes; j++) baseShare += base[j] / typical[j];
        java.util.Random contextRandom = new java.util.Random(20260916L);
        double[] typicalShare = shares(typical);
        double contextTv = 0.0, maxContextTv = 0.0;
        int contextDraws = 10000;
        for (int draw = 0; draw < contextDraws; draw++) {
            double[] x = {contextRandom.nextDouble(), contextRandom.nextDouble(),
                    contextRandom.nextDouble(), contextRandom.nextDouble()};
            double value = tv(shares(parameters.nominalDemand(new Basic.CovariateVector(x))), typicalShare);
            contextTv += value;
            maxContextTv = Math.max(maxContextTv, value);
        }
        return new Stats(mean(totals), sd(totals) / mean(totals), laneCv / lanes,
                shareTv / n, maxShareTv, contextTv / contextDraws, maxContextTv,
                baseShare / lanes);
    }

    private static List<String> laneNames(int count) {
        List<String> result = new ArrayList<>(count);
        for (int j = 0; j < count; j++) result.add("L" + (j + 1));
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

    private record Stats(double totalMean, double totalCv, double meanLaneCv,
                         double meanOosShareTv, double maxOosShareTv,
                         double meanContextShareTv, double maxContextShareTv,
                         double baseShare) { }
}
