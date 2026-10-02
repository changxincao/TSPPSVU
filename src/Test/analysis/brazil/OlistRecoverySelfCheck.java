package Test.analysis.brazil;

import java.nio.file.Files;
import java.nio.file.Path;

/** Solver-free regression for full-grid completion and fixed RF selection. */
public final class OlistRecoverySelfCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        StringBuilder table = new StringBuilder("lag\tparameter\tcompleted_origins\tvalid\tmean_cost\tsample_sd\n");
        for (int lag = 1; lag <= 3; lag++) for (int leaf : new int[]{1, 2, 5, 10})
            table.append(lag).append('\t').append(leaf).append("\t15\ttrue\t")
                    .append(100 + lag * 10 + leaf).append("\t1.0\n");
        Path file = root.resolve("candidates.tsv");
        Files.writeString(file, table);
        String[] selected = {"1", "1", "111", "1.0"};
        if (!OlistContextualRunner.selectionComplete(root, OlistContextualRunner.Method.RF, selected))
            throw new AssertionError("Complete grid rejected");
        // A failed non-winning candidate must not be silently discarded.
        Files.writeString(file, table.toString().replace("3\t10\t15\ttrue", "3\t10\t14\tfalse"));
        if (OlistContextualRunner.selectionComplete(root, OlistContextualRunner.Method.RF, selected))
            throw new AssertionError("Incomplete non-winning candidate accepted");
        Files.writeString(file, table);
        if (!OlistContextualRunner.selectionComplete(root, OlistContextualRunner.Method.RF, selected))
            throw new AssertionError("Repaired grid rejected");
        System.out.println("RECOVERY_GRID_SELF_CHECK_PASS complete/reject_partial/repair; no solver invoked");
    }
}
