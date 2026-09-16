package Test;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Scales only the carrier MQC thresholds p_i under demand equality.
 * Rates, capacities, penalties, coverage, demand data, rolling windows, and
 * solver settings remain identical to the controlled equality replay.
 */
public final class BrazilOlistConstraint6EqualityMqcQuantityScaling {

    private static final Path OUTPUT_ROOT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "17_MQC承诺量统一缩放_20260808");

    private BrazilOlistConstraint6EqualityMqcQuantityScaling() {
    }

    public static void main(String[] args) throws Exception {
        String methods = args.length > 0 ? args[0] : "Mean,SAA";
        String factorFilter = args.length > 1 ? args[1] : "ALL";
        String[] runnerArgs = new String[]{"", "", "", "", "0", "2147483647", methods};

        runIfSelected(runnerArgs, factorFilter, "0.50",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.P_SCALE_050,
                "01_g缩放_0.50");
        runIfSelected(runnerArgs, factorFilter, "0.75",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.P_SCALE_075,
                "02_g缩放_0.75");
        runIfSelected(runnerArgs, factorFilter, "1.00",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.P_SCALE_100,
                "03_g缩放_1.00_复现闸门");
    }

    private static void runIfSelected(String[] sourceArgs,
                                      String factorFilter,
                                      String factor,
                                      BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode mode,
                                      String directory) throws Exception {
        if (!"ALL".equalsIgnoreCase(factorFilter) && !factor.equals(factorFilter)) return;
        String[] args = sourceArgs.clone();
        BrazilOlistConstraint6EqualityPreOosCalibration.run(
                args, mode, OUTPUT_ROOT.resolve(directory));
    }
}
