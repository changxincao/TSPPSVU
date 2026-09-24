package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Sample;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Fixed 500-tree RF-CSAA adapter; external Python implements only leaf weights. */
public final class TRBSVUForestWeights implements TRBSVUExperiment1Runner.ForestWeights {
    private final String python;
    private final Path script;
    private final int trees;
    private final Map<String, double[]> cache = new HashMap<>();

    public TRBSVUForestWeights(String python, Path script) {
        this(python, script, 500);
    }

    /** Non-500 tree count is only for isolated implementation smoke tests. */
    public TRBSVUForestWeights(String python, Path script, int trees) {
        if (python == null || script == null || trees <= 0)
            throw new IllegalArgumentException("Invalid forest executable or tree count.");
        this.python = python;
        this.script = script.toAbsolutePath();
        this.trees = trees;
    }

    @Override
    public synchronized List<Sample> weights(List<Sample> training, CovariateVector query,
                                              long seed, int minSamplesLeaf) throws Exception {
        if (training.isEmpty()) throw new IllegalArgumentException("Empty RF training set.");
        if (minSamplesLeaf < 1) throw new IllegalArgumentException("RF min leaf must be positive.");
        String key = cacheKey(training, query, seed, minSamplesLeaf);
        double[] values = cache.get(key);
        if (values == null) {
            values = fit(training, query, seed, minSamplesLeaf);
            cache.put(key, values);
        }
        return TRBSVUScenarioWeights.copyWithWeights(training, values, false);
    }

    private String cacheKey(List<Sample> training, CovariateVector query, long seed,
                            int minSamplesLeaf) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        update(digest, seed);
        update(digest, trees);
        update(digest, minSamplesLeaf);
        update(digest, training.size());
        for (Sample sample : training) {
            update(digest, sample.theta.values());
            update(digest, sample.demand());
        }
        update(digest, query.values());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    private static void update(MessageDigest digest, double[] values) {
        update(digest, values.length);
        for (double value : values) update(digest, Double.doubleToLongBits(value));
    }

    private double[] fit(List<Sample> training, CovariateVector query, long seed,
                         int minSamplesLeaf) throws Exception {
        Path temporaryRoot = Path.of("tmp");
        Files.createDirectories(temporaryRoot);
        Path directory = Files.createTempDirectory(temporaryRoot, "trb_svu_rf_");
        Path input = directory.resolve("training.csv");
        Path output = directory.resolve("weights.csv");
        try {
            int lanes = training.get(0).demand().length;
            try (BufferedWriter writer = Files.newBufferedWriter(input, StandardCharsets.UTF_8)) {
                writer.write(training.size() + "," + query.dim() + "," + lanes);
                writer.newLine();
                for (Sample sample : training) {
                    writeRow(writer, sample.theta.values(), sample.demand());
                }
                writeRow(writer, query.values(), new double[0]);
            }
            Process process = new ProcessBuilder(python, script.toString(), input.toString(),
                    output.toString(), Long.toString(seed & 0xffff_ffffL), Integer.toString(trees),
                    Integer.toString(minSamplesLeaf))
                    .redirectErrorStream(true).start();
            boolean ended = process.waitFor(120, TimeUnit.SECONDS);
            if (!ended) {
                process.destroyForcibly();
                throw new IllegalStateException("RF fit exceeded 120 seconds.");
            }
            String diagnostics = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0)
                throw new IllegalStateException("RF fit failed: " + diagnostics);
            String[] fields = Files.readString(output, StandardCharsets.UTF_8).trim().split(",");
            if (fields.length != training.size()) throw new IllegalStateException("RF weight count mismatch.");
            double[] values = new double[fields.length];
            double sum = 0.0;
            for (int s = 0; s < fields.length; s++) {
                values[s] = Double.parseDouble(fields[s]);
                if (values[s] < 0.0 || !Double.isFinite(values[s]))
                    throw new IllegalStateException("Invalid RF weight.");
                sum += values[s];
            }
            if (Math.abs(sum - 1.0) > 1e-8) throw new IllegalStateException("RF weights do not sum to 1.");
            return values;
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(input);
            Files.deleteIfExists(directory);
        }
    }

    private static void writeRow(BufferedWriter writer, double[] context, double[] demand)
            throws java.io.IOException {
        boolean first = true;
        for (double value : context) {
            if (!first) writer.write(',');
            writer.write(Double.toString(value));
            first = false;
        }
        for (double value : demand) {
            if (!first) writer.write(',');
            writer.write(Double.toString(value));
            first = false;
        }
        writer.newLine();
    }
}
