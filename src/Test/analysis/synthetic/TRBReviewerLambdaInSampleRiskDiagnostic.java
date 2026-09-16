package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Model.SecondStageEvaluator;
import Test.analysis.synthetic.TRBReviewerOlistDynamicDgpV2.Calibration;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSolveBridge.PreparedInput;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only check of in-sample mean and risk for saved lambda-curve decisions. */
public final class TRBReviewerLambdaInSampleRiskDiagnostic {
    private static final double[] LAMBDAS = {0.01, 0.05, 0.1, 1.0, 5.0, 10.0, 50.0, 100.0, 300.0};
    private static final Pattern Y_PATTERN = Pattern.compile("\\\"\\[([^]]+)]\\\"");

    private TRBReviewerLambdaInSampleRiskDiagnostic() {
    }

    /** Usage: {@code <weekly-csv> <markets-00-09-root> <markets-10-19-root> <min|max>}. */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: <weekly-csv> <markets-00-09-root> <markets-10-19-root> <min|max>");
        }
        Path weekly = Path.of(args[0]);
        Path lowRoot = Path.of(args[1]);
        Path highRoot = Path.of(args[2]);
        String hRule = args[3];
        Calibration calibration = TRBReviewerOlistDynamicDgpV2.calibrate(weekly, 23);

        System.out.println("h,market,lambda,selected,selectedMqc,selectedMqcTimesH,trainingMean,trainingSd,trainingMax,"
                + "trainingMaxDemand,trainingMaxSpot,trainingMaxPenalty,trainingMaxWeight,"
                + "trainingMaxShortfall,vertexRadius,meanPlusLambdaSd,savedObjective,distanceToTrainingMax,"
                + "oosMax,oosMaxDemand,oosMaxSpot,oosMaxPenalty,oosMaxShortfall,oosLargestPenalty,"
                + "oosDemandAtLargestPenalty,trainingMaxDemandRawCdf,trainingMaxDemandWeightedCdf,"
                + "oosMaxDemandRawCdf");
        for (int market = 0; market < 20; market++) {
            Path marketRoot = (market < 10 ? lowRoot : highRoot)
                    .resolve(String.format(Locale.US, "market_%02d", market));
            if (!allCertified(marketRoot)) continue;

            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(
                    marketRoot.resolve("experiment.properties"), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            int procurementSeed = Integer.parseInt(properties.getProperty("procurementSeed"));
            long demandSeed = Long.parseLong(properties.getProperty("demandSeed"));

            Config procurementConfig = new Config();
            procurementConfig.seed = procurementSeed;
            InstanceGenerator.GenConfig marketConfig = new InstanceGenerator.GenConfig();
            marketConfig.betaRatio = 0.70;
            ProcurementParams fullMarket = InstanceGenerator.generate(
                    30, calibration.baselineDemand(), marketConfig, procurementConfig);
            fullMarket = TRBReviewerR7CoverageMqcGridExperiment.withMarketSizeScale(
                    fullMarket, 10.0 / 30.0);
            ProcurementParams params = TRBReviewerR7CoverageMqcGridExperiment.withNestedCoverage(
                    fullMarket, calibration.baselineDemand(), 1.0, 1.0, hRule, true);
            ReplicationData demand = TRBReviewerOlistDynamicDgpV2.generate(
                    calibration, 100, 200, 500, demandSeed,
                    InnovationDistribution.LOGNORMAL, 0.345, 0.50, 0.0, 0.0);

            Config weightConfig = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                    Method.DRO, 1, 0.5, 1.0, 1, 600, 0);
            PreparedInput prepared = TRBReviewerR3M3SyntheticSolveBridge
                    .prepare(demand, params, weightConfig);
            List<Sample> training = prepared.solveData.samples;

            for (double lambda : LAMBDAS) {
                SavedDecision saved = readDecision(summaryPath(marketRoot, lambda));
                double[] costs = new double[training.size()];
                double mean = 0.0;
                double maximum = Double.NEGATIVE_INFINITY;
                double maximumDemand = Double.NaN;
                double maximumSpot = Double.NaN;
                double maximumPenalty = Double.NaN;
                double maximumShortfall = Double.NaN;
                double maximumWeight = Double.NaN;
                for (int scenario = 0; scenario < training.size(); scenario++) {
                    Sample sample = training.get(scenario);
                    SecondStageEvaluator.Result evaluated = SecondStageEvaluator.evaluate(
                            params, saved.y, sample.demand(), true);
                    costs[scenario] = evaluated.objective;
                    mean += sample.weight * costs[scenario];
                    if (costs[scenario] > maximum) {
                        maximum = costs[scenario];
                        maximumDemand = sum(sample.demand());
                        maximumSpot = evaluated.spotCost;
                        maximumPenalty = evaluated.penaltyCost;
                        maximumShortfall = evaluated.mqcShortfallQuantity;
                        maximumWeight = sample.weight;
                    }
                }
                double variance = 0.0;
                for (int scenario = 0; scenario < training.size(); scenario++) {
                    double centered = costs[scenario] - mean;
                    variance += training.get(scenario).weight * centered * centered;
                }
                double sd = Math.sqrt(Math.max(0.0, variance));
                double reconstructed = mean + lambda * sd;
                OosExtremes oos = readOosExtremes(
                        summaryPath(marketRoot, lambda).resolveSibling("oos_costs.csv"),
                        prepared.oosSamples);
                double selectedMqc = 0.0;
                double selectedMqcTimesH = 0.0;
                for (int carrier = 0; carrier < saved.y.length; carrier++) {
                    if (saved.y[carrier] > 0.5) {
                        selectedMqc += params.p[carrier];
                        selectedMqcTimesH += params.p[carrier] * params.h[carrier];
                    }
                }
                System.out.printf(Locale.US,
                        "%s,%d,%.17g,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,"
                                + "%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,"
                                + "%.17g,%.17g,%.17g%n",
                        hRule, market, lambda, selected(saved.y), selectedMqc, selectedMqcTimesH, mean, sd,
                        maximum, maximumDemand, maximumSpot, maximumPenalty, maximumWeight,
                        maximumShortfall, Math.sqrt(1.0 / maximumWeight - 1.0), reconstructed, saved.objective,
                        Math.abs(maximum - saved.objective), oos.maximumTotal,
                        oos.demandAtMaximumTotal, oos.spotAtMaximumTotal,
                        oos.penaltyAtMaximumTotal, oos.shortfallAtMaximumTotal, oos.maximumPenalty,
                        oos.demandAtMaximumPenalty, rawDemandCdf(training, maximumDemand),
                        weightedDemandCdf(training, maximumDemand), oos.demandAtMaximumTotalRawCdf);
            }
        }
    }

    private static boolean allCertified(Path marketRoot) throws Exception {
        for (double lambda : LAMBDAS) {
            Path summary = summaryPath(marketRoot, lambda);
            if (!Files.isRegularFile(summary)) return false;
            String line = Files.readAllLines(summary, StandardCharsets.UTF_8).get(1);
            String beforeY = line.substring(0, line.indexOf(",\"["));
            String[] fields = beforeY.split(",", -1);
            if (!Boolean.parseBoolean(fields[12])) return false;
        }
        return true;
    }

    private static Path summaryPath(Path marketRoot, double lambda) {
        return marketRoot.resolve("B_0_5")
                .resolve("lambda_" + Double.toString(lambda).replace('.', '_'))
                .resolve("solve_summary.csv");
    }

    private static SavedDecision readDecision(Path path) throws Exception {
        String line = Files.readAllLines(path, StandardCharsets.UTF_8).get(1);
        String beforeY = line.substring(0, line.indexOf(",\"["));
        double objective = Double.parseDouble(beforeY.split(",", -1)[6]);
        Matcher matcher = Y_PATTERN.matcher(line);
        if (!matcher.find()) throw new IllegalStateException("Missing yBinary in " + path);
        String[] values = matcher.group(1).split(",");
        double[] y = new double[values.length];
        for (int i = 0; i < values.length; i++) y[i] = Double.parseDouble(values[i]);
        return new SavedDecision(y, objective);
    }

    private static int selected(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static double rawDemandCdf(List<Sample> samples, double threshold) {
        int notGreater = 0;
        for (Sample sample : samples) {
            if (sum(sample.demand()) <= threshold + 1e-9) notGreater++;
        }
        return (double) notGreater / samples.size();
    }

    private static double weightedDemandCdf(List<Sample> samples, double threshold) {
        double cumulative = 0.0;
        for (Sample sample : samples) {
            if (sum(sample.demand()) <= threshold + 1e-9) cumulative += sample.weight;
        }
        return cumulative;
    }

    private static OosExtremes readOosExtremes(Path path, List<Sample> oosSamples) throws Exception {
        double maximumTotal = Double.NEGATIVE_INFINITY;
        double demandAtMaximumTotal = Double.NaN;
        double spotAtMaximumTotal = Double.NaN;
        double penaltyAtMaximumTotal = Double.NaN;
        double shortfallAtMaximumTotal = Double.NaN;
        double maximumPenalty = Double.NEGATIVE_INFINITY;
        double demandAtMaximumPenalty = Double.NaN;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int row = 1; row < lines.size(); row++) {
            String[] fields = lines.get(row).split(",", -1);
            int draw = Integer.parseInt(fields[1]);
            double total = Double.parseDouble(fields[2]);
            double spot = Double.parseDouble(fields[4]);
            double penalty = Double.parseDouble(fields[5]);
            double shortfall = Double.parseDouble(fields[6]);
            if (total > maximumTotal) {
                maximumTotal = total;
                demandAtMaximumTotal = sum(oosSamples.get(draw).demand());
                spotAtMaximumTotal = spot;
                penaltyAtMaximumTotal = penalty;
                shortfallAtMaximumTotal = shortfall;
            }
            if (penalty > maximumPenalty) {
                maximumPenalty = penalty;
                demandAtMaximumPenalty = sum(oosSamples.get(draw).demand());
            }
        }
        return new OosExtremes(maximumTotal, demandAtMaximumTotal,
                spotAtMaximumTotal, penaltyAtMaximumTotal,
                shortfallAtMaximumTotal, maximumPenalty, demandAtMaximumPenalty,
                rawDemandCdf(oosSamples, demandAtMaximumTotal));
    }

    private record SavedDecision(double[] y, double objective) {
    }

    private record OosExtremes(double maximumTotal,
                               double demandAtMaximumTotal,
                               double spotAtMaximumTotal,
                               double penaltyAtMaximumTotal,
                               double shortfallAtMaximumTotal,
                               double maximumPenalty,
                               double demandAtMaximumPenalty,
                               double demandAtMaximumTotalRawCdf) {
    }
}
