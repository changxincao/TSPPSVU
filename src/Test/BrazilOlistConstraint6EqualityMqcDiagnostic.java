package Test;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runs pre-declared MQC diagnostics under equality while leaving q_ij at the
 * controlled all-104-week value. These are mechanism tests, not selected final
 * manuscript settings.
 */
public final class BrazilOlistConstraint6EqualityMqcDiagnostic {

    private static final Path OUTPUT_ROOT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "14_约束6等式重跑_20260807/03_MQC机制分解_快速方法");

    private BrazilOlistConstraint6EqualityMqcDiagnostic() {
    }

    public static void main(String[] args) throws Exception {
        String methods = args.length > 0 ? args[0] : "Mean,SAA";
        String modeFilter = args.length > 1 ? args[1] : "ALL";
        String[] runnerArgs = new String[]{"", "", "", "", "0", "2147483647", methods};
        runIfSelected(runnerArgs, modeFilter,
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.P_PRE53_ONLY,
                "01_仅MQC门槛按前53周标定");
        runIfSelected(runnerArgs, modeFilter,
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.H_MIN_RATE_ONLY,
                "02_MQC罚率改为承运人最小报价");
        runIfSelected(runnerArgs, modeFilter,
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.P_PRE53_AND_H_MIN_RATE,
                "03_前53周MQC门槛加最小报价罚率");
        runIfSelected(runnerArgs, modeFilter,
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.MQC_OFF,
                "04_关闭MQC诊断");
    }

    private static void runIfSelected(String[] args,
                                      String modeFilter,
                                      BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode mode,
                                      String directory) throws Exception {
        if (!"ALL".equalsIgnoreCase(modeFilter) && !mode.name().equalsIgnoreCase(modeFilter)) return;
        run(args, mode, directory);
    }

    private static void run(String[] sourceArgs,
                            BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode mode,
                            String directory) throws Exception {
        String[] args = sourceArgs.clone();
        Path defaultOutput = OUTPUT_ROOT.resolve(directory);
        BrazilOlistConstraint6EqualityPreOosCalibration.run(args, mode, defaultOutput);
    }
}
