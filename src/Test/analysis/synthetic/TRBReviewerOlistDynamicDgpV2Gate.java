package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.calculateHelper.KernelType;
import Test.analysis.synthetic.TRBReviewerOlistDynamicDgpV2.Calibration;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** D/SAA/CSAA gate for the frozen Olist-calibrated dynamic DGP v2. */
public final class TRBReviewerOlistDynamicDgpV2Gate {
    private TRBReviewerOlistDynamicDgpV2Gate() {
    }

    /**
     * Usage: {@code <weekly-csv> <output-root> <J> <I> <S> <paths>
     * [oos-draws] [coverage] [procurement-seed] [lognormal|uniform] [CV]
     * [C_h] [demand-seed-base] [lane-share-persistence] [lambda]
     * [comma-separated-methods] [log-trend-per-period]
     * [seasonal-amplitude] [exponential|gaussian] [knn-neighbors] [min|max]}.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 6 || args.length > 21) {
            throw new IllegalArgumentException(
                    "Usage: <weekly-csv> <output-root> <J> <I> <S> <paths> "
                            + "[oos-draws] [coverage] [procurement-seed] "
                            + "[lognormal|uniform] [CV] [C_h] [demand-seed-base] "
                            + "[lane-share-persistence] [lambda] [comma-separated-methods] "
                            + "[log-trend-per-period] [seasonal-amplitude] [exponential|gaussian] "
                            + "[knn-neighbors] [min|max]");
        }
        Path weekly = Path.of(args[0]).toAbsolutePath().normalize();
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        int J = Integer.parseInt(args[2]);
        int I = Integer.parseInt(args[3]);
        int S = Integer.parseInt(args[4]);
        int paths = Integer.parseInt(args[5]);
        int oosDraws = args.length > 6 ? Integer.parseInt(args[6]) : 200;
        double coverage = args.length > 7 ? Double.parseDouble(args[7]) : 0.50;
        int procurementSeed = args.length > 8 ? Integer.parseInt(args[8]) : 0;
        InnovationDistribution distribution = args.length > 9
                ? InnovationDistribution.valueOf(args[9].trim().toUpperCase(Locale.ROOT))
                : InnovationDistribution.LOGNORMAL;
        double cv = args.length > 10 ? Double.parseDouble(args[10]) : 0.345;
        double cH = args.length > 11 ? Double.parseDouble(args[11]) : 1.0;
        long demandSeedBase = args.length > 12 ? Long.parseLong(args[12]) : 10_000L;
        double sharePersistence = args.length > 13
                ? Double.parseDouble(args[13]) : TRBReviewerOlistDynamicDgpV2.RHO;
        double lambda = args.length > 14 ? Double.parseDouble(args[14]) : 1.0;
        List<Method> methods = args.length > 15
                ? parseMethods(args[15])
                : List.of(Method.D, Method.SAA, Method.CSAA);
        double logTrendPerPeriod = args.length > 16 ? Double.parseDouble(args[16]) : 0.0;
        double seasonalAmplitude = args.length > 17 ? Double.parseDouble(args[17]) : 0.0;
        KernelType kernel = args.length > 18
                ? KernelType.valueOf(args[18].trim().toUpperCase(Locale.ROOT))
                : KernelType.EXPONENTIAL;
        int knnNeighbors = args.length > 19 ? Integer.parseInt(args[19]) : 0;
        String hRule = args.length > 20
                ? args[20].trim().toLowerCase(Locale.ROOT) : "min";
        if (J <= 0 || I <= 0 || S <= 0 || paths <= 0 || oosDraws <= 0
                || coverage <= 0.0 || coverage > 1.0 || !(cv > 0.0)
                || !(cH > 0.0) || !Double.isFinite(cH)
                || !(lambda > 0.0) || !Double.isFinite(lambda)
                || sharePersistence < 0.0 || sharePersistence >= 1.0
                || !Double.isFinite(sharePersistence)
                || !Double.isFinite(logTrendPerPeriod)
                || seasonalAmplitude < 0.0 || seasonalAmplitude >= 1.0
                || !Double.isFinite(seasonalAmplitude)
                || (methods.contains(Method.KNN_SAA)
                    && (knnNeighbors <= 0 || knnNeighbors > S))
                || !(hRule.equals("min") || hRule.equals("max"))) {
            throw new IllegalArgumentException("Invalid Olist-v2 gate settings.");
        }
        requireEmpty(root);
        Files.createDirectories(root);

        Calibration calibration = TRBReviewerOlistDynamicDgpV2.calibrate(weekly, J);
        Config procurementConfig = new Config();
        procurementConfig.seed = procurementSeed;
        InstanceGenerator.GenConfig marketConfig = new InstanceGenerator.GenConfig();
        marketConfig.betaRatio = 0.70;
        ProcurementParams fullMarket = InstanceGenerator.generate(
                I, calibration.baselineDemand(), marketConfig, procurementConfig);
        fullMarket = TRBReviewerR7CoverageMqcGridExperiment.withMarketSizeScale(
                fullMarket, 10.0 / I);
        ProcurementParams params = TRBReviewerR7CoverageMqcGridExperiment.withNestedCoverage(
                fullMarket, calibration.baselineDemand(), coverage, 1.00,
                hRule, true);
        writeProperties(root.resolve("experiment.properties"), weekly,
                calibration, J, I, S, paths, oosDraws, coverage,
                procurementSeed, distribution, cv, cH, demandSeedBase,
                sharePersistence, lambda, methods, logTrendPerPeriod,
                seasonalAmplitude, kernel, knnNeighbors, hRule, params);

        List<Row> rows = new ArrayList<>();
        for (int pathIndex = 0; pathIndex < paths; pathIndex++) {
            long demandSeed = demandSeedBase + pathIndex;
            ReplicationData demand = TRBReviewerOlistDynamicDgpV2.generate(
                    calibration, S, 200, oosDraws, demandSeed,
                    distribution, cv, sharePersistence,
                    logTrendPerPeriod, seasonalAmplitude);
            for (Method method : methods) {
                Config solveConfig =
                        TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                                method, 3, cH, lambda, 1, 600,
                                method == Method.KNN_SAA ? knnNeighbors : 0);
                solveConfig.kernelType = kernel;
                Path output = root.resolve(String.format(Locale.US,
                        "path_%03d/%s", pathIndex, method));
                TRBReviewerR3M3SyntheticMainSolve.Result result =
                        TRBReviewerR3M3SyntheticMainSolve.run(
                                method.name(), demand, params, solveConfig, output);
                rows.add(new Row(pathIndex, demandSeed,
                        method == Method.KNN_SAA ? knnNeighbors : 0, result));
            }
        }
        writeResults(root.resolve("query_results.csv"), rows);
    }

    private static void requireEmpty(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output root must be empty: " + root);
            }
        }
    }

    private static void writeProperties(Path path,
                                        Path weekly,
                                        Calibration calibration,
                                        int J,
                                        int I,
                                        int S,
                                        int paths,
                                        int oosDraws,
                                        double coverage,
                                        int procurementSeed,
                                        InnovationDistribution distribution,
                                        double cv,
                                        double cH,
                                        long demandSeedBase,
                                        double sharePersistence,
                                        double lambda,
                                        List<Method> methods,
                                        double logTrendPerPeriod,
                                        double seasonalAmplitude,
                                        KernelType kernel,
                                        int knnNeighbors,
                                        String hRule,
                                        ProcurementParams params) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("dgpVersion", "OLIST_DYNAMIC_V2_20260808");
        properties.setProperty("weeklyInput", weekly.toString());
        properties.setProperty("sourceLanes", Integer.toString(calibration.sourceLaneCount()));
        properties.setProperty("stableWeeks", Integer.toString(calibration.stableWeeks()));
        properties.setProperty("J", Integer.toString(J));
        properties.setProperty("I", Integer.toString(I));
        properties.setProperty("S", Integer.toString(S));
        properties.setProperty("paths", Integer.toString(paths));
        properties.setProperty("oosDraws", Integer.toString(oosDraws));
        properties.setProperty("distribution", distribution.name());
        properties.setProperty("innovationCv", Double.toString(cv));
        properties.setProperty("C_h", Double.toString(cH));
        properties.setProperty("lambda", Double.toString(lambda));
        properties.setProperty("methods", methods.stream()
                .map(Method::name).reduce((left, right) -> left + "," + right).orElseThrow());
        properties.setProperty("logTrendPerPeriod", Double.toString(logTrendPerPeriod));
        properties.setProperty("seasonalAmplitude", Double.toString(seasonalAmplitude));
        properties.setProperty("kernelType", kernel.name());
        properties.setProperty("knnNeighbors", Integer.toString(knnNeighbors));
        properties.setProperty("demandSeedBase", Long.toString(demandSeedBase));
        properties.setProperty("phi", Double.toString(TRBReviewerOlistDynamicDgpV2.PHI));
        properties.setProperty("rhoOlistCalibration",
                Double.toString(TRBReviewerOlistDynamicDgpV2.RHO));
        properties.setProperty("laneSharePersistence",
                Double.toString(sharePersistence));
        properties.setProperty("activeCommonFactor",
                Double.toString(TRBReviewerOlistDynamicDgpV2.ACTIVE_COMMON_FACTOR));
        properties.setProperty("innovationCommonFactor",
                Double.toString(TRBReviewerOlistDynamicDgpV2.INNOVATION_COMMON_FACTOR));
        properties.setProperty("coverage", Double.toString(coverage));
        properties.setProperty("capacityNormalized", "true");
        properties.setProperty("mqcScale", "1.0");
        properties.setProperty("hRule", hRule + " eligible r_ij");
        properties.setProperty("procurementSeed", Integer.toString(procurementSeed));
        properties.setProperty("beta", Integer.toString(params.beta));
        properties.setProperty("baselineTotal",
                Double.toString(Arrays.stream(calibration.baselineDemand()).sum()));
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(out, "Olist-calibrated dynamic DGP v2 gate");
        }
    }

    private static List<Method> parseMethods(String value) {
        List<Method> methods = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(token -> Method.valueOf(token.toUpperCase(Locale.ROOT)))
                .distinct()
                .toList();
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("At least one method is required.");
        }
        return methods;
    }

    private static void writeResults(Path path, List<Row> rows) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("pathIndex,demandSeed,method,knnNeighbors,selectedCount,yBinary,meanOosCost,"
                    + "sdOosCost,q95OosCost,cvar95OosCost,meanTransportCost,"
                    + "meanSpotCost,meanPenaltyCost,certifiedOptimal,relativeGap,"
                    + "optimizerTimeSec");
            out.newLine();
            for (Row row : rows) {
                var result = row.result;
                out.write(String.format(Locale.US,
                        "%d,%d,%s,%d,%d,%s,%.17g,%.17g,%.17g,%.17g,%.17g,"
                                + "%.17g,%.17g,%s,%.17g,%.9f%n",
                        row.pathIndex, row.demandSeed, result.methodLabel, row.knnNeighbors,
                        result.selectedCount, csv(result.yBinary), result.meanOosCost,
                        result.sdOosCost, result.q95OosCost, result.cvar95OosCost,
                        result.meanTransportCost, result.meanSpotCost,
                        result.meanPenaltyCost, result.certifiedOptimal,
                        result.relativeGap, result.optimizerTimeSeconds));
            }
        }
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private record Row(int pathIndex, long demandSeed, int knnNeighbors,
                       TRBReviewerR3M3SyntheticMainSolve.Result result) {
    }
}
