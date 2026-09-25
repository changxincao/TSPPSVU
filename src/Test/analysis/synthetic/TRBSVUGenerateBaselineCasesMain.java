package Test.analysis.synthetic;

import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

/** Generates and freezes the formal Experiment 1/2 baseline cases; never solves a model. */
public final class TRBSVUGenerateBaselineCasesMain {
    private TRBSVUGenerateBaselineCasesMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3)
            throw new IllegalArgumentException("Usage: <outputDir> [baseSeed=20260915] [count=10]");
        Path root = Path.of(args[0]);
        long baseSeed = args.length > 1 ? Long.parseLong(args[1]) : 20260915L;
        int count = args.length > 2 ? Integer.parseInt(args[2])
                : TRBSVUFormalProtocol.BASELINE_REPLICATIONS;
        if (count < 1) throw new IllegalArgumentException("Replication count must be positive.");
        Files.createDirectories(root);
        for (int index = 0; index < count; index++) {
            Path instanceDirectory = root.resolve(String.format("rep_%03d", index)).resolve("instance");
            if (Files.exists(instanceDirectory))
                throw new IllegalStateException("Refusing to overwrite existing case: " + instanceDirectory);
            Files.createDirectories(instanceDirectory);
            SplittableRandom random = new SplittableRandom(baseSeed + index);
            TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                    random.nextLong(), random.nextLong(), random.nextLong(),
                    random.nextLong(), random.nextLong());
            TRBSVUSyntheticCase.GeneratedQueries generated = TRBSVUSyntheticCase.generateFormalQueries(
                    TRBSVUFormalProtocol.CARRIERS, TRBSVUFormalProtocol.LANES,
                    TRBSVUFormalProtocol.HISTORY_PERIODS, TRBSVUFormalProtocol.OOS_DRAWS,
                    Distribution.NORMAL, Volatility.LOW, seeds);
            TRBSVUSyntheticCase.QueryCase primary = generated.queries().get(0);
            Path text = instanceDirectory.resolve("instance.tsv");
            TRBSVUSyntheticCaseIO.saveText(primary.instance(), text);
            TRBSVUResultWriter.writeInstance(instanceDirectory, primary.instance());
            TRBSVUResultWriter.writeDgpParameters(instanceDirectory, generated.demandParameters(),
                    Distribution.NORMAL, Volatility.LOW,
                    generated.demandParameters().linearTrendTypicalDemand());
            Path queryRoot = root.resolve(String.format("rep_%03d", index)).resolve("queries");
            Files.createDirectories(queryRoot);
            StringBuilder queryManifest = new StringBuilder(
                    "query_index\tquery_type\tsource_candidate\tdemand_ratio\tinstance_file\n");
            for (int q = 0; q < generated.queries().size(); q++) {
                TRBSVUSyntheticCase.QueryCase query = generated.queries().get(q);
                Path queryFile = queryRoot.resolve(String.format("query_%03d.instance.tsv", q));
                TRBSVUSyntheticCaseIO.saveText(query.instance(), queryFile);
                queryManifest.append(q).append('\t').append(query.queryType()).append('\t')
                        .append(query.sourceCandidate()).append('\t').append(query.demandRatio())
                        .append('\t').append(queryFile.getFileName()).append('\n');
            }
            Files.writeString(queryRoot.resolve("queries.tsv"), queryManifest,
                    StandardCharsets.UTF_8);
            Files.writeString(instanceDirectory.resolve("manifest.txt"),
                    "protocolVersion=" + TRBSVUFormalProtocol.EXPERIMENT12_VERSION
                            + "\nbaseline=Normal-Low\nI=" + TRBSVUFormalProtocol.CARRIERS
                            + "\nJ=" + TRBSVUFormalProtocol.LANES
                            + "\nH=" + TRBSVUFormalProtocol.HISTORY_PERIODS
                            + "\nOOS=" + TRBSVUFormalProtocol.OOS_DRAWS + "\n"
                            + "randomQueries=" + TRBSVUFormalProtocol.RANDOM_QUERIES + "\n"
                            + "highRQueries=" + TRBSVUFormalProtocol.HIGH_R_QUERIES + "\n"
                            + "base=U(10,100)\ncoefficient=U(0,10*base)\n"
                            + "trend=historical_t_over_H;query_1\n"
                            + "commonLoading=U(0.1,0.3)\ncoverage=0.5\n"
                            + "capacity=U(0.3,0.5)*typicalLaneDemand\n"
                            + "rate=laneRate*carrierFactorU(0.7,1.3)*pairFactorU(0.9,1.1)\n"
                            + "spotMarkup=U(2,3)\nmqcShare=U(0.15,0.35)\n"
                            + "validationTrainingPeriods="
                            + TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS + "\n"
                            + "validationOrigins="
                            + TRBSVUFormalProtocol.VALIDATION_ORIGINS + "\n"
                            + "baseSeed=" + baseSeed + "\nreplication=" + index + "\n"
                            + "demandParameters=" + seeds.demandParameters() + "\n"
                            + "procurement=" + seeds.procurement() + "\n"
                            + "contexts=" + seeds.contexts() + "\n"
                            + "historicalNoise=" + seeds.historicalNoise() + "\n"
                            + "oosNoise=" + seeds.oosNoise() + "\n",
                    StandardCharsets.UTF_8);
            // Immediate round-trip gate: the frozen text snapshot, not regenerated data, is the solve input.
            TRBSVUSyntheticCase restored = TRBSVUSyntheticCaseIO.loadText(text);
            if (restored.params.I != TRBSVUFormalProtocol.CARRIERS
                    || restored.params.J != TRBSVUFormalProtocol.LANES
                    || restored.history.size() != TRBSVUFormalProtocol.HISTORY_PERIODS
                    || restored.oos.size() != TRBSVUFormalProtocol.OOS_DRAWS)
                throw new IllegalStateException("Frozen-case round-trip dimension mismatch at rep " + index);
            System.out.println("Generated rep_" + String.format("%03d", index));
        }
    }
}
