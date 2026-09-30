package Test.analysis.synthetic;

import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit sequential seeds for the small paired CV pilot, not the formal random-seed batch. */
public final class TRBSVUGeneratePairedCvCasesMain {
    private TRBSVUGeneratePairedCvCasesMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 5)
            throw new IllegalArgumentException("Usage: <outputDir> <cvLower> <cvUpper> [firstSeed=20261020] [count=5]");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        double lower = Double.parseDouble(args[1]), upper = Double.parseDouble(args[2]);
        long firstSeed = args.length > 3 ? Long.parseLong(args[3]) : 20261020L;
        int count = args.length > 4 ? Integer.parseInt(args[4]) : 5;
        if (!Double.isFinite(lower) || !Double.isFinite(upper) || lower < 0 || upper < lower || count < 1)
            throw new IllegalArgumentException("Invalid CV range or count.");
        // Check every target before generating any case, so a mistaken resume cannot partially overwrite.
        for (int rep = 0; rep < count; rep++)
            if (Files.exists(root.resolve(String.format("rep_%03d", rep))))
                throw new IllegalStateException("Case already exists under " + root);
        Files.createDirectories(root);
        StringBuilder manifest = new StringBuilder("replication\tcase_seed\tcv_lower\tcv_upper\tqueries\n");
        for (int rep = 0; rep < count; rep++) {
            long seed = Math.addExact(firstSeed, rep);
            TRBSVUGenerateModerateCommonCasesMain.generate(root.resolve(String.format("rep_%03d", rep)),
                    Volatility.MEDIUM, firstSeed, seed, rep, lower, upper, "explicit-sequential-pilot-v1");
            manifest.append(rep).append('\t').append(seed).append('\t').append(lower)
                    .append('\t').append(upper).append("\t40\n");
            System.out.println("Generated caseSeed=" + seed + " CV=[" + lower + "," + upper + "]");
        }
        Files.writeString(root.resolve("batch_manifest.tsv"), manifest, StandardCharsets.UTF_8);
    }
}
