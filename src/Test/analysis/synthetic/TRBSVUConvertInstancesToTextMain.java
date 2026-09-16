package Test.analysis.synthetic;

import java.nio.file.Files;
import java.nio.file.Path;

/** Converts existing lossless binary cases to human-readable single-file TSV snapshots. */
public final class TRBSVUConvertInstancesToTextMain {
    private TRBSVUConvertInstancesToTextMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <caseRoot>");
        Path root = Path.of(args[0]);
        int converted = 0, verified = 0;
        try (var replications = Files.list(root)) {
            for (Path replication : replications.filter(Files::isDirectory).sorted().toList()) {
                Path directory = replication.resolve("instance");
                Path binary = directory.resolve("instance.bin");
                Path text = directory.resolve("instance.tsv");
                if (!Files.exists(binary)) continue;
                TRBSVUSyntheticCase original = TRBSVUSyntheticCaseIO.load(binary);
                if (!Files.exists(text)) {
                    TRBSVUSyntheticCaseIO.saveText(original, text);
                    converted++;
                }
                TRBSVUSyntheticCase restored = TRBSVUSyntheticCaseIO.loadText(text);
                Path check = Files.createTempFile(directory, "roundtrip_", ".bin");
                try {
                    Files.delete(check);
                    TRBSVUSyntheticCaseIO.save(restored, check);
                    if (Files.mismatch(binary, check) != -1)
                        throw new IllegalStateException("Text round trip changed case: " + replication);
                    verified++;
                } finally {
                    Files.deleteIfExists(check);
                }
            }
        }
        System.out.println("TRBSVU text conversion PASS converted=" + converted
                + " verified=" + verified);
    }
}
