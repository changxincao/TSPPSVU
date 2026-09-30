package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextDistribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.MultiQueryReplication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Freezes the paired Normal-Medium/High cases used by the moderate-common-shock
 * comparison. All queries are ordinary draws; no high-demand query is selected.
 */
public final class TRBSVUGenerateModerateCommonCasesMain {
    private static final int I = 15;
    private static final int J = 50;
    private static final int H = 75;
    private static final int OOS = 1000;
    private static final int QUERIES = 40;
    private static final double LOADING_LOWER = 0.3;
    private static final double LOADING_UPPER = 0.5;
    private static final double LOCAL_RATE_HALFWIDTH = 0.1;
    private static final double SELECTION_UPPER_FRACTION = 0.8;

    private TRBSVUGenerateModerateCommonCasesMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4)
            throw new IllegalArgumentException(
                    "Usage: <outputDir> <MEDIUM|HIGH> [batchSeed=20261020] [count=5]");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Volatility volatility = Volatility.valueOf(args[1].toUpperCase(Locale.ROOT));
        if (volatility != Volatility.MEDIUM && volatility != Volatility.HIGH)
            throw new IllegalArgumentException("Only MEDIUM and HIGH are supported.");
        long batchSeed = args.length >= 3 ? Long.parseLong(args[2]) : 20261020L;
        int count = args.length >= 4 ? Integer.parseInt(args[3]) : 5;
        if (count < 1) throw new IllegalArgumentException("Count must be positive.");
        Files.createDirectories(root);

        StringBuilder batch = new StringBuilder(
                "replication\tbatch_seed\tcase_seed\tvolatility\tquery_count\tselection_upper\n");
        for (int replication = 0; replication < count; replication++) {
            Path replicationRoot = root.resolve(String.format(Locale.ROOT,
                    "rep_%03d", replication));
            if (Files.exists(replicationRoot))
                throw new IllegalStateException("Refusing to overwrite: " + replicationRoot);
            long caseSeed = TRBSVUFormalProtocol.caseSeed(batchSeed, replication);
            generate(replicationRoot, volatility, batchSeed, caseSeed, replication);
            batch.append(replication).append('\t').append(batchSeed).append('\t')
                    .append(caseSeed).append('\t')
                    .append(volatility).append('\t').append(QUERIES).append('\t')
                    .append((int) Math.ceil(SELECTION_UPPER_FRACTION * I)).append('\n');
            System.out.println("Generated " + volatility + " rep_"
                    + String.format(Locale.ROOT, "%03d", replication));
        }
        Files.writeString(root.resolve("batch_manifest.tsv"), batch, StandardCharsets.UTF_8);
    }

    private static void generate(Path replicationRoot, Volatility volatility,
                                 long batchSeed, long caseSeed, int replication) throws Exception {
        generate(replicationRoot, volatility, batchSeed, caseSeed, replication,
                null, null, "randomized-six-digit-v1");
    }

    static void generate(Path replicationRoot, Volatility volatility,
                         long batchSeed, long caseSeed, int replication,
                         Double cvLower, Double cvUpper, String seedScheme) throws Exception {
        if (Files.exists(replicationRoot))
            throw new IllegalStateException("Refusing to overwrite: " + replicationRoot);
        SplittableRandom random = new SplittableRandom(caseSeed);
        TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                random.nextLong(), random.nextLong(), random.nextLong(),
                random.nextLong(), random.nextLong());
        Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                J, H, seeds.demandParameters(), 10.0,
                ContextStructure.DENSE_INDEPENDENT_UNIFORM_POSITIVE,
                BaseStructure.UNIFORM_10_100, LOADING_LOWER, LOADING_UPPER);
        double[] cv = cvLower == null ? parameters.volatilityParameters(volatility)
                : parameters.volatilityParameters(cvLower, cvUpper);
        String cvLabel = cvLower == null ? volatility.name()
                : String.format(Locale.ROOT, "CV_U_%.1f_%.1f", cvLower, cvUpper);
        MultiQueryReplication generated =
                TRBSVUSyntheticDemandGenerator.generateMultiQueryWithLinearTrend(
                        parameters, Distribution.NORMAL, cv, QUERIES, OOS,
                        seeds.contexts(), seeds.historicalNoise(), seeds.oosNoise(),
                        ContextDistribution.UNIFORM);
        ProcurementParams market = TRBSVUProcurementGenerator.generate(
                I, parameters.linearTrendTypicalDemand(), seeds.procurement(),
                LOCAL_RATE_HALFWIDTH);
        market = withSelectionUpperFraction(market, SELECTION_UPPER_FRACTION);
        List<String> lanes = laneNames();

        Path instanceDirectory = replicationRoot.resolve("instance");
        Path queryRoot = replicationRoot.resolve("queries");
        Files.createDirectories(instanceDirectory);
        Files.createDirectories(queryRoot);
        StringBuilder queryManifest = new StringBuilder(
                "query_index\tquery_type\tsource_candidate\tdemand_ratio\tinstance_file\n");
        double typicalTotal = sum(parameters.linearTrendTypicalDemand());
        for (int query = 0; query < QUERIES; query++) {
            var conditional = generated.queries.get(query);
            TRBSVUSyntheticCase instance = new TRBSVUSyntheticCase(market, lanes,
                    generated.history, conditional.context, conditional.oos, seeds);
            Path queryFile = queryRoot.resolve(String.format(Locale.ROOT,
                    "query_%03d.instance.tsv", query));
            TRBSVUSyntheticCaseIO.saveText(instance, queryFile);
            double demandRatio = sum(parameters.nominalDemand(conditional.context)) / typicalTotal;
            queryManifest.append(query).append("\tRANDOM\t").append(query).append('\t')
                    .append(demandRatio).append('\t').append(queryFile.getFileName()).append('\n');
            if (query == 0) {
                Path primary = instanceDirectory.resolve("instance.tsv");
                TRBSVUSyntheticCaseIO.saveText(instance, primary);
                TRBSVUResultWriter.writeInstance(instanceDirectory, instance);
            }
        }
        Files.writeString(queryRoot.resolve("queries.tsv"), queryManifest,
                StandardCharsets.UTF_8);
        TRBSVUResultWriter.writeDgpParameters(instanceDirectory, parameters,
                Distribution.NORMAL, cvLabel, cv, parameters.linearTrendTypicalDemand());
        Files.writeString(instanceDirectory.resolve("manifest.txt"),
                "protocolVersion=" + TRBSVUFormalProtocol.EXPERIMENT12_VERSION + "\n"
                        + "experiment=moderate-common-volatility\n"
                        + "distribution=NORMAL\nvolatility=" + cvLabel + "\n"
                        + (cvLower == null ? "" : "cvLower=" + cvLower + "\ncvUpper=" + cvUpper + "\n")
                        + "I=" + I + "\nJ=" + J + "\nH=" + H + "\nOOS=" + OOS + "\n"
                        + "queries=" + QUERIES + "\nqueryType=RANDOM\n"
                        + "base=U(10,100)\ncoefficient=U(0,10*base)\n"
                        + "trend=historical_t_over_H;query_1\n"
                        + "commonLoading=U(0.3,0.5)\ncoverage=0.5\n"
                        + "capacity=U(0.3,0.5)*typicalLaneDemand\n"
                        + "rate=laneRate*carrierFactorU(0.7,1.3)*pairFactorU(0.9,1.1)\n"
                        + "spotMarkup=U(2,3)\nmqcShare=U(0.15,0.35)\n"
                        + "selectionUpperFraction=0.8\nselectionUpperCount=" + market.beta + "\n"
                        + "validationTrainingPeriods="
                        + TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS + "\n"
                        + "validationOrigins=" + TRBSVUFormalProtocol.VALIDATION_ORIGINS + "\n"
                        + "seedScheme=" + seedScheme + "\n"
                        + "batchSeed=" + batchSeed + "\ncaseSeed=" + caseSeed
                        + "\nreplication=" + replication + "\n"
                        + "demandParameters=" + seeds.demandParameters() + "\n"
                        + "procurement=" + seeds.procurement() + "\ncontexts=" + seeds.contexts() + "\n"
                        + "historicalNoise=" + seeds.historicalNoise() + "\n"
                        + "oosNoise=" + seeds.oosNoise() + "\n",
                StandardCharsets.UTF_8);

        TRBSVUSyntheticCase restored = TRBSVUSyntheticCaseIO.loadText(
                instanceDirectory.resolve("instance.tsv"));
        if (restored.params.I != I || restored.params.J != J || restored.params.beta != 12
                || restored.history.size() != H || restored.oos.size() != OOS)
            throw new IllegalStateException("Frozen case round-trip mismatch: " + replicationRoot);
    }

    private static ProcurementParams withSelectionUpperFraction(
            ProcurementParams source, double fraction) {
        int upper = Math.max(source.alpha, (int) Math.ceil(fraction * source.I));
        return new ProcurementParams(source.carriers, source.J, source.e, source.p,
                source.h, source.q, source.r, source.eligible, source.alpha, upper);
    }

    private static List<String> laneNames() {
        List<String> result = new ArrayList<>(J);
        for (int j = 0; j < J; j++) result.add("L" + (j + 1));
        return result;
    }

    private static double sum(double[] values) {
        double result = 0.0;
        for (double value : values) result += value;
        return result;
    }
}
