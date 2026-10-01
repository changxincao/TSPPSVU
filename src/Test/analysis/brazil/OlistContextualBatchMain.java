package Test.analysis.brazil;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Prepare five frozen real-demand markets, or audit a completed market/method task. */
public final class OlistContextualBatchMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("prepare|check|audit batchRoot [market method]");
        Path root = Path.of(args[1]);
        if (args[0].equals("prepare")) {
            StringBuilder manifest = new StringBuilder("market\tmarket_seed\trf_seed\tinstance\tsha256\n");
            byte[] firstDemands = null;
            Set<String> hashes = new HashSet<>();
            for (int r = 0; r < 5; r++) {
                long seed = 20261020L + r;
                String name = String.format("market_%03d", r);
                Path file = root.resolve("inputs/" + name + "/instance.tsv");
                OlistContextualData generated = new OlistContextualData(OlistContextualData.DEFAULT_INPUT, seed);
                Path temp = Files.createTempFile("olist_snapshot_", ".tsv");
                try {
                    generated.saveSnapshot(temp);
                    if (Files.exists(file) && Files.mismatch(temp, file) != -1)
                        throw new IllegalStateException("Refuse to overwrite a different frozen input: " + file);
                    if (!Files.exists(file)) OlistContextualRunner.atomic(file, Files.readString(temp));
                    OlistContextualData loaded = OlistContextualData.loadSnapshot(file);
                    loaded.saveSnapshot(temp);
                    if (Files.mismatch(temp, file) != -1) throw new AssertionError("Snapshot round trip differs");
                    if (loaded.weekly.periods.size() != 104 || loaded.market.J != 23)
                        throw new AssertionError("Unexpected actual Olist dimensions");
                    byte[] demands = demandBytes(file);
                    if (firstDemands == null) firstDemands = demands;
                    else if (!Arrays.equals(firstDemands, demands)) throw new AssertionError("Markets have different demands");
                    String hash = sha(file);
                    if (!hashes.add(hash)) throw new AssertionError("Markets did not vary");
                    manifest.append(name).append('\t').append(seed).append("\t20261020\tinputs/")
                            .append(name).append("/instance.tsv\t").append(hash).append('\n');
                } finally { Files.deleteIfExists(temp); }
            }
            OlistContextualRunner.atomic(root.resolve("inputs/markets.tsv"), manifest.toString());
            System.out.println("PREPARED_5_MARKETS identical_104x23_demands snapshot_roundtrip=PASS no_solves");
        } else if (args[0].equals("check")) {
            List<String> rows = Files.readAllLines(root.resolve("inputs/markets.tsv"));
            if (rows.size() != 6) throw new IllegalStateException("Expected five markets");
            byte[] first = null;
            for (String row : rows.subList(1, rows.size())) {
                String[] f = row.split("\t");
                Path file = root.resolve(f[3]);
                var data = OlistContextualData.loadSnapshot(file);
                if (!sha(file).equals(f[4]) || data.marketSeed != Long.parseLong(f[1]))
                    throw new IllegalStateException("Input hash/seed mismatch: " + file);
                byte[] demands = demandBytes(file);
                if (first == null) first = demands;
                else if (!Arrays.equals(first, demands)) throw new IllegalStateException("Unpaired actual demands");
            }
            System.out.println("BATCH_INPUT_CHECK_PASS markets=5");
        } else if (args[0].equals("audit") && args.length == 4) {
            var data = OlistContextualData.loadSnapshot(root.resolve("inputs/" + args[2] + "/instance.tsv"));
            var method = OlistContextualRunner.Method.valueOf(args[3]);
            int complete = 0;
            StringBuilder audit = new StringBuilder("trial\tweek\tcomplete\n");
            for (int trial = 0; trial < 51; trial++) {
                boolean good;
                try {
                    good = OlistContextualRunner.taskComplete(root.resolve("results/" + args[2])
                            .resolve(String.format("trial_%03d/%s", trial, method)), 53 + trial, method, data);
                } catch (Exception badOutput) { good = false; }
                if (good) complete++;
                audit.append(trial).append('\t').append(53 + trial).append('\t').append(good).append('\n');
            }
            OlistContextualRunner.atomic(root.resolve("control/audit_" + args[2] + "_" + method + ".tsv"), audit.toString());
            System.out.println("AUDIT " + args[2] + " " + method + " complete=" + complete + "/51");
            if (complete != 51) System.exit(2);
        } else throw new IllegalArgumentException("Unknown command");
    }

    private static byte[] demandBytes(Path file) throws Exception {
        return String.join("\n", Files.readAllLines(file).stream().filter(s -> s.startsWith("DEMAND\t")).toList())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
