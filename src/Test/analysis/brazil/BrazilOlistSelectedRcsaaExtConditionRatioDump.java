package Test;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.KernelFunction;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Computes sigma / (zbar - min_s z_s) for the final selected RCSAAEXT 35/15-CV solution of each trial.
 * Here z_s is Q(y,d^s) over the 50 training samples, zbar is the conditional weighted mean, and sigma is the
 * corresponding conditional weighted standard deviation.
 */
public class BrazilOlistSelectedRcsaaExtConditionRatioDump {
    private static final int W = 50;
    private static final int NUM_CARRIERS = 10;
    private static final double FORMAL_WEIGHT_FLOOR = 1e-8;

    private static final Path WEEKLY_INPUT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                    + "按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");

    private static final Path SELECTED_CSV = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/"
                    + "03_35_15_滚动CV结果/02_RCSAAEXT_精确枚举结果/"
                    + "02_RCSAAEXT_35_15_CV选参最终选中参数结果/"
                    + "RCSAAEXT_35_15_CV选参_最终选中参数对应的样本外逐trial结果.csv");

    private static final Path OUT_CSV = SELECTED_CSV.resolveSibling(
            "../../03_RCSAAEXT与DRO_35_15_对照结果/"
                    + "验证proposition 2_RCSAAEXT_35_15_CV最终选中参数_逐trial条件右侧sigma除以zbar减minzs.csv")
            .normalize();

    public static void main(String[] args) throws Exception {
        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY_INPUT);
        List<String> lanes = weekly.laneNames;
        double[] dBase = buildBaselineDemand(weekly.periods);
        ProcurementParams params = InstanceGenerator.generate(
                NUM_CARRIERS, dBase, new InstanceGenerator.GenConfig(), buildBaseConfig(3, 1.0, 0.01));

        List<Row> rows = loadRows(SELECTED_CSV);
        java.util.Map<Integer, List<Sample>> samplesByK = new java.util.HashMap<>();
        for (Row row : rows) {
            if (!samplesByK.containsKey(row.k)) {
                SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(
                        weekly.periods, lanes, buildBaseConfig(row.k, 1.0, 0.01));
                samplesByK.put(row.k, br.samples);
            }
        }

        try (BufferedWriter bw = Files.newBufferedWriter(OUT_CSV, StandardCharsets.UTF_8)) {
            bw.write("trialId,testPeriodIdx,k,C_h,lambda,expected_obj_from_file,zbar_conditional_mean,"
                    + "min_zs,sigma_conditional_std,zbar_minus_min_zs,sigma_div_zbar_minus_min_zs,"
                    + "condition_ratio_ge_lambda,selected_count,yBinary,selectedCarriers,"
                    + "sample_weights,sample_q_values");
            bw.newLine();

            for (Row row : rows) {
                Computed c = computeOne(params, samplesByK.get(row.k), row);
                bw.write(String.format(Locale.US,
                        "%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%s,%d,\"%s\",\"%s\",\"%s\",\"%s\"%n",
                        row.trialId, row.testPeriodIdx, row.k, row.cH, row.lambda,
                        row.expectedObjFromFile, c.zbar, c.minZs, c.sigma, c.denom, c.ratio,
                        String.valueOf(c.ratio + 1e-9 >= row.lambda),
                        row.selectedCount, encodeY(row.y), row.selectedCarriers,
                        encodeArray(c.weights), encodeArray(c.qValues)));

                if (row.trialId == 0) {
                    System.out.println(String.format(Locale.US,
                            "trial 0: zbar=%.10f min_zs=%.10f sigma=%.10f ratio=%.10f lambda=%.10f",
                            c.zbar, c.minZs, c.sigma, c.ratio, row.lambda));
                }
            }
        }

        System.out.println("wrote: " + OUT_CSV.toAbsolutePath());
    }

    private static Computed computeOne(ProcurementParams params, List<Sample> samplesForK, Row row) throws Exception {
        TrainWindow window = buildTrainingWindow(samplesForK, row.testPeriodIdx, row.k);
        List<Sample> train = BatchRunner.deepCopySamples(window.trainSamples);
        Sample testSample = window.testSample;
        CovariateVector thetaNow = new CovariateVector(testSample.theta.values().clone());

        int thetaDim = train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length;
        if (!train.isEmpty() && train.size() >= 2) {
            StandardScaler scaler = new StandardScaler();
            scaler.fit(train, thetaDim);
            for (Sample s : train) s.theta = new CovariateVector(scaler.transform(s.theta.values()));
            thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
        }

        Config cfg = buildBaseConfig(row.k, row.cH, row.lambda);
        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
        wc.computeKernelWeights(train, thetaNow, cfg);
        normalizeWithFormalFloor(train);

        double[] q = empiricalRecourseCosts(params, train, row.y);
        double zbar = weightedMean(q, train);
        double sigma = Math.sqrt(Math.max(0.0, weightedVariance(q, train, zbar)));
        double min = min(q);

        Computed out = new Computed();
        out.weights = weights(train);
        out.qValues = q;
        out.zbar = zbar;
        out.sigma = sigma;
        out.minZs = min;
        out.denom = zbar - min;
        out.ratio = out.denom > 1e-12 ? sigma / out.denom : Double.POSITIVE_INFINITY;
        return out;
    }

    private static Config buildBaseConfig(int k1, double cH, double lambda) {
        Config cfg = new Config();
        cfg.fillMissingDates = false;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = k1;
        cfg.demandAgg = false;
        cfg.lagDemandAsShare = false;
        cfg.featureFlags.includeLagDemand = true;
        cfg.featureFlags.includeHolidayCount = false;
        cfg.featureFlags.includeFreightIndex = false;
        cfg.featureFlags.includeConsumptionIndex = false;
        cfg.featureFlags.includeWEIIndex = false;
        cfg.standardizeTheta = true;
        cfg.kernelType = Helper.calculateHelper.KernelType.EXPONENTIAL;
        cfg.C_h = cH;
        cfg.lambda = lambda;
        cfg.seed = 0;
        cfg.writeCplexLogToFile = false;
        return cfg;
    }

    private static TrainWindow buildTrainingWindow(List<Sample> samplesForK, int testPeriodIdx, int k) {
        int testSampleIdx = testPeriodIdx - k;
        int start = testSampleIdx - W;
        int end = testSampleIdx;
        if (start < 0 || end > samplesForK.size()) {
            throw new IllegalArgumentException("Invalid training window for testPeriod=" + testPeriodIdx + ", k=" + k);
        }
        return new TrainWindow(new ArrayList<>(samplesForK.subList(start, end)), samplesForK.get(testSampleIdx));
    }

    private static double[] empiricalRecourseCosts(ProcurementParams params, List<Sample> train, double[] y) throws Exception {
        double[] q = new double[train.size()];
        for (int i = 0; i < train.size(); i++) {
            q[i] = BatchRunner.RecourseEvaluator.evaluate(params, y, train.get(i).demand().clone()).objValue;
        }
        return q;
    }

    private static void normalizeWithFormalFloor(List<Sample> train) {
        double sum = 0.0;
        for (Sample s : train) {
            s.weight = Math.max(s.weight, FORMAL_WEIGHT_FLOOR);
            sum += s.weight;
        }
        for (Sample s : train) s.weight /= sum;
    }

    private static double weightedMean(double[] vals, List<Sample> weightedSamples) {
        double sum = 0.0;
        double sumW = 0.0;
        for (int i = 0; i < vals.length; i++) {
            double w = weightedSamples.get(i).weight;
            sum += w * vals[i];
            sumW += w;
        }
        return sum / sumW;
    }

    private static double weightedVariance(double[] vals, List<Sample> weightedSamples, double mean) {
        double sum = 0.0;
        double sumW = 0.0;
        for (int i = 0; i < vals.length; i++) {
            double w = weightedSamples.get(i).weight;
            double d = vals[i] - mean;
            sum += w * d * d;
            sumW += w;
        }
        return sum / sumW;
    }

    private static double[] buildBaselineDemand(List<PeriodData> periods) {
        int jSize = periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (PeriodData p : periods) {
            for (int j = 0; j < jSize; j++) sum[j] += p.demandSum[j];
        }
        for (int j = 0; j < jSize; j++) sum[j] /= Math.max(1, periods.size());
        return sum;
    }

    private static List<Row> loadRows(Path csv) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = parseCsvLine(line);
                Row r = new Row();
                r.trialId = (int) Math.round(parseDouble(f[0]));
                r.testPeriodIdx = (int) Math.round(parseDouble(f[1]));
                r.k = (int) Math.round(parseDouble(f[3]));
                r.cH = parseDouble(f[4]);
                r.lambda = parseDouble(f[5]);
                r.expectedObjFromFile = parseDouble(f[13]);
                r.selectedCount = (int) Math.round(parseDouble(f[16]));
                r.y = parseY(f[37]);
                r.selectedCarriers = f[38].replace("\"", "");
                rows.add(r);
            }
        }
        return rows;
    }

    private static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static double parseDouble(String s) {
        return Double.parseDouble(s.replace("\uFEFF", "").replace("\"", "").trim());
    }

    private static double[] parseY(String s) {
        String t = s.replace("\"", "").replace("[", "").replace("]", "").trim();
        String[] parts = t.split(",");
        double[] y = new double[parts.length];
        for (int i = 0; i < parts.length; i++) y[i] = Double.parseDouble(parts[i].trim());
        return y;
    }

    private static double min(double[] vals) {
        double out = Double.POSITIVE_INFINITY;
        for (double v : vals) out = Math.min(out, v);
        return out;
    }

    private static double[] weights(List<Sample> samples) {
        double[] out = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) out[i] = samples.get(i).weight;
        return out;
    }

    private static String encodeY(double[] y) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < y.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(y[i] > 0.5 ? '1' : '0');
        }
        return sb.append(']').toString();
    }

    private static String encodeArray(double[] vals) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(String.format(Locale.US, "%.10f", vals[i]));
        }
        return sb.toString();
    }

    private static final class Row {
        int trialId, testPeriodIdx, k, selectedCount;
        double cH, lambda, expectedObjFromFile;
        double[] y;
        String selectedCarriers;
    }

    private static final class Computed {
        double[] weights, qValues;
        double zbar, sigma, minZs, denom, ratio;
    }

    private static final class TrainWindow {
        final List<Sample> trainSamples;
        final Sample testSample;

        TrainWindow(List<Sample> trainSamples, Sample testSample) {
            this.trainSamples = trainSamples;
            this.testSample = testSample;
        }
    }
}
