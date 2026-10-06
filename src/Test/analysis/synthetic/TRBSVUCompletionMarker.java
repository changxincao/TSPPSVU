package Test.analysis.synthetic;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.HexFormat;
import java.security.MessageDigest;

/** Completion markers are valid only after every required result artifact exists. */
final class TRBSVUCompletionMarker {
    private TRBSVUCompletionMarker() { }

    static final class ProtocolMismatchException extends IllegalStateException {
        ProtocolMismatchException(Path marker) {
            super("Completion protocol differs; preserve saved results and review before resuming: " + marker);
        }
    }

    static void requireMatchingProtocol(Path marker, String expected) throws Exception {
        if (nonempty(marker) && !expected.equals(value(Files.readAllLines(marker), "protocol")))
            throw new ProtocolMismatchException(marker);
    }

    static void invalidate(Path marker) throws Exception {
        Files.deleteIfExists(marker);
    }

    static boolean matches(Path marker, String... requiredTokens) throws Exception {
        if (!nonempty(marker)) return false;
        String text = Files.readString(marker, StandardCharsets.UTF_8);
        for (String token : requiredTokens)
            if (!text.contains(token)) return false;
        Path base = marker.toAbsolutePath().getParent().normalize();
        Path queries = base.resolve("queries");
        if (Files.isDirectory(queries)) {
            Path manifest = base.resolve("artifacts.sha256.tsv");
            String expected = value(Arrays.asList(text.split("\\R")), "artifactManifestSha256");
            if (expected == null || !nonempty(manifest) || !expected.equals(sha256(manifest))) return false;
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                String[] fields = line.split("\\t", -1);
                if (fields.length != 2) return false;
                Path file = base.resolve(fields[0]).normalize();
                if (!file.startsWith(base) || !nonempty(file)
                        || !fields[1].equals(sha256(file))) return false;
            }
        }
        return true;
    }

    static boolean queryArtifactsComplete(Path output, List<Integer> queryIndices,
                                          String... relativeArtifacts) throws Exception {
        for (int index : queryIndices) {
            Path query = output.resolve("queries").resolve(String.format("query_%03d", index));
            for (String relative : relativeArtifacts) {
                if (!nonempty(query.resolve(relative))) return false;
                if (relative.replace('\\', '/').matches("oos/(experiment2_)?draws\\.csv")) {
                    Path metadata = query.resolve("query_metadata.txt");
                    String completed = Files.isRegularFile(metadata)
                            ? value(Files.readAllLines(metadata), "completedMethods") : null;
                    // An explicitly empty, failed-method result may get an incomplete
                    // marker, never a complete marker (requireQueryMethods rejects it).
                    if (!(completed != null && completed.isBlank())
                            && !oosDrawsComplete(query.resolve(relative), completed == null ? null
                            : new HashSet<>(Arrays.asList(completed.split(";"))))) return false;
                }
            }
        }
        return true;
    }

    static void requireQueryArtifacts(Path output, List<Integer> queryIndices,
                                      String... relativeArtifacts) throws Exception {
        if (!queryArtifactsComplete(output, queryIndices, relativeArtifacts))
            throw new IllegalStateException("Completion marker withheld: required query artifacts "
                    + "are missing or empty under " + output.toAbsolutePath());
    }

    static boolean queryMethodsComplete(Path output, List<Integer> queryIndices,
                                        Set<String> requestedMethods) throws Exception {
        for (int index : queryIndices) {
            Path metadata = output.resolve("queries").resolve(String.format("query_%03d", index))
                    .resolve("query_metadata.txt");
            if (!nonempty(metadata)) return false;
            List<String> lines = Files.readAllLines(metadata, StandardCharsets.UTF_8);
            String completed = value(lines, "completedMethods");
            String missing = value(lines, "missingMethods");
            if (completed == null || missing == null || !missing.isBlank()) return false;
            Set<String> actual = new HashSet<>(Arrays.asList(completed.split(";")));
            if (!actual.containsAll(requestedMethods)) return false;
        }
        return true;
    }

    static void requireQueryMethods(Path output, List<Integer> queryIndices,
                                    Set<String> requestedMethods) throws Exception {
        if (!queryMethodsComplete(output, queryIndices, requestedMethods))
            throw new IllegalStateException("Completion marker withheld: requested methods are "
                    + "missing from one or more query outputs under " + output.toAbsolutePath());
    }

    private static String value(List<String> lines, String key) {
        String prefix = key + "=";
        return lines.stream().filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length())).findFirst().orElse(null);
    }

    static boolean nonempty(Path path) throws Exception {
        return Files.isRegularFile(path) && Files.size(path) > 0L;
    }

    static void writeAtomically(Path marker, String content) throws Exception {
        Path queries = marker.toAbsolutePath().getParent().resolve("queries");
        if (marker.getFileName().toString().equals("complete.txt") && Files.isDirectory(queries)) {
            Path manifest = marker.toAbsolutePath().getParent().resolve("artifacts.sha256.tsv");
            StringBuilder hashes = new StringBuilder();
            try (var paths = Files.walk(queries)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString();
                    String parent = file.getParent().getFileName().toString();
                    if (!(name.equals("query_metadata.txt") || (name.endsWith(".csv")
                            && Set.of("validation", "solve", "oos").contains(parent)))) continue;
                    hashes.append(marker.toAbsolutePath().getParent().relativize(file))
                            .append('\t').append(sha256(file)).append('\n');
                }
            }
            if (hashes.isEmpty()) throw new IllegalStateException("No completed query artifacts: " + marker);
            writeAtomically(manifest, hashes.toString());
            content += "\nartifactManifestSha256=" + sha256(manifest) + "\n";
        }
        Files.createDirectories(marker.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(marker.toAbsolutePath().getParent(),
                marker.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, marker, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = stream.read(buffer)) >= 0;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // The formal protocol fixes 1000 independent OOS draws per method/query.
    static boolean oosDrawsComplete(Path path) throws Exception {
        return oosDrawsComplete(path, null, TRBSVUFormalProtocol.OOS_DRAWS);
    }

    static boolean oosDrawsComplete(Path path, int expectedCount) throws Exception {
        return oosDrawsComplete(path, null, expectedCount);
    }

    private static boolean oosDrawsComplete(Path path, Set<String> expectedMethods) throws Exception {
        return oosDrawsComplete(path, expectedMethods, TRBSVUFormalProtocol.OOS_DRAWS);
    }

    private static boolean oosDrawsComplete(Path path, Set<String> expectedMethods, int expectedCount) throws Exception {
        if (expectedCount <= 0) return false;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return false;
            List<String> columns = Arrays.asList(header.split(",", -1));
            int method = columns.indexOf("method"), draw = columns.indexOf("draw_index");
            int cost = columns.indexOf("total_cost"), demand = columns.indexOf("total_demand");
            if (method < 0 || draw < 0 || cost < 0 || demand < 0) return false;
            Map<String, Set<Integer>> indices = new HashMap<>();
            for (String row; (row = reader.readLine()) != null;) {
                String[] fields = row.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
                if (fields.length != columns.size()) return false;
                int index = Integer.parseInt(fields[draw]);
                if (index < 0 || index >= expectedCount
                        || fields[method].isBlank() || !Double.isFinite(Double.parseDouble(fields[cost]))
                        || !Double.isFinite(Double.parseDouble(fields[demand]))
                        || !indices.computeIfAbsent(fields[method], key -> new HashSet<>()).add(index)) return false;
            }
            return !indices.isEmpty() && (expectedMethods == null || expectedMethods.equals(indices.keySet()))
                    && indices.values().stream()
                    .allMatch(set -> set.size() == expectedCount);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
