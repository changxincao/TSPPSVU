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

/** Completion markers are valid only after every required result artifact exists. */
final class TRBSVUCompletionMarker {
    private TRBSVUCompletionMarker() { }

    static void invalidate(Path marker) throws Exception {
        Files.deleteIfExists(marker);
    }

    static boolean matches(Path marker, String... requiredTokens) throws Exception {
        if (!nonempty(marker)) return false;
        String text = Files.readString(marker, StandardCharsets.UTF_8);
        for (String token : requiredTokens)
            if (!text.contains(token)) return false;
        return true;
    }

    static boolean queryArtifactsComplete(Path output, List<Integer> queryIndices,
                                          String... relativeArtifacts) throws Exception {
        for (int index : queryIndices) {
            Path query = output.resolve("queries").resolve(String.format("query_%03d", index));
            for (String relative : relativeArtifacts)
                if (!nonempty(query.resolve(relative))) return false;
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
}
