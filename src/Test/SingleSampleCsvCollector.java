package Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Merge all trial-level single-sample CSV files under a root directory into one CSV.
 *
 * Input file pattern:
 *   *_single_sample_det.csv
 *
 * Usage:
 *   java Test.SingleSampleCsvCollector <rootDir> [outCsv]
 */
public class SingleSampleCsvCollector {

    public static void main(String[] args) throws Exception {
        Path rootDir = Paths.get(args.length > 0 ? args[0] : ".");
        Path outCsv = Paths.get(args.length > 1
                ? args[1]
                : rootDir.resolve("single_sample_det_merged.csv").toString());

        MergeStats st = merge(rootDir, outCsv);
        System.out.println(String.format(Locale.US,
                "[OK] merged files=%d, rows=%d -> %s",
                st.mergedFiles, st.mergedRows, outCsv.toAbsolutePath()));
    }

    public static MergeStats merge(Path rootDir, Path outCsv) throws IOException {
        if (rootDir == null || !Files.exists(rootDir) || !Files.isDirectory(rootDir)) {
            throw new IllegalArgumentException("rootDir not found or not a directory: " + rootDir);
        }

        List<Path> candidates;
        try (Stream<Path> s = Files.walk(rootDir)) {
            candidates = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("_single_sample_det.csv"))
                    .filter(p -> !p.toAbsolutePath().normalize().equals(outCsv.toAbsolutePath().normalize()))
                    .sorted(Comparator.comparing(p -> p.toAbsolutePath().toString()))
                    .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException("No '*_single_sample_det.csv' found under: " + rootDir);
        }

        Files.createDirectories(outCsv.toAbsolutePath().getParent());

        String expectedHeader = null;
        long mergedRows = 0L;
        int mergedFiles = 0;
        List<String> skipped = new ArrayList<>();

        try (BufferedWriter bw = Files.newBufferedWriter(outCsv, StandardCharsets.UTF_8)) {
            for (Path f : candidates) {
                try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    String header = br.readLine();
                    if (header == null || header.trim().isEmpty()) {
                        skipped.add("empty: " + f.toString());
                        continue;
                    }

                    if (expectedHeader == null) {
                        expectedHeader = header;
                        bw.write(expectedHeader + ",sourceDirAbs");
                        bw.newLine();
                    } else if (!expectedHeader.equals(header)) {
                        skipped.add("header mismatch: " + f.toString());
                        continue;
                    }

                    String line;
                    long localRows = 0L;
                    while ((line = br.readLine()) != null) {
                        if (line.trim().isEmpty()) {
                            continue;
                        }
                        String sourceDirAbs = f.toAbsolutePath().getParent().toString();
                        String sourceDirCsv = "\"" + sourceDirAbs.replace("\"", "\"\"") + "\"";
                        bw.write(line + "," + sourceDirCsv);
                        bw.newLine();
                        localRows++;
                    }

                    mergedRows += localRows;
                    mergedFiles++;
                }
            }
        }

        if (!skipped.isEmpty()) {
            System.out.println("[WARN] Some files were skipped:");
            for (String msg : skipped) {
                System.out.println("  - " + msg);
            }
        }

        return new MergeStats(mergedFiles, mergedRows);
    }

    public static class MergeStats {
        public final int mergedFiles;
        public final long mergedRows;

        public MergeStats(int mergedFiles, long mergedRows) {
            this.mergedFiles = mergedFiles;
            this.mergedRows = mergedRows;
        }
    }
}
