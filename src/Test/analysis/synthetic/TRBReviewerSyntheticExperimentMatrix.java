package Test.analysis.synthetic;

import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Frozen configuration interface for the synthetic reviewer experiments.
 *
 * <p><strong>R3-3/R4-M35.</strong> {@link #mainComparison(int, int)} creates
 * the required lognormal/uniform by low/high-volatility cells with paired
 * seeds. <strong>R3-4/R4-M33--M35.</strong> {@link #scaleStress(int, boolean)}
 * creates a non-factorial I/J/S staircase, so the scale test does not explode
 * into an unnecessary full Cartesian grid.</p>
 *
 * <p>This class only defines and exports experiment cells. It generates no
 * demand, procurement parameters or optimization result. Each row is intended
 * to be materialized by the saved-instance builder and then consumed by the
 * synthetic main-solve interface.</p>
 */
public final class TRBReviewerSyntheticExperimentMatrix {

    private static final double[] VOLATILITY_LEVELS = {0.15, 0.30};

    private TRBReviewerSyntheticExperimentMatrix() {
    }

    /** Four DGP cells times the requested number of paired random seeds. */
    public static List<Cell> mainComparison(int seedCount, int oosDraws) {
        if (seedCount <= 0 || oosDraws <= 0) {
            throw new IllegalArgumentException("seedCount and oosDraws must be positive.");
        }
        List<Cell> cells = new ArrayList<>(4 * seedCount);
        for (InnovationDistribution distribution : InnovationDistribution.values()) {
            for (double cv : VOLATILITY_LEVELS) {
                for (int seed = 1; seed <= seedCount; seed++) {
                    cells.add(new Cell(
                            "R3M3_MAIN",
                            distribution,
                            cv,
                            seed,
                            10,
                            23,
                            50,
                            oosDraws,
                            3));
                }
            }
        }
        return Collections.unmodifiableList(cells);
    }

    /**
     * Non-factorial computational staircase. Distribution, volatility and
     * hyperparameters are held fixed so runtime changes are not confounded with
     * another DGP comparison.
     */
    public static List<Cell> scaleStress(int seedCount, boolean includeJ200) {
        if (seedCount <= 0) throw new IllegalArgumentException("seedCount must be positive.");
        int[][] sizes = includeJ200
                ? new int[][] {{10, 23, 50}, {20, 50, 50}, {20, 50, 100},
                        {30, 100, 100}, {30, 200, 100}}
                : new int[][] {{10, 23, 50}, {20, 50, 50}, {20, 50, 100},
                        {30, 100, 100}};

        List<Cell> cells = new ArrayList<>(sizes.length * seedCount);
        for (int[] size : sizes) {
            for (int seed = 1; seed <= seedCount; seed++) {
                cells.add(new Cell(
                        "R3M4_R4M33_SCALE",
                        InnovationDistribution.LOGNORMAL,
                        0.30,
                        seed,
                        size[0],
                        size[1],
                        size[2],
                        1000,
                        3));
            }
        }
        return Collections.unmodifiableList(cells);
    }

    /** Writes a reviewable matrix; it does not start any solver. */
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args.length > 0 ? args[0]
                : "analysis/巴西数据分析/新版_purchase时间/输出/返修实验/"
                + "synthetic_experiment_matrix.csv");
        int mainSeeds = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int scaleSeeds = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        boolean includeJ200 = args.length > 3 && Boolean.parseBoolean(args[3]);

        List<Cell> cells = new ArrayList<>();
        cells.addAll(mainComparison(mainSeeds, 1000));
        cells.addAll(scaleStress(scaleSeeds, includeJ200));
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("experimentBlock,distribution,innovationCv,replicationSeed,I,J,S,oosDraws,observedLags");
            writer.newLine();
            for (Cell cell : cells) {
                writer.write(String.format(Locale.US,
                        "%s,%s,%.6f,%d,%d,%d,%d,%d,%d%n",
                        cell.experimentBlock,
                        cell.distribution,
                        cell.innovationCv,
                        cell.replicationSeed,
                        cell.carrierCount,
                        cell.laneCount,
                        cell.trainingSampleCount,
                        cell.oosDraws,
                        cell.observedLagPeriods));
            }
        }
        System.out.println("matrix=" + output.toAbsolutePath() + " rows=" + cells.size());
    }

    /** One immutable row in the predeclared experiment matrix. */
    public static final class Cell {
        public final String experimentBlock;
        public final InnovationDistribution distribution;
        public final double innovationCv;
        public final long replicationSeed;
        public final int carrierCount;
        public final int laneCount;
        public final int trainingSampleCount;
        public final int oosDraws;
        public final int observedLagPeriods;

        private Cell(String experimentBlock,
                     InnovationDistribution distribution,
                     double innovationCv,
                     long replicationSeed,
                     int carrierCount,
                     int laneCount,
                     int trainingSampleCount,
                     int oosDraws,
                     int observedLagPeriods) {
            this.experimentBlock = experimentBlock;
            this.distribution = distribution;
            this.innovationCv = innovationCv;
            this.replicationSeed = replicationSeed;
            this.carrierCount = carrierCount;
            this.laneCount = laneCount;
            this.trainingSampleCount = trainingSampleCount;
            this.oosDraws = oosDraws;
            this.observedLagPeriods = observedLagPeriods;
        }

        /** Converts the row to the existing DGP settings object. */
        public Settings toDemandSettings() {
            Settings settings = new Settings();
            settings.laneCount = laneCount;
            settings.trainingSampleCount = trainingSampleCount;
            settings.oosSampleCount = oosDraws;
            settings.observedLagPeriods = observedLagPeriods;
            settings.innovationDistribution = distribution;
            settings.innovationCv = innovationCv;
            settings.replicationSeed = replicationSeed;
            return settings;
        }
    }
}
