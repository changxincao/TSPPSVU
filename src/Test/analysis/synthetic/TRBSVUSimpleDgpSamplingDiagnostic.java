package Test.analysis.synthetic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Data-only screen for a deliberately simple synthetic-DGP revision.
 * It does not construct procurement instances or inspect optimization results.
 */
public final class TRBSVUSimpleDgpSamplingDiagnostic {
    private static final int LANES = 50;
    private static final int REPLICATIONS = 200;
    private static final int CONTEXT_DRAWS = 2_000;
    private static final long SEED = 20260917L;

    private TRBSVUSimpleDgpSamplingDiagnostic() { }

    private enum BaseDesign { ORIGINAL, THREE_LEVEL }
    private enum CoefficientDesign { ORIGINAL, WIDE_POSITIVE, INDEPENDENT_LEVELS }

    private record Design(String name, BaseDesign base, CoefficientDesign coefficients) { }

    private record Summary(double baseCv, double topFiveShare, double loadingCosine,
                           double meanShareTv, double q95ShareTv, double totalMeanCv,
                           double meanLaneQ90Q10Ratio) { }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: <output-directory>");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        List<Design> designs = List.of(
                new Design("original", BaseDesign.ORIGINAL, CoefficientDesign.ORIGINAL),
                new Design("three_level_original_coefficients", BaseDesign.THREE_LEVEL,
                        CoefficientDesign.ORIGINAL),
                new Design("three_level_wide_positive", BaseDesign.THREE_LEVEL,
                        CoefficientDesign.WIDE_POSITIVE),
                new Design("three_level_independent_effect_levels", BaseDesign.THREE_LEVEL,
                        CoefficientDesign.INDEPENDENT_LEVELS));

        List<String> details = new ArrayList<>();
        details.add("replication\tdesign\tbase_cv\ttop5_share\tmean_loading_cosine"
                + "\tmean_context_share_tv\tq95_context_share_tv\ttotal_conditional_mean_cv"
                + "\tmean_lane_q90_q10_ratio");
        List<String> aggregate = new ArrayList<>();
        aggregate.add("design\treplications\tmean_base_cv\tmean_top5_share"
                + "\tmean_loading_cosine\tmean_context_share_tv\tmean_q95_context_share_tv"
                + "\tmean_total_conditional_mean_cv\tmean_lane_q90_q10_ratio");

        for (int designIndex = 0; designIndex < designs.size(); designIndex++) {
            Design design = designs.get(designIndex);
            double[] sums = new double[7];
            for (int replication = 1; replication <= REPLICATIONS; replication++) {
                long seed = SEED + 1_000_003L * replication;
                Parameters parameters = sample(design, seed);
                Summary summary = summarize(parameters, seed ^ 0x9E3779B97F4A7C15L);
                double[] values = {summary.baseCv, summary.topFiveShare,
                        summary.loadingCosine, summary.meanShareTv, summary.q95ShareTv,
                        summary.totalMeanCv, summary.meanLaneQ90Q10Ratio};
                for (int i = 0; i < sums.length; i++) sums[i] += values[i];
                details.add(String.format(Locale.ROOT,
                        "%d\t%s\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                        replication, design.name, values[0], values[1], values[2], values[3],
                        values[4], values[5], values[6]));
            }
            aggregate.add(String.format(Locale.ROOT,
                    "%s\t%d\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                    design.name, REPLICATIONS, sums[0] / REPLICATIONS,
                    sums[1] / REPLICATIONS, sums[2] / REPLICATIONS,
                    sums[3] / REPLICATIONS, sums[4] / REPLICATIONS,
                    sums[5] / REPLICATIONS, sums[6] / REPLICATIONS));
        }
        Files.write(output.resolve("sampling_details.tsv"), details, StandardCharsets.UTF_8);
        Files.write(output.resolve("sampling_summary.tsv"), aggregate, StandardCharsets.UTF_8);
        aggregate.forEach(System.out::println);
    }

    private static Parameters sample(Design design, long seed) {
        Random random = new Random(seed);
        double[] base = new double[LANES];
        if (design.base == BaseDesign.ORIGINAL) {
            for (int j = 0; j < LANES; j++) base[j] = uniform(random, 10.0, 30.0);
        } else {
            List<Integer> levels = new ArrayList<>(LANES);
            for (int j = 0; j < LANES; j++) levels.add(j % 3);
            Collections.shuffle(levels, random);
            for (int j = 0; j < LANES; j++) {
                base[j] = switch (levels.get(j)) {
                    case 0 -> uniform(random, 5.0, 15.0);
                    case 1 -> uniform(random, 25.0, 50.0);
                    default -> uniform(random, 75.0, 125.0);
                };
            }
        }
        double[][] beta = new double[LANES][4];
        for (int j = 0; j < LANES; j++) {
            for (int k = 0; k < 4; k++) {
                beta[j][k] = switch (design.coefficients) {
                    case ORIGINAL -> k == 1 ? uniform(random, 0.2, 0.4)
                            : uniform(random, 0.3, 0.6);
                    case WIDE_POSITIVE -> uniform(random, 0.1, 0.9);
                    case INDEPENDENT_LEVELS -> effectLevel(random);
                };
            }
        }
        return new Parameters(base, beta);
    }

    private static double effectLevel(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> uniform(random, 0.1, 0.3);
            case 1 -> uniform(random, 0.4, 0.6);
            default -> uniform(random, 0.7, 0.9);
        };
    }

    private static Summary summarize(Parameters p, long contextSeed) {
        Random random = new Random(contextSeed);
        double[][] demands = new double[CONTEXT_DRAWS][LANES];
        double[] totals = new double[CONTEXT_DRAWS];
        double[] meanDemand = new double[LANES];
        for (int n = 0; n < CONTEXT_DRAWS; n++) {
            double[] context = {random.nextDouble(), random.nextDouble(),
                    random.nextDouble(), random.nextDouble()};
            for (int j = 0; j < LANES; j++) {
                double multiplier = 1.0;
                for (int k = 0; k < 4; k++) multiplier += p.beta[j][k] * context[k];
                demands[n][j] = p.base[j] * multiplier;
                totals[n] += demands[n][j];
                meanDemand[j] += demands[n][j] / CONTEXT_DRAWS;
            }
        }
        double[] referenceShare = shares(meanDemand);
        double[] tv = new double[CONTEXT_DRAWS];
        for (int n = 0; n < CONTEXT_DRAWS; n++) tv[n] = tv(shares(demands[n]), referenceShare);
        double laneRatio = 0.0;
        for (int j = 0; j < LANES; j++) {
            double[] lane = new double[CONTEXT_DRAWS];
            for (int n = 0; n < CONTEXT_DRAWS; n++) lane[n] = demands[n][j];
            java.util.Arrays.sort(lane);
            laneRatio += quantile(lane, 0.90) / quantile(lane, 0.10);
        }
        return new Summary(cv(p.base), topShare(p.base, 5), meanPairCosine(p.beta),
                mean(tv), quantileSortedCopy(tv, 0.95), cv(totals), laneRatio / LANES);
    }

    private static double meanPairCosine(double[][] beta) {
        double sum = 0.0;
        int pairs = 0;
        for (int j = 0; j < beta.length; j++) {
            for (int l = j + 1; l < beta.length; l++) {
                double dot = 0.0, left = 0.0, right = 0.0;
                for (int k = 0; k < beta[j].length; k++) {
                    dot += beta[j][k] * beta[l][k];
                    left += beta[j][k] * beta[j][k];
                    right += beta[l][k] * beta[l][k];
                }
                sum += dot / Math.sqrt(left * right);
                pairs++;
            }
        }
        return sum / pairs;
    }

    private static double[] shares(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        double[] result = values.clone();
        for (int i = 0; i < result.length; i++) result[i] /= sum;
        return result;
    }

    private static double tv(double[] left, double[] right) {
        double value = 0.0;
        for (int i = 0; i < left.length; i++) value += Math.abs(left[i] - right[i]);
        return 0.5 * value;
    }

    private static double topShare(double[] values, int count) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        double top = 0.0, total = 0.0;
        for (double value : sorted) total += value;
        for (int i = 0; i < count; i++) top += sorted[sorted.length - 1 - i];
        return top / total;
    }

    private static double cv(double[] values) {
        return sd(values) / mean(values);
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

    private static double quantileSortedCopy(double[] values, double probability) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return quantile(sorted, probability);
    }

    private static double quantile(double[] sorted, double probability) {
        double position = probability * (sorted.length - 1);
        int lower = (int) Math.floor(position), upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        double fraction = position - lower;
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction;
    }

    private static double uniform(Random random, double lower, double upper) {
        return lower + (upper - lower) * random.nextDouble();
    }

    private record Parameters(double[] base, double[][] beta) { }
}
