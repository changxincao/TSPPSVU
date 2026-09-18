package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.CovariateVector;
import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ConditionalQuery;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextDistribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.MultiQueryReplication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Checks whether the single-sample cardinality mechanism propagates to
 * different D/SAA/CSAA decisions. This is a diagnostic, not a formal experiment.
 */
public final class TRBSVUPersistentRateMqcMethodProbe {
    private static final int DEFAULT_CARRIERS = 12;
    private static final int LANES = 20;
    private static final int HISTORY = 60;
    private static final int OOS = 500;
    private static final double DEFAULT_BANDWIDTH = 0.5;

    private TRBSVUPersistentRateMqcMethodProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 24) {
            throw new IllegalArgumentException(
                    "Usage: <output-directory> [replications] [queries] [mqc-scale]"
                            + " [volatility] [context-scale] [bandwidth] [spot-scale]"
                            + " [carriers] [oracle-samples] [context-structure]"
                            + " [common-loading-lower] [common-loading-upper]"
                            + " [fixed-design-index; 0 means paired designs]"
                            + " [context-distribution] [robustness; <=0 disables robust methods]"
                            + " [robust-methods: BOTH|RCSAA|CHI2] [history-size]"
                            + " [surge-probability; <=0 disables] [surge-multiplier]"
                            + " [top-high-demand-queries; 0 means all]"
                            + " [heterogeneous-surge: true|false]"
                            + " [regional-groups; <=1 disables] [global-variance-share]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        int replications = args.length >= 2 ? Integer.parseInt(args[1]) : 3;
        int queryCount = args.length >= 3 ? Integer.parseInt(args[2]) : 20;
        double mqcScale = args.length >= 4 ? Double.parseDouble(args[3]) : 1.50;
        Volatility volatility = args.length >= 5
                ? Volatility.valueOf(args[4].toUpperCase(Locale.ROOT)) : Volatility.MEDIUM;
        double contextScale = args.length >= 6 ? Double.parseDouble(args[5]) : 1.0;
        double bandwidth = args.length >= 7 ? Double.parseDouble(args[6]) : DEFAULT_BANDWIDTH;
        if (!(bandwidth > 0.0) || !Double.isFinite(bandwidth)) {
            throw new IllegalArgumentException("Bandwidth must be finite and positive.");
        }
        double spotScale = args.length >= 8 ? Double.parseDouble(args[7]) : 1.0;
        if (!(spotScale > 0.0) || !Double.isFinite(spotScale)) {
            throw new IllegalArgumentException("Spot scale must be finite and positive.");
        }
        int carriers = args.length >= 9 ? Integer.parseInt(args[8]) : DEFAULT_CARRIERS;
        if (carriers < 6) throw new IllegalArgumentException("At least six carriers are required.");
        int oracleSamples = args.length >= 10 ? Integer.parseInt(args[9]) : 0;
        if (oracleSamples < 0) throw new IllegalArgumentException("Oracle samples cannot be negative.");
        ContextStructure contextStructure = args.length >= 11
                ? ContextStructure.valueOf(args[10].trim().toUpperCase(Locale.ROOT))
                : ContextStructure.DENSE_INDEPENDENT_LEVELS;
        double commonLoadingLower = args.length >= 12 ? Double.parseDouble(args[11]) : 0.2;
        double commonLoadingUpper = args.length >= 13 ? Double.parseDouble(args[12]) : 0.6;
        int fixedDesignIndex = args.length >= 14 ? Integer.parseInt(args[13]) : 0;
        if (fixedDesignIndex < 0) {
            throw new IllegalArgumentException("Fixed design index cannot be negative.");
        }
        ContextDistribution contextDistribution = args.length >= 15
                ? ContextDistribution.valueOf(args[14].trim().toUpperCase(Locale.ROOT))
                : ContextDistribution.UNIFORM;
        double robustness = args.length >= 16 ? Double.parseDouble(args[15]) : 0.0;
        String robustMethods = args.length >= 17
                ? args[16].trim().toUpperCase(Locale.ROOT) : "BOTH";
        if (!List.of("BOTH", "RCSAA", "CHI2").contains(robustMethods)) {
            throw new IllegalArgumentException("robust-methods must be BOTH, RCSAA, or CHI2.");
        }
        int historySize = args.length >= 18 ? Integer.parseInt(args[17]) : HISTORY;
        if (historySize <= 0) throw new IllegalArgumentException("History size must be positive.");
        double surgeProbability = args.length >= 19 ? Double.parseDouble(args[18]) : 0.0;
        double surgeMultiplier = args.length >= 20 ? Double.parseDouble(args[19]) : 2.0;
        int topHighDemandQueries = args.length >= 21 ? Integer.parseInt(args[20]) : 0;
        boolean heterogeneousSurge = args.length >= 22 && Boolean.parseBoolean(args[21]);
        int regionalGroups = args.length >= 23 ? Integer.parseInt(args[22]) : 0;
        double globalVarianceShare = args.length >= 24 ? Double.parseDouble(args[23]) : 1.0;
        if (surgeProbability > 0.0 && regionalGroups > 1) {
            throw new IllegalArgumentException(
                    "Rare-surge and regional-factor diagnostics cannot be enabled together.");
        }
        if (topHighDemandQueries < 0 || topHighDemandQueries > queryCount) {
            throw new IllegalArgumentException(
                    "top-high-demand-queries must be between 0 and query-count.");
        }
        Files.createDirectories(output);

        Settings settings = new Settings(1, 600, 1e-8,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        List<String> rows = new ArrayList<>();
        rows.add("market\treplication\tquery\tmethod\tstatus\tcertified\tobjective\tgap"
                + "\tsolve_sec\tess\tselected_count\tselected\tnominal_total"
                + "\toos_mean\toos_sd\toos_q95\toos_cvar95\toos_max\tspot_share"
                + "\tmqc_penalty");

        SplittableRandom seeds = new SplittableRandom(20260917L);
        TRBSVUSyntheticCase.Seeds fixedDesign = fixedDesignIndex == 0
                ? null : designSeeds(fixedDesignIndex);
        for (int replication = 1; replication <= replications; replication++) {
            TRBSVUSyntheticCase.Seeds sampled = new TRBSVUSyntheticCase.Seeds(
                    seeds.nextLong(), seeds.nextLong(), seeds.nextLong(),
                    seeds.nextLong(), seeds.nextLong());
            TRBSVUSyntheticCase.Seeds paired = fixedDesign == null ? sampled
                    : new TRBSVUSyntheticCase.Seeds(
                            fixedDesign.demandParameters(), fixedDesign.procurement(),
                            sampled.contexts(), sampled.historicalNoise(), sampled.oosNoise());
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    LANES, historySize, paired.demandParameters(), contextScale,
                    contextStructure,
                    BaseStructure.THREE_LEVEL_WIDE,
                    commonLoadingLower, commonLoadingUpper);
            MultiQueryReplication demand;
            if (regionalGroups > 1) {
                demand = TRBSVUSyntheticDemandGenerator.generateMultiQueryWithRegionalFactors(
                        parameters, Distribution.LOGNORMAL, volatility, queryCount,
                        OOS + oracleSamples,
                        paired.contexts(), paired.historicalNoise(), paired.oosNoise(),
                        contextDistribution, regionalGroups, globalVarianceShare);
            } else if (surgeProbability <= 0.0) {
                demand = TRBSVUSyntheticDemandGenerator.generateMultiQuery(
                        parameters, Distribution.LOGNORMAL, volatility, queryCount,
                        OOS + oracleSamples,
                        paired.contexts(), paired.historicalNoise(), paired.oosNoise(),
                        contextDistribution);
            } else if (heterogeneousSurge) {
                demand = TRBSVUSyntheticDemandGenerator.generateMultiQueryWithHeterogeneousRareSurge(
                        parameters, volatility, queryCount, OOS + oracleSamples,
                        paired.contexts(), paired.historicalNoise(), paired.oosNoise(),
                        contextDistribution, surgeProbability, surgeMultiplier);
            } else {
                demand = TRBSVUSyntheticDemandGenerator.generateMultiQueryWithRareSurge(
                        parameters, volatility, queryCount, OOS + oracleSamples,
                        paired.contexts(), paired.historicalNoise(), paired.oosNoise(),
                        contextDistribution, surgeProbability, surgeMultiplier);
            }
            ProcurementParams current = TRBSVUProcurementGenerator.generate(
                    carriers, parameters.typicalDemand(), paired.procurement());
            ProcurementParams persistent =
                    TRBSVUSingleSampleCardinalityDiagnostic.persistentCarrierRates(current,
                            paired.procurement() ^ 0x5DEECE66DL, 0.7, 1.3);
            ProcurementParams candidate =
                    TRBSVUSingleSampleCardinalityDiagnostic.scaleMqc(persistent, mqcScale);
            candidate = scaleSpot(candidate, spotScale);

            if (oracleSamples == 0) {
                runMarket(rows, "CURRENT", replication, parameters, demand, current, bandwidth,
                        0, robustness, robustMethods, topHighDemandQueries, settings,
                        output.resolve("method_probe.tsv"));
            }
            String marketName = String.format(Locale.ROOT, "PERSISTENT_WIDE_MQC_%.2f", mqcScale);
            if (fixedDesignIndex > 0) marketName += "_FIXED_DESIGN_" + fixedDesignIndex;
            if (heterogeneousSurge) marketName += "_HETEROGENEOUS_SURGE";
            if (regionalGroups > 1) {
                marketName += String.format(Locale.ROOT, "_REGIONAL_K%d_TAU%.2f",
                        regionalGroups, globalVarianceShare);
            }
            runMarket(rows, marketName,
                    replication,
                    parameters, demand, candidate, bandwidth, oracleSamples, robustness,
                    robustMethods, topHighDemandQueries, settings,
                    output.resolve("method_probe.tsv"));
            Files.write(output.resolve("method_probe.tsv"), rows, StandardCharsets.UTF_8);
        }
    }

    private static TRBSVUSyntheticCase.Seeds designSeeds(int oneBasedIndex) {
        if (oneBasedIndex <= 0) throw new IllegalArgumentException("Design index must be positive.");
        SplittableRandom seeds = new SplittableRandom(20260917L);
        TRBSVUSyntheticCase.Seeds result = null;
        for (int index = 1; index <= oneBasedIndex; index++) {
            result = new TRBSVUSyntheticCase.Seeds(
                    seeds.nextLong(), seeds.nextLong(), seeds.nextLong(),
                    seeds.nextLong(), seeds.nextLong());
        }
        return result;
    }

    private static void runMarket(List<String> rows, String marketName, int replication,
                                  Parameters parameters, MultiQueryReplication demand,
                                  ProcurementParams market, double bandwidth,
                                  int oracleSamples,
                                  double robustness,
                                  String robustMethods,
                                  int topHighDemandQueries,
                                  Settings settings,
                                  Path checkpointFile) throws Exception {
        List<String> lanes = laneNames();
        ConditionalQuery first = demand.queries.get(0);
        Solution d = solve(market, lanes,
                TRBSVUScenarioWeights.arithmeticMean(demand.history), first,
                Method.NOMINAL, 0.0, settings);
        Solution saa = solve(market, lanes,
                TRBSVUScenarioWeights.equal(demand.history), first,
                Method.NOMINAL, 0.0, settings);

        List<Integer> queryIndices = selectedQueryIndices(parameters, demand,
                topHighDemandQueries);
        for (int query : queryIndices) {
            ConditionalQuery conditional = demand.queries.get(query);
            List<Sample> evaluation = conditional.oos.subList(oracleSamples,
                    conditional.oos.size());
            write(rows, marketName, replication, query, "D", d, 1.0,
                    parameters, conditional.context, evaluation, market);
            write(rows, marketName, replication, query, "SAA", saa, demand.history.size(),
                    parameters, conditional.context, evaluation, market);
            List<Sample> contextual = TRBSVUScenarioWeights.kernel(demand.history,
                    conditional.context, TRBSVUScenarioWeights.Kernel.EXPONENTIAL, bandwidth);
            Solution csaa = solve(market, lanes, contextual, conditional,
                    Method.NOMINAL, 0.0, settings);
            write(rows, marketName, replication, query, "CSAA", csaa,
                    TRBSVUExperiment1Runner.ess(contextual),
                    parameters, conditional.context, evaluation, market);
            Files.write(checkpointFile, rows, StandardCharsets.UTF_8);
            if (robustness > 0.0) {
                if (!"CHI2".equals(robustMethods)) {
                    try {
                        Solution rcsaa = solve(market, lanes, contextual, conditional,
                                Method.RCSAA, robustness, settings);
                        write(rows, marketName, replication, query, "RCSAA", rcsaa,
                                TRBSVUExperiment1Runner.ess(contextual),
                                parameters, conditional.context, evaluation, market);
                        Files.write(checkpointFile, rows, StandardCharsets.UTF_8);
                    } catch (IllegalStateException failure) {
                        System.err.printf(Locale.ROOT,
                                "SKIP_UNCERTIFIED method=RCSAA replication=%d query=%d reason=%s%n",
                                replication, query, failure.getMessage());
                    }
                }
                if (!"RCSAA".equals(robustMethods)) {
                    try {
                        Solution chiSquared = solve(market, lanes, contextual, conditional,
                                Method.CHI_SQUARED, robustness, settings);
                        write(rows, marketName, replication, query, "C-CHI2", chiSquared,
                                TRBSVUExperiment1Runner.ess(contextual),
                                parameters, conditional.context, evaluation, market);
                        Files.write(checkpointFile, rows, StandardCharsets.UTF_8);
                    } catch (IllegalStateException failure) {
                        System.err.printf(Locale.ROOT,
                                "SKIP_UNCERTIFIED method=C-CHI2 replication=%d query=%d reason=%s%n",
                                replication, query, failure.getMessage());
                    }
                }
            }
            if (oracleSamples > 0) {
                List<Sample> oracleTraining = TRBSVUScenarioWeights.equal(
                        conditional.oos.subList(0, oracleSamples));
                Solution oracle = solve(market, lanes, oracleTraining, conditional,
                        Method.NOMINAL, 0.0, settings);
                write(rows, marketName, replication, query, "ORACLE", oracle,
                        oracleSamples, parameters, conditional.context, evaluation, market);
            }
            Files.write(checkpointFile, rows, StandardCharsets.UTF_8);
        }
    }

    private static List<Integer> selectedQueryIndices(Parameters parameters,
                                                       MultiQueryReplication demand,
                                                       int topHighDemandQueries) {
        List<Integer> indices = new ArrayList<>();
        for (int query = 0; query < demand.queries.size(); query++) indices.add(query);
        if (topHighDemandQueries == 0) return indices;
        indices.sort((left, right) -> Double.compare(
                nominalTotal(parameters, demand.queries.get(right).context),
                nominalTotal(parameters, demand.queries.get(left).context)));
        return List.copyOf(indices.subList(0, topHighDemandQueries));
    }

    private static double nominalTotal(Parameters parameters, CovariateVector context) {
        double total = 0.0;
        for (double value : parameters.nominalDemand(context)) total += value;
        return total;
    }

    private static Solution solve(ProcurementParams market, List<String> lanes,
                                  List<Sample> samples, ConditionalQuery query,
                                  Method method, double robustness,
                                  Settings settings) throws Exception {
        Solution solution = TRBSVUSolveMethods.solve(market, lanes, samples,
                query.context, method, robustness, settings);
        if (!solution.certifiedOptimal) {
            throw new IllegalStateException("Uncertified method probe solve: "
                    + solution.solverStatus);
        }
        return solution;
    }

    private static void write(List<String> rows, String marketName, int replication,
                              int query, String method, Solution solution, double ess,
                              Parameters parameters, CovariateVector context,
                              List<Sample> evaluation,
                              ProcurementParams market) throws Exception {
        Oos oos = TRBSVUSolveMethods.evaluate(market, solution.y, evaluation);
        double nominalTotal = 0.0;
        for (double value : parameters.nominalDemand(context)) nominalTotal += value;
        rows.add(String.format(Locale.ROOT,
                "%s\t%d\t%d\t%s\t%s\t%s\t%.10f\t%.10g\t%.6f\t%.10f\t%d\t%s"
                        + "\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                marketName, replication, query, method, solution.solverStatus,
                solution.certifiedOptimal, solution.objValue, solution.relativeGap,
                solution.solveTimeSec, ess, selectedCount(solution.y), selected(solution.y),
                nominalTotal, oos.mean(), oos.standardDeviation(), oos.q95(), oos.cvar95(),
                oos.maximum(), oos.spotShare(), oos.meanPenalty()));
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

    private static List<String> laneNames() {
        List<String> result = new ArrayList<>(LANES);
        for (int j = 0; j < LANES; j++) result.add("L" + (j + 1));
        return result;
    }

    private static ProcurementParams scaleSpot(ProcurementParams source, double scale) {
        if (scale == 1.0) return source;
        double[] spot = source.e.clone();
        for (int j = 0; j < spot.length; j++) spot[j] *= scale;
        return new ProcurementParams(source.carriers, source.J, spot, source.p.clone(),
                source.h.clone(), clone(source.q), clone(source.r), clone(source.eligible),
                source.alpha, source.beta);
    }

    private static double[][] clone(double[][] source) {
        double[][] result = new double[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }

    private static boolean[][] clone(boolean[][] source) {
        boolean[][] result = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }
}
