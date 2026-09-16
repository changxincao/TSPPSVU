package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.calculateHelper.KernelType;
import Test.analysis.synthetic.TRBReviewerOlistDynamicDgpV2.Calibration;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticMainSolve.Result;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** One-market pilot for the manuscript's lambda-sensitivity grid. */
public final class TRBReviewerOlistDynamicDgpV2LambdaFigurePilot {
    private static final double[] B_VALUES = {0.1, 0.5, 1.0, 100.0};
    private static final double[] LAMBDAS = {
            0.0, 0.01, 0.05, 0.1, 1.0, 5.0, 10.0,
            50.0, 100.0, 300.0, 1000.0, 10000.0, 50000.0, 100000.0
    };

    private TRBReviewerOlistDynamicDgpV2LambdaFigurePilot() {
    }

    /** Usage: {@code <weekly-csv> <output-root> <procurement-seed> <demand-seed>}. */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: <weekly-csv> <output-root> <procurement-seed> <demand-seed>");
        }
        Path weekly = Path.of(args[0]).toAbsolutePath().normalize();
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        int procurementSeed = Integer.parseInt(args[2]);
        long demandSeed = Long.parseLong(args[3]);
        double coverage = doubleProperty("trb.coverage", 0.50);
        int trainingSamples = intProperty("trb.trainingSamples", 50);
        String hRule = hRuleProperty("trb.hRule", "max");
        double[] bValues = doubleArrayProperty("trb.bValues", B_VALUES);
        double[] lambdas = doubleArrayProperty("trb.lambdas", LAMBDAS);
        requireEmpty(root);
        Files.createDirectories(root);

        Calibration calibration = TRBReviewerOlistDynamicDgpV2.calibrate(weekly, 23);
        Config procurementConfig = new Config();
        procurementConfig.seed = procurementSeed;
        InstanceGenerator.GenConfig marketConfig = new InstanceGenerator.GenConfig();
        marketConfig.betaRatio = 0.70;
        ProcurementParams fullMarket = InstanceGenerator.generate(
                30, calibration.baselineDemand(), marketConfig, procurementConfig);
        fullMarket = TRBReviewerR7CoverageMqcGridExperiment.withMarketSizeScale(
                fullMarket, 10.0 / 30.0);
        ProcurementParams params = TRBReviewerR7CoverageMqcGridExperiment.withNestedCoverage(
                fullMarket, calibration.baselineDemand(), coverage, 1.00, hRule, true);
        ReplicationData demand = TRBReviewerOlistDynamicDgpV2.generate(
                calibration, trainingSamples, 200, 500, demandSeed,
                InnovationDistribution.LOGNORMAL, 0.345, 0.50, 0.0, 0.0);
        writeProperties(root.resolve("experiment.properties"), weekly,
                procurementSeed, demandSeed, params.beta, coverage, trainingSamples,
                hRule, bValues, lambdas);

        Config deterministicConfig =
                TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                        Method.D, 1, 1.0, 1.0, 1, 600, 0);
        deterministicConfig.kernelType = KernelType.EXPONENTIAL;
        Path deterministicOutput = root.resolve("D");
        Result deterministic = TRBReviewerR3M3SyntheticMainSolve.run(
                "D", demand, params, deterministicConfig, deterministicOutput);
        double deterministicMax = maxOosCost(
                deterministicOutput.resolve("oos_costs.csv"));

        List<Row> rows = new ArrayList<>();
        for (double b : bValues) {
            for (double lambda : lambdas) {
                Method method = lambda == 0.0 ? Method.CSAA : Method.DRO;
                Config solveConfig =
                        TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                                method, 1, b, lambda == 0.0 ? 1.0 : lambda,
                                1, 600, 0);
                solveConfig.kernelType = KernelType.EXPONENTIAL;
                Path output = root.resolve(String.format(Locale.US,
                        "B_%s/lambda_%s", token(b), token(lambda)));
                Result result = TRBReviewerR3M3SyntheticMainSolve.run(
                        lambda == 0.0 ? "CSAA" : "DRO",
                        demand, params, solveConfig, output);
                double maximum = maxOosCost(output.resolve("oos_costs.csv"));
                rows.add(new Row(b, lambda, maximum, result));
            }
        }
        writeResults(root.resolve("lambda_figure_results.csv"),
                deterministic, deterministicMax, rows);
    }

    private static void requireEmpty(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output root must be empty: " + root);
            }
        }
    }

    private static String token(double value) {
        return Double.toString(value).replace('.', '_');
    }

    private static double doubleProperty(String key, double defaultValue) {
        return Double.parseDouble(System.getProperty(key, Double.toString(defaultValue)));
    }

    private static int intProperty(String key, int defaultValue) {
        int value = Integer.parseInt(System.getProperty(key, Integer.toString(defaultValue)));
        if (value <= 0) throw new IllegalArgumentException(key + " must be positive: " + value);
        return value;
    }

    private static String hRuleProperty(String key, String defaultValue) {
        String value = System.getProperty(key, defaultValue).trim().toLowerCase(Locale.ROOT);
        if (!value.equals("min") && !value.equals("max")) {
            throw new IllegalArgumentException(key + " must be min or max: " + value);
        }
        return value;
    }

    private static double[] doubleArrayProperty(String key, double[] defaults) {
        String text = System.getProperty(key);
        if (text == null || text.isBlank()) return defaults.clone();
        String[] fields = text.split(",");
        double[] values = new double[fields.length];
        for (int i = 0; i < fields.length; i++) {
            values[i] = Double.parseDouble(fields[i].trim());
        }
        return values;
    }

    private static double maxOosCost(Path path) throws Exception {
        double maximum = Double.NEGATIVE_INFINITY;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            if (line == null) throw new IllegalStateException("Empty OOS file: " + path);
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",", -1);
                maximum = Math.max(maximum, Double.parseDouble(fields[2]));
            }
        }
        return maximum;
    }

    private static void writeProperties(Path path,
                                        Path weekly,
                                        int procurementSeed,
                                        long demandSeed,
                                        int beta,
                                        double coverage,
                                        int trainingSamples,
                                        String hRule,
                                        double[] bValues,
                                        double[] lambdas) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("purpose", "REPRODUCE_MANUSCRIPT_LAMBDA_FIGURE_PILOT");
        properties.setProperty("weeklyInput", weekly.toString());
        properties.setProperty("I", "30");
        properties.setProperty("J", "23");
        properties.setProperty("S", Integer.toString(trainingSamples));
        properties.setProperty("oosDraws", "500");
        properties.setProperty("coverage", Double.toString(coverage));
        properties.setProperty("capacityNormalized", "true");
        properties.setProperty("hRule", hRule + " eligible r_ij");
        properties.setProperty("mqcScale", "1.0");
        properties.setProperty("distribution", "LOGNORMAL");
        properties.setProperty("innovationCv", "0.345");
        properties.setProperty("laneSharePersistence", "0.5");
        properties.setProperty("k", "1");
        properties.setProperty("B", join(bValues));
        properties.setProperty("lambda", join(lambdas));
        properties.setProperty("procurementSeed", Integer.toString(procurementSeed));
        properties.setProperty("demandSeed", Long.toString(demandSeed));
        properties.setProperty("beta", Integer.toString(beta));
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, "Manuscript lambda-sensitivity figure pilot");
        }
    }

    private static String join(double[] values) {
        StringBuilder joined = new StringBuilder();
        for (double value : values) {
            if (joined.length() > 0) joined.append(',');
            joined.append(value);
        }
        return joined.toString();
    }

    private static void writeResults(Path path,
                                     Result deterministic,
                                     double deterministicMax,
                                     List<Row> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("B,lambda,selectedCount,meanOosCost,sdOosCost,maxOosCost,"
                    + "q95OosCost,cvar95OosCost,meanImprovementPct,sdImprovementPct,"
                    + "maxImprovementPct,certifiedOptimal,relativeGap,optimizerTimeSec");
            writer.newLine();
            for (Row row : rows) {
                Result result = row.result;
                writer.write(String.format(Locale.US,
                        "%.17g,%.17g,%d,%.17g,%.17g,%.17g,%.17g,%.17g,"
                                + "%.17g,%.17g,%.17g,%s,%.17g,%.9f%n",
                        row.b, row.lambda, result.selectedCount,
                        result.meanOosCost, result.sdOosCost, row.maximum,
                        result.q95OosCost, result.cvar95OosCost,
                        improvement(deterministic.meanOosCost, result.meanOosCost),
                        improvement(deterministic.sdOosCost, result.sdOosCost),
                        improvement(deterministicMax, row.maximum),
                        result.certifiedOptimal, result.relativeGap,
                        result.optimizerTimeSeconds));
            }
        }
    }

    private static double improvement(double baseline, double value) {
        return 100.0 * (baseline - value) / baseline;
    }

    private record Row(double b, double lambda, double maximum, Result result) {
    }
}
