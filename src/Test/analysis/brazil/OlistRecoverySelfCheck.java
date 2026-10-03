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
        var complete = OlistBestCsaaDroRunner.class.getDeclaredMethod("complete",
                Path.class, Path.class, int.class, String.class, OlistContextualData.class);
        complete.setAccessible(true);
        Path dro = root.resolve("missing-final-result"), trial = dro.resolve("trial_000");
        Files.createDirectories(trial);
        Files.writeString(trial.resolve("complete.txt"), "complete\n");
        Files.writeString(trial.resolve("selection.tsv"), "header\nRF\t1\t1\n");
        var grid = OlistBestCsaaDroRunner.class.getDeclaredField("LAMBDA"); grid.setAccessible(true);
        String candidates = "header\n" + "candidate\n".repeat(((double[]) grid.get(null)).length);
        Files.writeString(trial.resolve("candidates.tsv"), candidates);
        if ((boolean) complete.invoke(null, dro, root, 0, "RF", null))
            throw new AssertionError("Missing top-level final_result accepted");
        // A malformed existing result must fail closed, not throw out of recovery.
        Path broken = root.resolve("malformed-solve"); Files.createDirectories(broken);
        Files.writeString(broken.resolve("result.tsv"), "header\nbroken\n");
        Files.writeString(broken.resolve("incumbent.tsv"), "header\nvalue\n");
        for (String name : new String[]{"weights.tsv", "model_scenarios.tsv", "lane_oos.tsv", "carrier_oos.tsv"})
            Files.writeString(broken.resolve(name), "header\nvalue\n");
        for (String name : new String[]{"max_scaling.tsv", "source_fingerprint.txt", "mosek.log"})
            Files.writeString(broken.resolve(name), "fixture");
        var data = new OlistContextualData(OlistContextualData.DEFAULT_INPUT, 20261020L);
        String[] badResult = new String[31]; java.util.Arrays.fill(badResult, "0");
        badResult[15] = "0".repeat(data.market.I); badResult[18] = "not-a-number";
        Files.writeString(broken.resolve("result.tsv"), "header\n" + String.join("\t", badResult) + "\n");
        Files.writeString(broken.resolve("lane_oos.tsv"), "header\n" + "row\n".repeat(data.market.J));
        Files.writeString(broken.resolve("carrier_oos.tsv"), "header\n" + "row\n".repeat(data.market.I));
        var solveComplete = OlistBestCsaaDroRunner.class.getDeclaredMethod("solveFilesComplete",
                Path.class, OlistContextualData.class, int.class); solveComplete.setAccessible(true);
        if ((boolean) solveComplete.invoke(null, broken, data, 1))
            throw new AssertionError("Malformed solve result accepted");
        System.out.println("RECOVERY_GRID_SELF_CHECK_PASS complete/reject_partial/repair; no solver invoked");
    }
}
