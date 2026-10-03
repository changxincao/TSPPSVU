package Test.analysis.synthetic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

/** Paired diagnostic: fixed markets/contexts; new independent history and OOS noise. */
public final class TRBSVUResampleHighDemandMain {
    private TRBSVUResampleHighDemandMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3)
            throw new IllegalArgumentException("Usage: <originalInputRoot> <newInputRoot> <noiseSeedOffset>");
        Path original = Path.of(args[0]).toAbsolutePath().normalize();
        Path target = Path.of(args[1]).toAbsolutePath().normalize();
        long offset = Long.parseLong(args[2]);
        if (offset != 1 && offset != 2)
            throw new IllegalArgumentException("This diagnostic freezes offsets 1 and 2 before solving.");
        if (Files.exists(target)) throw new IllegalStateException("Refusing overwrite: " + target);
        // Read all baseline inputs before producing any new case.
        TRBSVUSyntheticCase[] reference = new TRBSVUSyntheticCase[5];
        Properties[] metadata = new Properties[5];
        for (int rep = 1; rep <= 5; rep++) {
            Path source = original.resolve(String.format("rep_%03d", rep));
            metadata[rep - 1] = new Properties();
            try (var reader = Files.newBufferedReader(source.resolve("instance/manifest.txt"), StandardCharsets.UTF_8)) {
                metadata[rep - 1].load(reader);
            }
            Properties m = metadata[rep - 1];
            require("NORMAL".equals(m.getProperty("distribution"))
                    && Double.parseDouble(m.getProperty("cvLower")) == 0.5
                    && Double.parseDouble(m.getProperty("cvUpper")) == 0.7,
                    "Expected original Normal CV [0.5,0.7]: " + source);
            reference[rep - 1] = TRBSVUSyntheticCaseIO.loadText(source.resolve("instance/instance.tsv"));
        }
        Files.createDirectories(target);
        StringBuilder audit = new StringBuilder("replication\tcase_seed\tnoise_offset\toriginal_history_seed\thistory_seed\toriginal_oos_seed\toos_seed\toriginal_query000_sha256\tnew_query000_sha256\tmarket_context_audit\n");
        for (int rep = 1; rep <= 5; rep++) {
            String name = String.format("rep_%03d", rep);
            Path source = original.resolve(name), output = target.resolve(name);
            TRBSVUSyntheticCase before = reference[rep - 1];
            var s = before.seeds;
            var changed = new TRBSVUSyntheticCase.Seeds(s.demandParameters(), s.procurement(), s.contexts(),
                    Math.addExact(s.historicalNoise(), offset), Math.addExact(s.oosNoise(), offset));
            Properties m = metadata[rep - 1];
            long caseSeed = Long.parseLong(m.getProperty("caseSeed"));
            TRBSVUGenerateModerateCommonCasesMain.generate(output, Volatility.MEDIUM,
                    Long.parseLong(m.getProperty("batchSeed")), caseSeed, rep,
                    0.5, 0.7, "paired-demand-noise-offset-v1", changed);
            for (String file : new String[]{"carriers.csv", "carrier_lane.csv", "lanes.csv", "test_context.csv", "dgp_parameters.csv"})
                require(Files.mismatch(source.resolve("instance/" + file), output.resolve("instance/" + file)) == -1,
                        "Fixed input changed: " + name + "/" + file);
            require(Files.mismatch(source.resolve("queries/queries.tsv"), output.resolve("queries/queries.tsv")) == -1,
                    "Query manifest changed: " + name);
            for (int q = 0; q < 40; q++) {
                String file = String.format("queries/query_%03d.instance.tsv", q);
                var a = TRBSVUSyntheticCaseIO.loadText(source.resolve(file));
                var b = TRBSVUSyntheticCaseIO.loadText(output.resolve(file));
                require(Arrays.equals(a.testContext.values(), b.testContext.values()), "Query context changed");
                require(b.history.size() == 75 && b.oos.size() == 1000 && b.params.beta == 12, "Dimensions changed");
                for (int t = 0; t < 75; t++)
                    require(Arrays.equals(a.history.get(t).theta.values(), b.history.get(t).theta.values()),
                            "Historical context changed");
                require(!Arrays.equals(a.history.get(0).demand(), b.history.get(0).demand()), "History not resampled");
                require(!Arrays.equals(a.oos.get(0).demand(), b.oos.get(0).demand()), "OOS not resampled");
                require(b.seeds.equals(changed), "Saved noise seeds mismatch");
            }
            String baselineHash = hash(source.resolve("instance/instance.tsv"));
            String newHash = hash(output.resolve("instance/instance.tsv"));
            Files.writeString(output.resolve("instance/resampling_provenance.txt"),
                    "purpose=PAIRED_DEMAND_SAMPLING_DIAGNOSTIC\noriginalInput=" + source
                    + "\noriginalQuery000Sha256=" + baselineHash + "\nnoiseSeedOffset=" + offset
                    + "\noriginalHistoricalNoise=" + s.historicalNoise() + "\noriginalOosNoise=" + s.oosNoise()
                    + "\nfixed=market;dgp_coefficients;cv;common_loading;history_contexts;query_contexts\n"
                    + "changed=historical_demand_noise;oos_demand_noise\n", StandardCharsets.UTF_8);
            audit.append(rep).append('\t').append(caseSeed).append('\t').append(offset).append('\t')
                    .append(s.historicalNoise()).append('\t').append(changed.historicalNoise()).append('\t')
                    .append(s.oosNoise()).append('\t').append(changed.oosNoise()).append('\t')
                    .append(baselineHash).append('\t').append(newHash).append("\tPASS\n");
            System.out.println("PASS " + name + " offset=" + offset + " all 40 queries paired; only demand noise changed");
        }
        Files.writeString(target.resolve("resampling_audit.tsv"), audit, StandardCharsets.UTF_8);
        Files.writeString(target.resolve("generation_complete.txt"), "pairedMarketCount=5\nqueriesPerMarket=40\nnoiseSeedOffset=" + offset + "\n", StandardCharsets.UTF_8);
    }

    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
