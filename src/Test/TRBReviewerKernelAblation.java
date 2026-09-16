package Test;

import Helper.calculateHelper.KernelType;
import Model.RCSAASolverVariant;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reviewer experiment for R1-M1 and R4-M15 (P1).
 *
 * <p><strong>Input.</strong> The Olist daily-demand CSV, carrier count, common
 * output root, trial range and parallel-trial limit.</p>
 *
 * <p><strong>Operation.</strong> Runs EXPONENTIAL and GAUSSIAN through the same
 * 35/15 rolling-CV workflow. Each kernel independently re-selects k, C_h and
 * lambda. The reviewer-only properties also force the corrected demand
 * equality in training and OOS evaluation; historical direct calls retain
 * their original >= default.</p>
 *
 * <p><strong>Output.</strong> One subdirectory per kernel containing candidate,
 * selected-parameter, per-trial cost/decision/runtime and global-summary CSVs.
 * This is a first-stage optimization experiment. It deliberately does not add
 * kNN, random-forest or other unrelated weighting methods.</p>
 */
public class TRBReviewerKernelAblation {

    /** Reviewer operation: solve both kernels; does not merely postprocess old output. */

    public static void main(String[] args) throws Exception {
        Path dailyCsv = Paths.get(args.length > 0 ? args[0]
                : "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                + "按purchase时间_按大区OD聚合_清洗后_日度需求表_千克.csv");
        int numCarriers = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        Path outputRoot = Paths.get(args.length > 2 ? args[2]
                : "analysis/巴西数据分析/新版_purchase时间/输出/返修实验/R1-M1_R4-M15_核函数消融");
        int startTrial = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int maxTrials = args.length > 4 ? Integer.parseInt(args[4]) : Integer.MAX_VALUE;
        int maxParallelTrials = args.length > 5 ? Integer.parseInt(args[5]) : 3;

        String previousKernel = System.getProperty("trb.reviewer.kernelType");
        String previousEquality = System.getProperty("trb.reviewer.enforceDemandEquality");
        String previousVariant = System.getProperty("trb.reviewer.rcsaaSolverVariant");
        String previousLabel = System.getProperty("trb.reviewer.rcsaaResultLabel");
        try {
            System.setProperty("trb.reviewer.enforceDemandEquality", "true");
            System.setProperty("trb.reviewer.rcsaaSolverVariant", RCSAASolverVariant.DRO_EXTENSIVE.name());
            System.setProperty("trb.reviewer.rcsaaResultLabel", "DRO");
            for (KernelType kernel : new KernelType[] {KernelType.EXPONENTIAL, KernelType.GAUSSIAN}) {
                System.setProperty("trb.reviewer.kernelType", kernel.name());
                Path kernelOut = outputRoot.resolve(kernel.name().toLowerCase());
                BrazilOlistAdaptiveCVSolveComparison.main(new String[] {
                        dailyCsv.toString(),
                        Integer.toString(numCarriers),
                        "50",
                        kernelOut.toString(),
                        Integer.toString(startTrial),
                        Integer.toString(maxTrials),
                        Integer.toString(maxParallelTrials)
                });
            }
            TRBReviewerKernelAblationSummary.summarize(outputRoot);
        } finally {
            if (previousKernel == null) {
                System.clearProperty("trb.reviewer.kernelType");
            } else {
                System.setProperty("trb.reviewer.kernelType", previousKernel);
            }
            if (previousEquality == null) {
                System.clearProperty("trb.reviewer.enforceDemandEquality");
            } else {
                System.setProperty("trb.reviewer.enforceDemandEquality", previousEquality);
            }
            restore("trb.reviewer.rcsaaSolverVariant", previousVariant);
            restore("trb.reviewer.rcsaaResultLabel", previousLabel);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
