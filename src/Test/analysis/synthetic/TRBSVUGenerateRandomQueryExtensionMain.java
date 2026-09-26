package Test.analysis.synthetic;

import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Freezes candidates 20--39 from the formal ordinary-query stream. */
public final class TRBSVUGenerateRandomQueryExtensionMain {
    private static final int FIRST = 20;
    private static final int LAST_EXCLUSIVE = 40;

    private TRBSVUGenerateRandomQueryExtensionMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("Usage: <formal-input-root> <output-root> [replications=10]");
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        int replications = args.length == 3 ? Integer.parseInt(args[2])
                : TRBSVUFormalProtocol.BASELINE_REPLICATIONS;
        if (replications < 1) throw new IllegalArgumentException("Replication count must be positive.");
        Files.createDirectories(output);
        for (int replication = 0; replication < replications; replication++) {
            String name = String.format(Locale.ROOT, "rep_%03d", replication);
            Path original = input.resolve(name);
            Path target = output.resolve(name).resolve("queries");
            if (Files.exists(target))
                throw new IllegalStateException("Refusing to overwrite random-query extension: " + target);
            TRBSVUSyntheticCase reference = TRBSVUSyntheticCaseIO.loadText(
                    original.resolve("instance").resolve("instance.tsv"));
            TRBSVUSyntheticCase.GeneratedQueries regenerated =
                    TRBSVUSyntheticCase.regenerateFormalRandomCandidates(
                            TRBSVUFormalProtocol.CARRIERS, TRBSVUFormalProtocol.LANES,
                            TRBSVUFormalProtocol.HISTORY_PERIODS, TRBSVUFormalProtocol.OOS_DRAWS,
                            Distribution.NORMAL, Volatility.LOW, reference.seeds, LAST_EXCLUSIVE);
            verifyFrozenPrefix(original, regenerated);
            Files.createDirectories(target);
            StringBuilder manifest = new StringBuilder(
                    "query_index\tquery_type\tsource_candidate\tdemand_ratio\tinstance_file\n");
            for (int q = FIRST; q < LAST_EXCLUSIVE; q++) {
                TRBSVUSyntheticCase.QueryCase query = regenerated.queries().get(q);
                Path file = target.resolve(String.format(Locale.ROOT,
                        "query_%03d.instance.tsv", q));
                TRBSVUSyntheticCaseIO.saveText(query.instance(), file);
                manifest.append(q).append("\tRANDOM\t").append(query.sourceCandidate())
                        .append('\t').append(query.demandRatio()).append('\t')
                        .append(file.getFileName()).append('\n');
            }
            Files.writeString(target.resolve("queries.tsv"), manifest, StandardCharsets.UTF_8);
            Files.writeString(output.resolve(name).resolve("manifest.txt"),
                    "protocol=TRBSVU_RANDOM_QUERY_EXTENSION_V1\n"
                            + "source=" + original.toAbsolutePath() + "\n"
                            + "candidateRange=20-39\nqueryType=RANDOM\n"
                            + "prefixVerifiedAgainstFrozenQueries=0-19\n",
                    StandardCharsets.UTF_8);
            System.out.println("Generated and prefix-verified " + name);
        }
    }

    private static void verifyFrozenPrefix(Path original,
                                           TRBSVUSyntheticCase.GeneratedQueries regenerated)
            throws Exception {
        Path verification = Files.createTempDirectory("trbsvu-random-prefix-");
        try {
            for (int q = 0; q < FIRST; q++) {
                Path recreated = verification.resolve(String.format(Locale.ROOT,
                        "query_%03d.instance.tsv", q));
                TRBSVUSyntheticCaseIO.saveText(regenerated.queries().get(q).instance(), recreated);
                Path frozen = original.resolve("queries").resolve(String.format(Locale.ROOT,
                        "query_%03d.instance.tsv", q));
                boolean differs = Files.mismatch(recreated, frozen) != -1L;
                Files.delete(recreated);
                if (differs)
                    throw new IllegalStateException("Regenerated ordinary query differs from frozen input: "
                            + frozen);
            }
        } finally {
            Files.deleteIfExists(verification);
        }
    }
}
