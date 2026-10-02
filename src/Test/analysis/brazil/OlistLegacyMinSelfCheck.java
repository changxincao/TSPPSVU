package Test.analysis.brazil;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import java.nio.file.*;
import java.util.*;

/** No optimization: verify the sole legacy-market change and readable snapshot round trip. */
public final class OlistLegacyMinSelfCheck {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]); Files.createDirectories(dir);
        List<String> commonDemands = null;
        for (int carriers : new int[]{10,15}) for (int seed = 0; seed < 3; seed++) {
            var data = OlistContextualData.legacyMin(OlistContextualData.DEFAULT_INPUT, seed, carriers);
            Config cfg = new Config(); cfg.seed = seed;
            ProcurementParams old = InstanceGenerator.generate(carriers, data.baselineDemand,
                    new InstanceGenerator.GenConfig(), cfg);
            ProcurementParams market = data.market;
            require(market.alpha == (int)Math.ceil(.1 * carriers)
                    && market.beta == (int)Math.ceil(.7 * carriers), "Legacy selection bounds");
            require(Arrays.equals(old.e,market.e) && Arrays.equals(old.p,market.p)
                    && Arrays.equals(old.M,market.M) && Arrays.deepEquals(old.q,market.q)
                    && Arrays.deepEquals(old.r,market.r) && Arrays.deepEquals(old.eligible,market.eligible),
                    "No change except penalty");
            for (int i = 0; i < carriers; i++) {
                require(market.h[i] == Arrays.stream(market.r[i]).min().orElseThrow(), "Min penalty");
                require(old.h[i] == Arrays.stream(old.r[i]).max().orElseThrow(), "Legacy max penalty");
                for (int j = 0; j < market.J; j++) require(market.eligible[i][j], "Full coverage retained");
            }
            Path first = dir.resolve("I" + carriers + "_seed" + seed + ".tsv"), second = dir.resolve("roundtrip.tsv");
            data.saveSnapshot(first); OlistContextualData.loadSnapshot(first).saveSnapshot(second);
            require(Files.mismatch(first,second) == -1, "Snapshot round trip");
            var demands = Files.readAllLines(first).stream().filter(s -> s.startsWith("DEMAND\t")).toList();
            if (commonDemands == null) commonDemands = demands;
            else require(commonDemands.equals(demands), "Identical real demands across all six markets");
            System.out.println("LEGACY_MIN_PASS I=" + carriers + " seed=" + seed + " bounds="
                    + market.alpha + ".." + market.beta + " only_h_changed=true");
        }
        System.out.println("LEGACY_SIX_MARKETS_AND_SNAPSHOT_CHECK_PASS no_solves");
        if (args.length > 1 && args[1].equals("smoke")) {
            OlistContextualRunner.SNAPSHOT = dir.resolve("I10_seed0.tsv");
            OlistContextualRunner.MARKET_SEED = 0;
            OlistContextualRunner.OUTPUT = dir.resolve("baseline_smoke");
            OlistContextualRunner.BANDWIDTH = new double[]{1};
            OlistContextualRunner.METHODS = new OlistContextualRunner.Method[]{OlistContextualRunner.Method.EXP};
            OlistContextualRunner.TRIAL_COUNT = 1;
            OlistContextualRunner.LIMIT_SECONDS = 60;
            OlistContextualRunner.main(new String[]{"run"});
            var data = OlistContextualData.loadSnapshot(OlistContextualRunner.SNAPSHOT);
            require(OlistContextualRunner.taskComplete(OlistContextualRunner.OUTPUT.resolve("trial_000/EXP"),
                    53, OlistContextualRunner.Method.EXP, data), "Ten-carrier final solve/OOS audit");
            System.out.println("LEGACY_I10_CPLEX_EXP_ROLLING_AND_FINAL_SMOKE_PASS");
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
