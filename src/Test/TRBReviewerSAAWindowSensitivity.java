package Test;

import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.calculateHelper.KernelType;
import Model.SolveMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reviewer experiment for R4-M28 and the SAA timing part of R3-2 (P2/P5).
 *
 * <p><strong>Input.</strong> Weekly-wide demand CSV, carrier count, output root
 * and optional maximum number of common OOS trials.</p>
 *
 * <p><strong>Operation.</strong> Solves equal-weight SAA with W={26,39,50}
 * under the corrected demand equality. All variants retain only test indices
 * available under W=50, so OOS weeks are paired. This is window sensitivity;
 * it is not a tuned-window SAA unless a separate inner validation selects W.</p>
 *
 * <p><strong>Output.</strong> Per-window trial decisions, OOS costs, cost
 * components, selected-carrier counts and solve times plus common summaries.
 * This class performs first-stage optimization.</p>
 */
public class TRBReviewerSAAWindowSensitivity {

    private static final int[] WINDOWS = {26, 39, 50};
    private static final int MAIN_WINDOW = 50;
    private static final int K_FOR_ALIGNMENT = 3;

    public static void main(String[] args) throws Exception {
        Path weeklyCsv = Paths.get(args.length > 0 ? args[0]
                : TRBReviewerExperimentSupport.DEFAULT_WEEKLY_CSV.toString());
        int numCarriers = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        Path outRoot = Paths.get(args.length > 2 ? args[2]
                : "analysis/巴西数据分析/新版_purchase时间/输出/返修实验/R4-M28_SAA窗口敏感性");
        int maxTrials = args.length > 3 ? Integer.parseInt(args[3]) : Integer.MAX_VALUE;
        Files.createDirectories(outRoot);

        TRBReviewerExperimentSupport.Prepared prepared = TRBReviewerExperimentSupport.prepare(
                weeklyCsv, numCarriers, K_FOR_ALIGNMENT, 1.0,
                KernelType.EXPONENTIAL, true, SolveMode.SAA);
        GlobalSummaryCollector summaries = new GlobalSummaryCollector(outRoot);
        GlobalTrialCollector trials = new GlobalTrialCollector(outRoot);

        for (int window : WINDOWS) {
            ExperimentBatches all = ExperimentBuilder.buildRolling(prepared.built.samples, window);
            ExperimentBatches aligned = TRBReviewerExperimentSupport.retainTestIndicesAtLeast(
                    all, MAIN_WINDOW, maxTrials);
            if (aligned.size() == 0) {
                throw new IllegalStateException("No common test weeks for SAA window=" + window);
            }

            String tag = "TRB_R4-M28_SAA_EQ_W" + window + "_commonTestFrom" + MAIN_WINDOW;
            SAACSAAPlainBatchRunner.run(
                    tag,
                    prepared.weekly.laneNames,
                    prepared.params,
                    prepared.cfg,
                    aligned,
                    outRoot.resolve("W" + window),
                    summaries,
                    trials,
                    prepared.genCfg);
        }
    }
}
