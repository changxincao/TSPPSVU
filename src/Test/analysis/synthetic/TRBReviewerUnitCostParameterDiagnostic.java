package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Read-only summary of contract, spot, and MQC unit rates for frozen markets. */
public final class TRBReviewerUnitCostParameterDiagnostic {
    private TRBReviewerUnitCostParameterDiagnostic() {
    }

    /** Usage: {@code <weekly-csv> <number-of-markets>}. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: <weekly-csv> <number-of-markets>");
        }
        Path weekly = Path.of(args[0]);
        int numberOfMarkets = Integer.parseInt(args[1]);
        var calibration = TRBReviewerOlistDynamicDgpV2.calibrate(weekly, 23);

        Values allSpot = new Values();
        Values allContract = new Values();
        Values allHMin = new Values();
        Values allHMax = new Values();
        Values allKappa = new Values();
        for (int seed = 0; seed < numberOfMarkets; seed++) {
            Config config = new Config();
            config.seed = seed;
            InstanceGenerator.GenConfig generation = new InstanceGenerator.GenConfig();
            generation.betaRatio = 0.70;
            ProcurementParams market = InstanceGenerator.generate(
                    30, calibration.baselineDemand(), generation, config);

            for (int lane = 0; lane < market.J; lane++) {
                allSpot.add(market.e[lane]);
                List<Double> laneRates = new ArrayList<>(market.I);
                for (int carrier = 0; carrier < market.I; carrier++) {
                    double rate = market.r[carrier][lane];
                    allContract.add(rate);
                    laneRates.add(rate);
                }
                Collections.sort(laneRates);
                allKappa.add(market.e[lane] / median(laneRates));
            }
            for (int carrier = 0; carrier < market.I; carrier++) {
                double minimum = Double.POSITIVE_INFINITY;
                double maximum = Double.NEGATIVE_INFINITY;
                for (int lane = 0; lane < market.J; lane++) {
                    minimum = Math.min(minimum, market.r[carrier][lane]);
                    maximum = Math.max(maximum, market.r[carrier][lane]);
                }
                allHMin.add(minimum);
                allHMax.add(maximum);
            }
        }

        System.out.println("quantity,count,min,mean,max");
        print("spot_unit_e", allSpot);
        print("contract_unit_r", allContract);
        print("mqc_unit_h_min_rule", allHMin);
        print("mqc_unit_h_max_rule", allHMax);
        print("spot_to_lane_median_multiplier", allKappa);
        System.out.printf(Locale.US, "h_max_mean_over_h_min_mean,1,%.17g,%.17g,%.17g%n",
                allHMax.mean() / allHMin.mean(),
                allHMax.mean() / allHMin.mean(),
                allHMax.mean() / allHMin.mean());
    }

    private static void print(String name, Values values) {
        System.out.printf(Locale.US, "%s,%d,%.17g,%.17g,%.17g%n",
                name, values.count, values.minimum, values.mean(), values.maximum);
    }

    private static double median(List<Double> sorted) {
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : 0.5 * (sorted.get(middle - 1) + sorted.get(middle));
    }

    private static final class Values {
        private int count;
        private double sum;
        private double minimum = Double.POSITIVE_INFINITY;
        private double maximum = Double.NEGATIVE_INFINITY;

        void add(double value) {
            count++;
            sum += value;
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }

        double mean() {
            return sum / count;
        }
    }
}
