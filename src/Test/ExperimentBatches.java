package Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import Basic.CovariateVector;
import Basic.Sample;

/**
 * Backtest 试验批次容器。
 * 这个类只负责保存一次批量切分后的结果，不负责具体切分逻辑。
 *
 * 主要内容：
 * 1. `trainSets`：每个 trial 的训练样本集
 * 2. `thetaNowList`：每个 trial 对应的当前协变量
 * 3. `testSamples`：每个 trial 对应的样本外测试样本
 * 4. `testIndex`：测试样本在原始样本序列中的索引，便于追踪和调试
 */
public class ExperimentBatches {
    public String mode;
    public final List<List<Sample>> trainSets;
    public final List<CovariateVector> thetaNowList;
    public final List<Sample> testSamples;
    public final List<Integer> testIndex;

    public ExperimentBatches(String mode, List<List<Sample>> trainSets,
                             List<CovariateVector> thetaNowList,
                             List<Sample> testSamples,
                             List<Integer> testIndex) {
        this.mode = mode;
        this.trainSets = Collections.unmodifiableList(trainSets);
        this.thetaNowList = Collections.unmodifiableList(thetaNowList);
        this.testSamples = Collections.unmodifiableList(testSamples);
        this.testIndex = Collections.unmodifiableList(testIndex);
    }

    public int size() {
        return testSamples.size();
    }

    public static ExperimentBatches empty() {
        return new ExperimentBatches("", new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }
}
