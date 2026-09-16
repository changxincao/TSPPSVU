package Test;

import Basic.PeriodData;
import Helper.calculateHelper.KernelType;
import Model.SolveMode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reviewer analysis for R4-M29 and the operational part of R1-M2 (P5).
 *
 * <p><strong>Input.</strong> Weekly-wide demand, carrier count and one or more
 * label=trial.csv files containing actual test-period IDs and yBinary.</p>
 *
 * <p><strong>Operation.</strong> Reconstructs the seeded procurement instance
 * and re-evaluates every fixed y under realized weekly demand using the
 * corrected demand equality. It does not re-solve the first-stage model, but it
 * does solve one second-stage LP per decision/week.</p>
 *
 * <p><strong>Output.</strong> Selected-carrier count, contracted and spot
 * volumes, spot share, MQC shortfall/penalty, selected-capacity utilization and
 * adjacent-week switching/Jaccard metrics, at trial and method-summary levels.
 * It is valid only when the trial CSV used the same generated procurement
 * parameters (seed/config/carrier count) reconstructed here.</p>
 */
public class TRBReviewerOperationalMetrics {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException(
                    "Usage: TRBReviewerOperationalMetrics <weekly.csv> <numCarriers> <output-dir> <label=trial.csv> [...]");
        }
        Path weeklyCsv = Paths.get(args[0]);
        int numCarriers = Integer.parseInt(args[1]);
        Path outDir = Paths.get(args[2]);
        Files.createDirectories(outDir);

        TRBReviewerExperimentSupport.Prepared prepared = TRBReviewerExperimentSupport.prepare(
                weeklyCsv, numCarriers, 3, 1.0, KernelType.EXPONENTIAL, true, SolveMode.SAA);
        Map<Integer, PeriodData> periods = new HashMap<>();
        for (PeriodData period : prepared.weekly.periods) periods.put(period.tIndex, period);

        try (BufferedWriter detail = Files.newBufferedWriter(
                outDir.resolve("operational_metrics_by_trial.csv"), StandardCharsets.UTF_8);
             BufferedWriter summary = Files.newBufferedWriter(
                     outDir.resolve("operational_metrics_summary.csv"), StandardCharsets.UTF_8)) {
            detail.write("method,period,selectedCount,demandVolume,contractVolume,spotVolume,spotShare,"
                    + "mqcShortfallVolume,mqcPenaltyCost,selectedCapacity,capacityUtilization,"
                    + "carrierChanges,switchOccurred,jaccardSimilarity");
            detail.newLine();
            summary.write("method,n,meanSelectedCount,meanSpotVolume,meanSpotShare,meanMqcShortfallVolume,"
                    + "meanMqcPenaltyCost,meanCapacityUtilization,switchRate,meanCarrierChanges,meanJaccardSimilarity");
            summary.newLine();

            for (int argIndex = 3; argIndex < args.length; argIndex++) {
                int split = args[argIndex].indexOf('=');
                if (split <= 0 || split == args[argIndex].length() - 1) {
                    throw new IllegalArgumentException("Expected label=csv, got: " + args[argIndex]);
                }
                String label = args[argIndex].substring(0, split);
                Path trialCsv = Paths.get(args[argIndex].substring(split + 1));
                List<DecisionRow> decisions = readDecisions(trialCsv);
                decisions.sort(Comparator.comparingInt(d -> d.period));

                Aggregate aggregate = new Aggregate();
                double[] previousY = null;
                for (DecisionRow decision : decisions) {
                    PeriodData period = periods.get(decision.period);
                    if (period == null) {
                        throw new IllegalArgumentException(
                                "No weekly period tIndex=" + decision.period + " for row in " + trialCsv);
                    }
                    if (decision.y.length != prepared.params.I) {
                        throw new IllegalArgumentException("y dimension " + decision.y.length
                                + " does not match numCarriers=" + prepared.params.I);
                    }

                    BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(
                            prepared.params, decision.y, period.demandSum.clone(),
                            prepared.cfg.enforceDemandEquality);
                    double demand = sum(period.demandSum);
                    double contract = sum(rec.carrierAssignedQty);
                    double spot = sum(rec.laneSpotQty);
                    double shortfall = sum(rec.carrierPenaltyQty);
                    double selectedCapacity = selectedCapacity(prepared, decision.y);
                    double utilization = selectedCapacity <= 0.0 ? Double.NaN : contract / selectedCapacity;

                    SwitchMetrics switching = previousY == null
                            ? SwitchMetrics.notAvailable()
                            : SwitchMetrics.of(previousY, decision.y);
                    previousY = decision.y.clone();

                    detail.write(String.format(Locale.US,
                            "%s,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%s,%s,%s,%s%n",
                            label, decision.period, countSelected(decision.y), demand, contract, spot,
                            demand <= 0.0 ? 0.0 : spot / demand,
                            shortfall, rec.penaltyTotalCost, selectedCapacity,
                            Double.isFinite(utilization) ? String.format(Locale.US, "%.10f", utilization) : "NA",
                            switching.available ? Integer.toString(switching.changes) : "NA",
                            switching.available ? Boolean.toString(switching.changes > 0) : "NA",
                            switching.available ? String.format(Locale.US, "%.10f", switching.jaccard) : "NA"));
                    aggregate.add(countSelected(decision.y), spot, demand, shortfall,
                            rec.penaltyTotalCost, utilization, switching);
                }
                summary.write(aggregate.toCsv(label));
                summary.newLine();
            }
        }
    }

    private static double selectedCapacity(TRBReviewerExperimentSupport.Prepared prepared, double[] y) {
        double capacity = 0.0;
        for (int i = 0; i < prepared.params.I; i++) {
            if (y[i] <= 0.5) continue;
            for (int j = 0; j < prepared.params.J; j++) {
                if (prepared.params.eligible[i][j]) capacity += prepared.params.q[i][j];
            }
        }
        return capacity;
    }

    private static List<DecisionRow> readDecisions(Path csv) throws Exception {
        List<DecisionRow> decisions = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return decisions;
            List<String> header = TRBReviewerBandwidthStability.parseCsvLine(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> fields = TRBReviewerBandwidthStability.parseCsvLine(line);
                Map<String, String> row = toMap(header, fields);
                String actual = optional(row, "actual_test_period", "testPeriodIdx");
                int period;
                if (!actual.isBlank()) {
                    period = Integer.parseInt(actual);
                } else {
                    int testIdx = Integer.parseInt(required(row, "testIdx"));
                    int k = Integer.parseInt(required(row, "k1Lag", "selected_k", "k"));
                    period = testIdx + k;
                }
                double[] y = parseY(required(row, "yBinary", "y_binary"));
                decisions.add(new DecisionRow(period, y));
            }
        }
        return decisions;
    }

    private static double[] parseY(String raw) {
        String text = raw.trim();
        if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length() - 1);
        if (text.isBlank()) return new double[0];
        String[] tokens = text.split("\\s*,\\s*");
        double[] y = new double[tokens.length];
        for (int i = 0; i < tokens.length; i++) y[i] = Double.parseDouble(tokens[i]);
        return y;
    }

    private static Map<String, String> toMap(List<String> header, List<String> fields) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) row.put(header.get(i), i < fields.size() ? fields.get(i) : "");
        return row;
    }

    private static String required(Map<String, String> row, String... names) {
        String value = optional(row, names);
        if (value.isBlank()) throw new IllegalArgumentException("Missing required column: " + String.join("/", names));
        return value;
    }

    private static String optional(Map<String, String> row, String... names) {
        for (String name : names) {
            String value = row.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static int countSelected(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static double sum(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum;
    }

    private static final class DecisionRow {
        final int period;
        final double[] y;

        DecisionRow(int period, double[] y) {
            this.period = period;
            this.y = y;
        }
    }

    private static final class SwitchMetrics {
        final boolean available;
        final int changes;
        final double jaccard;

        SwitchMetrics(boolean available, int changes, double jaccard) {
            this.available = available;
            this.changes = changes;
            this.jaccard = jaccard;
        }

        static SwitchMetrics notAvailable() {
            return new SwitchMetrics(false, 0, Double.NaN);
        }

        static SwitchMetrics of(double[] previous, double[] current) {
            int changes = 0;
            int intersection = 0;
            int union = 0;
            for (int i = 0; i < previous.length; i++) {
                boolean a = previous[i] > 0.5;
                boolean b = current[i] > 0.5;
                if (a != b) changes++;
                if (a && b) intersection++;
                if (a || b) union++;
            }
            return new SwitchMetrics(true, changes, union == 0 ? 1.0 : (double) intersection / union);
        }
    }

    private static final class Aggregate {
        int n;
        double selected;
        double spot;
        double spotShare;
        double shortfall;
        double penalty;
        double utilization;
        int utilizationN;
        int switchN;
        int switched;
        double changes;
        double jaccard;

        void add(int selectedCount, double spotVolume, double demand, double shortfallVolume,
                 double penaltyCost, double capacityUtilization, SwitchMetrics switching) {
            n++;
            selected += selectedCount;
            spot += spotVolume;
            spotShare += demand <= 0.0 ? 0.0 : spotVolume / demand;
            shortfall += shortfallVolume;
            penalty += penaltyCost;
            if (Double.isFinite(capacityUtilization)) {
                utilization += capacityUtilization;
                utilizationN++;
            }
            if (switching.available) {
                switchN++;
                if (switching.changes > 0) switched++;
                changes += switching.changes;
                jaccard += switching.jaccard;
            }
        }

        String toCsv(String label) {
            return String.format(Locale.US, "%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%s,%s,%s,%s",
                    label, n, selected / n, spot / n, spotShare / n, shortfall / n, penalty / n,
                    utilizationN == 0 ? "NA" : String.format(Locale.US, "%.10f", utilization / utilizationN),
                    switchN == 0 ? "NA" : String.format(Locale.US, "%.10f", (double) switched / switchN),
                    switchN == 0 ? "NA" : String.format(Locale.US, "%.10f", changes / switchN),
                    switchN == 0 ? "NA" : String.format(Locale.US, "%.10f", jaccard / switchN));
        }
    }
}
