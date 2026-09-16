package Test;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-factor sensitivity gate for explaining the equality-model D/SAA reversal.
 * The observed demand path and all non-target procurement parameters are frozen.
 */
public final class BrazilOlistConstraint6EqualityEconomicParameterSensitivity {

    private static final Path OUTPUT_ROOT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "18_需求不变_经济参数单因素敏感性_20260808");

    private BrazilOlistConstraint6EqualityEconomicParameterSensitivity() {
    }

    public static void main(String[] args) throws Exception {
        String methods = args.length > 0 ? args[0] : "Mean,SAA";
        String scenarioFilter = args.length > 1 ? args[1] : "ALL";
        String[] runnerArgs = new String[]{"", "", "", "", "0", "2147483647", methods};

        runIfSelected(runnerArgs, scenarioFilter, "BASE",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.P_SCALE_100,
                "01_BASE_复现闸门");
        runIfSelected(runnerArgs, scenarioFilter, "G025",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.P_SCALE_025,
                "02_g缩放_0.25");
        runIfSelected(runnerArgs, scenarioFilter, "G040",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.P_SCALE_040,
                "03_g缩放_0.40");
        runIfSelected(runnerArgs, scenarioFilter, "H025",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.H_SCALE_025,
                "04_h缩放_0.25");
        runIfSelected(runnerArgs, scenarioFilter, "H050",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.H_SCALE_050,
                "05_h缩放_0.50");
        runIfSelected(runnerArgs, scenarioFilter, "H075",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.H_SCALE_075,
                "06_h缩放_0.75");
        runIfSelected(runnerArgs, scenarioFilter, "Q075",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.Q_SCALE_075,
                "07_q缩放_0.75");
        runIfSelected(runnerArgs, scenarioFilter, "Q125",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.Q_SCALE_125,
                "08_q缩放_1.25");
        runIfSelected(runnerArgs, scenarioFilter, "Q150",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.Q_SCALE_150,
                "09_q缩放_1.50");
        runIfSelected(runnerArgs, scenarioFilter, "E075",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.E_SCALE_075,
                "10_e缩放_0.75");
        runIfSelected(runnerArgs, scenarioFilter, "E125",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.E_SCALE_125,
                "11_e缩放_1.25");
        runIfSelected(runnerArgs, scenarioFilter, "HFILL",
                BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode.H_MQC_FILL_UNIT_COST,
                "12_h按承运人容量加权MQC补足成本");
    }

    private static void runIfSelected(String[] sourceArgs,
                                      String scenarioFilter,
                                      String scenario,
                                      BrazilOlistConstraint6EqualityPreOosCalibration.CalibrationMode mode,
                                      String directory) throws Exception {
        if (!"ALL".equalsIgnoreCase(scenarioFilter)
                && !scenario.equalsIgnoreCase(scenarioFilter)) return;
        BrazilOlistConstraint6EqualityPreOosCalibration.run(
                sourceArgs.clone(), mode, OUTPUT_ROOT.resolve(directory));
    }
}
