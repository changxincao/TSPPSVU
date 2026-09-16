package Test;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Helper for patching scenario q upper-bound values into weekly experiment inputs.
 * It supports later exact/primal experiments but does not run model selection or result aggregation.
 */
public class BrazilAppendScenarioQUpperBound {

    private static final int W = 50;
    private static final int NUM_CARRIERS = 10;
    private static final int[] K_GRID = {1, 2, 3};

    private static final Path WEEKLY_INPUT = Paths.get(
            "analysis/\u5df4\u897f\u6570\u636e\u5206\u6790/\u65b0\u7248_purchase\u65f6\u95f4/\u8f93\u5165/\u805a\u5408\u9700\u6c42\u8868_\u65e5\u5ea6\u4e0e\u5468\u5ea6/"
                    + "\u6309purchase\u65f6\u95f4_\u4e94\u5927\u533a23OD_\u5468\u5ea6\u5bbd\u8868_10\u4f9b\u5e94\u5546\u5b9e\u9a8c\u8f93\u5165.csv");

    private static final Path TARGET_CSV = Paths.get(
            "analysis/\u5df4\u897f\u6570\u636e\u5206\u6790/\u65b0\u7248_purchase\u65f6\u95f4/\u8f93\u51fa/\u7ed3\u679c/"
                    + "10_35_15_ExactVsDRO_\u8be6\u7ec6\u9a8c\u8bc1/\u6c47\u603b\u7ed3\u679c/"
                    + "\u6bcf\u4e2atrial_\u6bcf\u4e2a\u53c2\u6570_15\u6b21\u5e73\u5747\u8868\u73b0_\u542b\u9009\u53c2\u6807\u8bb0\u4e0e\u6837\u672c\u5916.csv");

    public static void main(String[] args) throws Exception {
        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY_INPUT);
        List<String> lanes = weekly.laneNames;

        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : K_GRID) {
            Config cfg = buildBaseConfig(k);
            SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(weekly.periods, lanes, cfg);
            samplesByK.put(k, br.samples);
        }

        double[] dBase = buildBaselineDemand(weekly.periods);
        ProcurementParams params = InstanceGenerator.generate(
                NUM_CARRIERS, dBase, new InstanceGenerator.GenConfig(), buildBaseConfig(3));
        double topBetaPenalty = topBetaPenalty(params);

        Path outCsv = TARGET_CSV.resolveSibling(stripCsvSuffix(TARGET_CSV.getFileName().toString()) + "_含场景Q上界.csv");
        try (BufferedReader br = Files.newBufferedReader(TARGET_CSV, StandardCharsets.UTF_8);
             BufferedWriter bw = Files.newBufferedWriter(outCsv, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null) {
                throw new IllegalStateException("Empty csv: " + TARGET_CSV);
            }
            String[] header = parseCsvLine(headerLine);
            Map<String, Integer> h = headerMap(header);
            int trialCol = require(h, "trial编号");
            int kCol = require(h, "k");
            int testPeriodCol = require(h, "测试期");
            int stdCol = require(h, "RCSAAEXT_参数直接样本外_条件标准差");

            List<String> newHeader = new ArrayList<>(List.of(header));
            newHeader.add("训练窗口_场景Q上界最大值");
            newHeader.add("RCSAAEXT_参数直接样本外_条件标准差除以上界最大值");
            bw.write(toCsvLine(newHeader));
            bw.newLine();

            Map<String, Double> cache = new HashMap<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = parseCsvLine(line);
                int trialId = (int) Math.round(Double.parseDouble(f[trialCol]));
                int k = (int) Math.round(Double.parseDouble(f[kCol]));
                int testPeriod = (int) Math.round(Double.parseDouble(f[testPeriodCol]));
                double condStd = parseDoubleSafe(f[stdCol]);

                String key = trialId + "|" + testPeriod + "|" + k;
                double ubMax = cache.computeIfAbsent(key, kk ->
                        computeTrainWindowScenarioUbMax(samplesByK.get(k), testPeriod, k, params, topBetaPenalty));
                double ratio = (Double.isFinite(condStd) && ubMax > 0.0) ? (condStd / ubMax) : Double.NaN;

                List<String> out = new ArrayList<>(List.of(f));
                out.add(fmt(ubMax));
                out.add(fmt(ratio));
                bw.write(toCsvLine(out));
                bw.newLine();
            }
        }
        System.out.println("updated: " + outCsv.toAbsolutePath());
    }

    private static Config buildBaseConfig(int k1) {
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
        cfg.C_h = 1.0;
        cfg.lambda = 0.01;
        cfg.seed = 0;
        return cfg;
    }

    private static double[] buildBaselineDemand(List<PeriodData> periods) {
        int jSize = periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (PeriodData p : periods) {
            for (int j = 0; j < jSize; j++) {
                sum[j] += p.demandSum[j];
            }
        }
        double denom = Math.max(1, periods.size());
        for (int j = 0; j < jSize; j++) {
            sum[j] /= denom;
        }
        return sum;
    }

    private static double computeTrainWindowScenarioUbMax(List<Sample> samplesForK,
                                                          int testPeriodIdx,
                                                          int k,
                                                          ProcurementParams params,
                                                          double topBetaPenalty) {
        int testSampleIdx = testPeriodIdx - k;
        int start = testSampleIdx - W;
        int end = testSampleIdx;
        if (start < 0 || end > samplesForK.size()) {
            throw new IllegalArgumentException("Invalid training window for testPeriod=" + testPeriodIdx + ", k=" + k);
        }
        double ubMax = Double.NEGATIVE_INFINITY;
        for (int idx = start; idx < end; idx++) {
            double[] d = samplesForK.get(idx).demand();
            double spotOnly = 0.0;
            for (int j = 0; j < params.J; j++) {
                spotOnly += params.e[j] * d[j];
            }
            ubMax = Math.max(ubMax, spotOnly + topBetaPenalty);
        }
        return ubMax;
    }

    private static double topBetaPenalty(ProcurementParams p) {
        double[] vals = new double[p.I];
        for (int i = 0; i < p.I; i++) {
            vals[i] = p.h[i] * p.p[i];
        }
        java.util.Arrays.sort(vals);
        double sum = 0.0;
        int take = Math.min(p.beta, vals.length);
        for (int t = 0; t < take; t++) {
            sum += vals[vals.length - 1 - t];
        }
        return sum;
    }

    private static Map<String, Integer> headerMap(String[] header) {
        Map<String, Integer> h = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            h.put(normalize(header[i]), i);
        }
        return h;
    }

    private static int require(Map<String, Integer> h, String name) {
        Integer idx = h.get(normalize(name));
        if (idx == null) {
            throw new IllegalArgumentException("Missing column: " + name);
        }
        return idx;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace("\ufeff", "").trim();
    }

    private static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static String toCsvLine(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            String s = fields.get(i);
            if (s == null) s = "";
            sb.append('"').append(s.replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "Inf" : "-Inf";
        return String.format(Locale.US, "%.10f", v);
    }

    private static double parseDoubleSafe(String s) {
        if (s == null || s.isBlank()) return Double.NaN;
        return Double.parseDouble(s);
    }

    private static String stripCsvSuffix(String name) {
        if (name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }
}
