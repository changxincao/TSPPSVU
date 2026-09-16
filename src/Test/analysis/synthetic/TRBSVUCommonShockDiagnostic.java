package Test.analysis.synthetic;

import Basic.CovariateVector;
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

/** Generation-only controlled comparison of independent and one-factor residuals. */
public final class TRBSVUCommonShockDiagnostic {
    private TRBSVUCommonShockDiagnostic() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <output-directory>");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);

        SplittableRandom seedSource = new SplittableRandom(20260915L);
        TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                seedSource.nextLong(), seedSource.nextLong(), seedSource.nextLong(),
                seedSource.nextLong(), seedSource.nextLong());
        int carriers = 15, lanes = 50, history = 100, oos = 1000;
        Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                lanes, history, seeds.demandParameters());
        Replication independent = TRBSVUSyntheticDemandGenerator.generate(parameters,
                Distribution.NORMAL, Volatility.LOW, oos, seeds.contexts(),
                seeds.historicalNoise(), seeds.oosNoise(), false);
        Replication factor = TRBSVUSyntheticDemandGenerator.generate(parameters,
                Distribution.NORMAL, Volatility.LOW, oos, seeds.contexts(),
                seeds.historicalNoise(), seeds.oosNoise(), true);
        ProcurementParams market = TRBSVUProcurementGenerator.generate(
                carriers, parameters.typicalDemand(), seeds.procurement());
        List<String> laneNames = new ArrayList<>(lanes);
        for (int j = 0; j < lanes; j++) laneNames.add("L" + (j + 1));
        TRBSVUSyntheticCase independentCase = new TRBSVUSyntheticCase(
                market, laneNames, independent.history, independent.testContext, independent.oos, seeds);
        TRBSVUSyntheticCase factorCase = new TRBSVUSyntheticCase(
                market, laneNames, factor.history, factor.testContext, factor.oos, seeds);
        Path independentFile = root.resolve("independent.instance.tsv");
        Path factorFile = root.resolve("factor_u02_u06.instance.tsv");
        TRBSVUSyntheticCaseIO.saveText(independentCase, independentFile);
        TRBSVUSyntheticCaseIO.saveText(factorCase, factorFile);
        TRBSVUResultWriter.writeDgpParameters(root, parameters,
                Distribution.NORMAL, Volatility.LOW);

        DemandStats independentStats = stats(independent.oos, parameters, independent.testContext);
        DemandStats factorStats = stats(factor.oos, parameters, factor.testContext);
        CoefficientStats coefficients = coefficientStats(parameters);
        String report = "variant\ttotal_mean\ttotal_cv\tmedian_pair_residual_corr\tmean_lane_cv\n"
                + row("independent", independentStats)
                + row("factor_u02_u06", factorStats)
                + String.format(Locale.ROOT,
                "\ncoefficient_diagnostic\tvalue\nmean_base_share_of_typical\t%.10f\n"
                        + "mean_market_share_of_typical\t%.10f\nmean_trend_share_of_typical\t%.10f\n"
                        + "mean_promotion_share_of_typical\t%.10f\nmean_attention_share_of_typical\t%.10f\n"
                        + "mean_context_loading_cosine\t%.10f\nmean_lane_share_tv\t%.10f\n"
                        + "max_lane_share_tv\t%.10f\n",
                coefficients.baseShare, coefficients.marketShare, coefficients.trendShare,
                coefficients.promotionShare, coefficients.attentionShare,
                coefficients.meanLoadingCosine, coefficients.meanShareTv, coefficients.maxShareTv);
        Files.writeString(root.resolve("diagnostic.tsv"), report, StandardCharsets.UTF_8);
        System.out.print(report);
        System.out.println("independent_instance=" + independentFile);
        System.out.println("factor_instance=" + factorFile);
    }

    private static String row(String name, DemandStats stats) {
        return String.format(Locale.ROOT, "%s\t%.10f\t%.10f\t%.10f\t%.10f%n",
                name, stats.totalMean, stats.totalCv, stats.medianPairCorrelation,
                stats.meanLaneCv);
    }

    private static DemandStats stats(List<Sample> samples, Parameters parameters,
                                     CovariateVector context) {
        int n = samples.size(), lanes = parameters.laneCount();
        double[] nominal = parameters.nominalDemand(context);
        double[] totals = new double[n];
        double[][] residual = new double[lanes][n];
        for (int s = 0; s < n; s++) {
            double[] demand = samples.get(s).demand();
            for (int j = 0; j < lanes; j++) {
                totals[s] += demand[j];
                residual[j][s] = demand[j] / nominal[j] - 1.0;
            }
        }
        double laneCv = 0.0;
        for (int j = 0; j < lanes; j++) laneCv += sd(residual[j]) / (1.0 + mean(residual[j]));
        double[] correlations = new double[lanes * (lanes - 1) / 2];
        int index = 0;
        for (int j = 0; j < lanes; j++) {
            for (int k = j + 1; k < lanes; k++) correlations[index++] = correlation(residual[j], residual[k]);
        }
        java.util.Arrays.sort(correlations);
        return new DemandStats(mean(totals), sd(totals) / mean(totals),
                correlations[correlations.length / 2], laneCv / lanes);
    }

    private static CoefficientStats coefficientStats(Parameters p) {
        double[] base = p.base(), market = p.market(), trend = p.trend();
        double[] promotion = p.promotion(), attention = p.attention(), typical = p.typicalDemand();
        double baseShare = 0, marketShare = 0, trendShare = 0, promotionShare = 0, attentionShare = 0;
        double averageTrend = (p.historicalPeriods() - 1.0) / (2.0 * p.historicalPeriods());
        for (int j = 0; j < p.laneCount(); j++) {
            baseShare += base[j] / typical[j];
            marketShare += 0.5 * market[j] / typical[j];
            trendShare += averageTrend * trend[j] / typical[j];
            promotionShare += 0.5 * promotion[j] / typical[j];
            attentionShare += 0.5 * attention[j] / typical[j];
        }
        double cosine = 0.0;
        int pairs = 0;
        for (int j = 0; j < p.laneCount(); j++) {
            double[] left = {market[j] / base[j], trend[j] / base[j],
                    promotion[j] / base[j], attention[j] / base[j]};
            for (int k = j + 1; k < p.laneCount(); k++) {
                double[] right = {market[k] / base[k], trend[k] / base[k],
                        promotion[k] / base[k], attention[k] / base[k]};
                cosine += cosine(left, right);
                pairs++;
            }
        }
        java.util.Random contexts = new java.util.Random(20260916L);
        double[] reference = shares(typical);
        double tv = 0.0, maxTv = 0.0;
        int draws = 10000;
        for (int draw = 0; draw < draws; draw++) {
            double[] x = {contexts.nextDouble(), contexts.nextDouble(),
                    contexts.nextDouble(), contexts.nextDouble()};
            double value = tv(shares(p.nominalDemand(new CovariateVector(x))), reference);
            tv += value;
            maxTv = Math.max(maxTv, value);
        }
        int lanes = p.laneCount();
        return new CoefficientStats(baseShare / lanes, marketShare / lanes,
                trendShare / lanes, promotionShare / lanes, attentionShare / lanes,
                cosine / pairs, tv / draws, maxTv);
    }

    private static double[] shares(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        double[] result = values.clone();
        for (int j = 0; j < result.length; j++) result[j] /= total;
        return result;
    }

    private static double tv(double[] a, double[] b) {
        double sum = 0.0;
        for (int j = 0; j < a.length; j++) sum += Math.abs(a[j] - b[j]);
        return 0.5 * sum;
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0.0, aa = 0.0, bb = 0.0;
        for (int k = 0; k < a.length; k++) {
            dot += a[k] * b[k];
            aa += a[k] * a[k];
            bb += b[k] * b[k];
        }
        return dot / Math.sqrt(aa * bb);
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

    private static double correlation(double[] a, double[] b) {
        double ma = mean(a), mb = mean(b), numerator = 0.0, va = 0.0, vb = 0.0;
        for (int s = 0; s < a.length; s++) {
            double da = a[s] - ma, db = b[s] - mb;
            numerator += da * db;
            va += da * da;
            vb += db * db;
        }
        return numerator / Math.sqrt(va * vb);
    }

    private record DemandStats(double totalMean, double totalCv,
                               double medianPairCorrelation, double meanLaneCv) { }
    private record CoefficientStats(double baseShare, double marketShare, double trendShare,
                                    double promotionShare, double attentionShare,
                                    double meanLoadingCosine, double meanShareTv,
                                    double maxShareTv) { }
}
