package Test;

import Helper.basicHelper.Config;
import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.calculateHelper.KernelType;
import Model.RCSAASolverVariant;
import Model.SolveMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Reviewer stress test for R3-4, R4-M22, R4-M33 and R4-M34 (partial P3).
 *
 * <p><strong>Input.</strong> Weekly-wide Olist demand, output root, trial limit,
 * fixed k/C_h/lambda/time limit and a carrier-count grid.</p>
 *
 * <p><strong>Operation.</strong> Solves RCSAA under the corrected demand
 * equality while varying carrier count and local-search radius kappa. It keeps
 * J=23 and W=50 fixed. Procurement parameters are freshly generated for each I;
 * this runner does not yet apply the 10/I economic-tightness scaling proposed
 * for the final synthetic scale study.</p>
 *
 * <p><strong>Output.</strong> Per-trial decisions, objectives, OOS cost
 * components, ESS and elapsed solve time. The current downstream runner does
 * not export certified gap, iteration, cut, node or candidate counts. Hence
 * this is a real first-stage stress test, but only a partial interface for the
 * full R3-4/R4-M33--M35 scalability response.</p>
 */
public class TRBReviewerKappaCarrierScalability {

    private static final int[] DEFAULT_CARRIER_GRID = {10, 15, 20, 30};
    private static final int[] KAPPA_GRID = {1, 2, 3};
    private static final int WINDOW = 50;
    private static final int MINIMUM_COMMON_TEST_INDEX = 50;

    public static void main(String[] args) throws Exception {
        Path weeklyCsv = Paths.get(args.length > 0 ? args[0]
                : TRBReviewerExperimentSupport.DEFAULT_WEEKLY_CSV.toString());
        Path outRoot = Paths.get(args.length > 1 ? args[1]
                : "analysis/巴西数据分析/新版_purchase时间/输出/返修实验/"
                + "R3-4_R4-M22_M33_M34_承运人数与kappa压力测试");
        int maxTrials = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        int k = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        double cH = args.length > 4 ? Double.parseDouble(args[4]) : 0.1;
        double lambda = args.length > 5 ? Double.parseDouble(args[5]) : 1.0;
        int timeLimitSeconds = args.length > 6 ? Integer.parseInt(args[6]) : 3600;
        int[] carrierGrid = args.length > 7 ? parseIntGrid(args[7]) : DEFAULT_CARRIER_GRID;
        Files.createDirectories(outRoot);

        GlobalSummaryCollector summaries = new GlobalSummaryCollector(outRoot);
        GlobalTrialCollector trials = new GlobalTrialCollector(outRoot);

        for (int carriers : carrierGrid) {
            TRBReviewerExperimentSupport.Prepared prepared = TRBReviewerExperimentSupport.prepare(
                    weeklyCsv, carriers, k, cH, KernelType.EXPONENTIAL, true, SolveMode.RCSAA);
            ExperimentBatches all = ExperimentBuilder.buildRolling(prepared.built.samples, WINDOW);
            ExperimentBatches selectedTrials = TRBReviewerExperimentSupport.retainTestIndicesAtLeast(
                    all, MINIMUM_COMMON_TEST_INDEX, maxTrials);
            if (selectedTrials.size() == 0) {
                throw new IllegalStateException("No selected stress-test trials for carriers=" + carriers);
            }

            for (int kappa : KAPPA_GRID) {
                Config cfg = TRBReviewerExperimentSupport.baseConfig(
                        k, cH, KernelType.EXPONENTIAL, true, SolveMode.RCSAA);
                cfg.lambda = lambda;
                cfg.timeLimitSeconds = timeLimitSeconds;
                cfg.rcsaaSolverVariant = RCSAASolverVariant.LBBD_SEARCH;
                cfg.rcsaaSearchNeighborhoodRadius = kappa;

                String tag = String.format(Locale.US,
                        "TRB_R3-4_R4-M22_M33_M34_EQ_I%d_J%d_W%d_k%d_C%.4f_lambda%.4f_kappa%d",
                        carriers, prepared.params.J, WINDOW, k, cH, lambda, kappa);
                Path comboOut = outRoot.resolve("I" + carriers + "_kappa" + kappa);
                DROBatchRunner.run(
                        tag,
                        prepared.weekly.laneNames,
                        prepared.params,
                        cfg,
                        selectedTrials,
                        comboOut,
                        summaries,
                        trials,
                        prepared.genCfg);
            }
        }
    }

    private static int[] parseIntGrid(String raw) {
        String[] tokens = raw.split(",");
        int[] values = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            values[i] = Integer.parseInt(tokens[i].trim());
            if (values[i] <= 0) throw new IllegalArgumentException("Carrier counts must be positive.");
        }
        return values;
    }
}
