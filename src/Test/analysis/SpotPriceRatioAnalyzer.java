package Test.analysis;

import Basic.ProcurementParams;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 兼容入口：实际实现已迁移到 Test.analysis.legacy 子包。
 */
public final class SpotPriceRatioAnalyzer {
    private SpotPriceRatioAnalyzer() {}

    public static void run(ProcurementParams params, Path outDir) throws IOException {
        Test.analysis.legacy.SpotPriceRatioAnalyzer.run(params, outDir);
    }

    public static Test.analysis.legacy.SpotPriceRatioAnalyzer.Report analyze(ProcurementParams params) {
        return Test.analysis.legacy.SpotPriceRatioAnalyzer.analyze(params);
    }
}
