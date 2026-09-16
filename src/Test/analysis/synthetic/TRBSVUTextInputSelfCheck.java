package Test.analysis.synthetic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Checks that damaged human-readable cases are rejected rather than silently changed. */
public final class TRBSVUTextInputSelfCheck {
    private TRBSVUTextInputSelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <instance.tsv>");
        List<String> original = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        Path directory = Files.createTempDirectory(Path.of("tmp"), "trb_svu_text_reject_");
        try {
            List<String> missingPair = new ArrayList<>(original);
            missingPair.remove(firstRecord(missingPair, "PAIR\t"));
            expectRejected(directory.resolve("missing_pair.tsv"), missingPair);

            List<String> badBoolean = new ArrayList<>(original);
            int pair = firstRecord(badBoolean, "PAIR\t");
            badBoolean.set(pair, badBoolean.get(pair).replaceFirst("\t(true|false)\t", "\tmaybe\t"));
            expectRejected(directory.resolve("bad_boolean.tsv"), badBoolean);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
        System.out.println("TRBSVUTextInputSelfCheck PASS");
    }

    private static int firstRecord(List<String> lines, String prefix) {
        for (int k = 0; k < lines.size(); k++) if (lines.get(k).startsWith(prefix)) return k;
        throw new AssertionError("Missing test record " + prefix);
    }

    private static void expectRejected(Path file, List<String> lines) throws Exception {
        Files.write(file, lines, StandardCharsets.UTF_8);
        try {
            TRBSVUSyntheticCaseIO.loadText(file);
            throw new AssertionError("Damaged input was accepted: " + file);
        } catch (IOException expected) {
            // Expected rejection.
        }
    }
}
