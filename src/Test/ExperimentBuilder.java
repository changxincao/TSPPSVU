package Test;

import java.util.ArrayList;
import java.util.List;

import Basic.CovariateVector;
import Basic.Sample;

/**
 * Backtest 切分构造器。
 * 这个类负责把原始样本序列切成 rolling / LOO 两类试验批次，
 * 供主求解流程和分析工具复用。
 *
 * 功能：
 * 1. `buildRolling`：构造滚动窗口试验，训练集是测试样本之前的固定窗口
 * 2. `buildLOO`：构造留一法试验，训练集是除测试样本外的全部可用样本
 */
public class ExperimentBuilder {

    /**
     * Rolling window:
     * - 对每个 `testIdx`，取 `[testIdx-W, ..., testIdx-1]` 作为训练集
     * - `thetaNow = samples[testIdx].theta`
     * - `testSample = samples[testIdx]`
     */
    public static ExperimentBatches buildRolling(List<Sample> samples, int W) {
        if (samples == null || samples.isEmpty()) return ExperimentBatches.empty();
        if (W <= 0) throw new IllegalArgumentException("W must be positive.");

        List<List<Sample>> trainSets = new ArrayList<>();
        List<CovariateVector> thetaNowList = new ArrayList<>();
        List<Sample> testSamples = new ArrayList<>();
        List<Integer> testIndex = new ArrayList<>();

        for (int testIdx = W; testIdx < samples.size(); testIdx++) {
            List<Sample> train = new ArrayList<>(W);
            for (int k = testIdx - W; k <= testIdx - 1; k++) {
                train.add(samples.get(k));
            }
            trainSets.add(train);

            Sample test = samples.get(testIdx);
            thetaNowList.add(new CovariateVector(test.theta.values().clone()));
            testSamples.add(test);
            testIndex.add(testIdx);
        }
        return new ExperimentBatches("rolling", trainSets, thetaNowList, testSamples, testIndex);
    }

    /**
     * Leave-one-out:
     * - 每个 `testIdx` 都做一次，训练集为除当前测试样本之外的全部样本
     * - `thetaNow = samples[testIdx].theta`
     */
    public static ExperimentBatches buildLOO(List<Sample> samples, int W) {
        if (samples == null || samples.isEmpty()) return ExperimentBatches.empty();

        int n = samples.size();
        List<List<Sample>> trainSets = new ArrayList<>();
        List<CovariateVector> thetaNowList = new ArrayList<>();
        List<Sample> testSamples = new ArrayList<>();
        List<Integer> testIndex = new ArrayList<>();

        for (int testIdx = W; testIdx < n; testIdx++) {
            List<Sample> train = new ArrayList<>();
            for (int k = W; k < n; k++) {
                if (k == testIdx) continue;
                train.add(samples.get(k));
            }
            trainSets.add(train);

            Sample test = samples.get(testIdx);
            thetaNowList.add(new CovariateVector(test.theta.values().clone()));
            testSamples.add(test);
            testIndex.add(testIdx);
        }
        return new ExperimentBatches("loo", trainSets, thetaNowList, testSamples, testIndex);
    }
}
