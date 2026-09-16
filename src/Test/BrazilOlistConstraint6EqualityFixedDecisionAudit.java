package Test;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.WeeklyWideLoader;
import Helper.calculateHelper.KernelType;
import Model.SecondStageEvaluator;
import Model.SolveMode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Audits already-solved equality decisions on a common 50-week training window.
 * This class fixes y from the replay CSV and performs recourse evaluation only;
 * it does not re-optimize the first-stage carrier selection.
 */
public final class BrazilOlistConstraint6EqualityFixedDecisionAudit {

    private static final int TRAIN_SIZE = 50;
    private static final int NUM_CARRIERS = 10;

    private BrazilOlistConstraint6EqualityFixedDecisionAudit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: <weeklyCsv> <equalityFastTrialsCsv> <outputCsv>");
        }

        Path weeklyCsv = Path.of(args[0]);
        Path trialsCsv = Path.of(args[1]);
        Path outputCsv = Path.of(args[2]);

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(weeklyCsv);
        Config cfg = TRBReviewerExperimentSupport.baseConfig(
                3, 1.0, KernelType.EXPONENTIAL, true, SolveMode.SAA);
        cfg.enforceDemandEquality = true;
        ProcurementParams params = InstanceGenerator.generate(
                NUM_CARRIERS,
                TRBReviewerExperimentSupport.baselineDemand(weekly.periods),
                new InstanceGenerator.GenConfig(),
                cfg);

        List<InputRow> rows = readRows(trialsCsv);
        Files.createDirectories(outputCsv.toAbsolutePath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8)) {
            writer.write("method,trialId,testPeriodIdx,selectedCount,trainingScenarioCount,"
                    + "trainingMeanObjective,trainingMeanTransport,trainingMeanSpot,trainingMeanPenalty,"
                    + "meanDemandObjective,meanDemandTransport,meanDemandSpot,meanDemandPenalty,"
                    + "reportedExpectedObjective,reportedExpectedAbsError,yBinary");
            writer.newLine();

            for (InputRow row : rows) {
                if (!row.method.equals("Mean") && !row.method.equals("SAA")) continue;
                int start = row.testPeriodIdx - TRAIN_SIZE;
                if (start < 0 || row.testPeriodIdx > weekly.periods.size()) {
                    throw new IllegalArgumentException("Invalid training window for trial " + row.trialId);
                }

                Cost training = new Cost();
                double[] meanDemand = new double[params.J];
                for (int t = start; t < row.testPeriodIdx; t++) {
                    PeriodData period = weekly.periods.get(t);
                    SecondStageEvaluator.Result result = SecondStageEvaluator.evaluate(
                            params, row.y, period.demandSum.clone(), true);
                    training.add(result);
                    for (int j = 0; j < params.J; j++) meanDemand[j] += period.demandSum[j];
                }
                training.divide(TRAIN_SIZE);
                for (int j = 0; j < params.J; j++) meanDemand[j] /= TRAIN_SIZE;

                SecondStageEvaluator.Result atMean = SecondStageEvaluator.evaluate(
                        params, row.y, meanDemand, true);
                double comparable = row.method.equals("SAA") ? training.objective : atMean.objective;

                writer.write(String.format(Locale.US,
                        "%s,%d,%d,%d,%d,%.10f,%.10f,%.10f,%.10f,"
                                + "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%s%n",
                        row.method, row.trialId, row.testPeriodIdx, selectedCount(row.y), TRAIN_SIZE,
                        training.objective, training.transport, training.spot, training.penalty,
                        atMean.objective, atMean.transportCost, atMean.spotCost, atMean.penaltyCost,
                        row.reportedExpected, Math.abs(comparable - row.reportedExpected), row.yBinary));
                System.out.println("audited " + row.method + " trial=" + row.trialId);
            }
        }
        System.out.println("done " + outputCsv.toAbsolutePath());
    }

    private static List<InputRow> readRows(Path csv) throws Exception {
        List<InputRow> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first == null) return rows;
            String[] header = first.split(",", -1);
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < header.length; i++) columns.put(header[i], i);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                InputRow row = new InputRow();
                row.method = value(values, columns, "method");
                row.trialId = Integer.parseInt(value(values, columns, "trialId"));
                row.testPeriodIdx = Integer.parseInt(value(values, columns, "testPeriodIdx"));
                row.reportedExpected = Double.parseDouble(value(values, columns, "expected_obj"));
                row.yBinary = value(values, columns, "yBinary");
                row.y = parseY(row.yBinary);
                rows.add(row);
            }
        }
        return rows;
    }

    private static String value(String[] values, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index >= values.length) {
            throw new IllegalArgumentException("Missing CSV column: " + name);
        }
        return values[index].trim();
    }

    private static double[] parseY(String binary) {
        if (binary.length() != NUM_CARRIERS) {
            throw new IllegalArgumentException("Expected " + NUM_CARRIERS + " y digits: " + binary);
        }
        double[] y = new double[NUM_CARRIERS];
        for (int i = 0; i < binary.length(); i++) {
            char digit = binary.charAt(i);
            if (digit != '0' && digit != '1') {
                throw new IllegalArgumentException("Invalid yBinary: " + binary);
            }
            y[i] = digit == '1' ? 1.0 : 0.0;
        }
        return y;
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static final class InputRow {
        String method;
        int trialId;
        int testPeriodIdx;
        double reportedExpected;
        String yBinary;
        double[] y;
    }

    private static final class Cost {
        double objective;
        double transport;
        double spot;
        double penalty;

        void add(SecondStageEvaluator.Result result) {
            objective += result.objective;
            transport += result.transportCost;
            spot += result.spotCost;
            penalty += result.penaltyCost;
        }

        void divide(double divisor) {
            objective /= divisor;
            transport /= divisor;
            spot /= divisor;
            penalty /= divisor;
        }
    }
}
