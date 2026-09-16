package Test.analysis.legacy;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.CsvHistoryLoader;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.calculateHelper.KernelType;
import Test.ExperimentBuilder;
import Test.ExperimentBatches;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 单样本需求与选择一致性分析器。
 *
 * 这个类读取 `single_sample_det_merged.csv`，在每个 rolling trial 内逐个 sample 重建其需求，
 * 然后比较三件事是否稳定：
 * 1. 大头需求线路集合是否稳定；
 * 2. 大头线路对应的最低成本供应商集合是否稳定；
 * 3. single-sample 求解得到的已选供应商集合是否稳定。
 *
 * 它主要用于判断：sample 间变化到底改变了“需求大头”，还是只是改变了同一批低价供应商之间的权重。
 */
public class SingleSampleDemandSelectionAnalyzer {

    private static final Locale US = Locale.US;

    public static void main(String[] args) throws Exception {
        Path historyCsv = Paths.get(args.length > 0 ? args[0] : "./fFreight_with_indicators_and_holidays.csv");
        Path singleCsv = Paths.get(args.length > 1 ? args[1] : "./single_sample_det_merged.csv");
        Path outDir = Paths.get(args.length > 2 ? args[2] : "./analysis");
        int k1Target = (args.length > 3 ? Integer.parseInt(args[3]) : 2);
        double topShare = (args.length > 4 ? Double.parseDouble(args[4]) : 0.8);
        int numCarriers = (args.length > 5 ? Integer.parseInt(args[5]) : 15);
        int windowW = (args.length > 6 ? Integer.parseInt(args[6]) : 24);

        Files.createDirectories(outDir);

        Config cfg = buildConfig(k1Target);
        InstanceGenerator.GenConfig genCfg = buildGenConfig();
        CsvHistoryLoader.HistoryLoadResult hist = CsvHistoryLoader.load(historyCsv, cfg);
        SampleBuilder.BuildResult br = SampleBuilder.build(hist.days, hist.laneNames, cfg);
        ProcurementParams params = InstanceGenerator.generate(numCarriers, buildBaselineDemand(br), genCfg, cfg);
        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, windowW);

        List<SingleSampleRow> sourceRows = readSingleSampleRows(singleCsv, k1Target);
        AnalysisResult result = analyze(sourceRows, rolling, params, hist.laneNames, topShare);

        Path sampleCsv = outDir.resolve("单样本_样本内大头需求与选择一致性_k1=" + k1Target + ".csv");
        Path trialCsv = outDir.resolve("单样本_试验内稳定性汇总_k1=" + k1Target + ".csv");
        Path summaryTxt = outDir.resolve("单样本_分析结论_k1=" + k1Target + ".txt");

        writeSampleCsv(sampleCsv, result.sampleRows);
        writeTrialCsv(trialCsv, result.trialRows);
        writeSummaryTxt(summaryTxt, result);

        System.out.println("[DONE] " + sampleCsv.toAbsolutePath());
        System.out.println("[DONE] " + trialCsv.toAbsolutePath());
        System.out.println("[DONE] " + summaryTxt.toAbsolutePath());
    }

    private static Config buildConfig(int k1) {
        Config cfg = new Config();
        cfg.fillMissingDates = true;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = k1;
        cfg.kernelType = KernelType.EXPONENTIAL;
        cfg.standardizeTheta = false;
        cfg.bandwidthH = 1.0;
        cfg.featureFlags.includeLagDemand = (k1 > 0);
        cfg.featureFlags.includeHolidayCount = true;
        cfg.featureFlags.includeFreightIndex = true;
        cfg.featureFlags.includeConsumptionIndex = true;
        cfg.featureFlags.includeWEIIndex = true;
        return cfg;
    }

    private static InstanceGenerator.GenConfig buildGenConfig() {
        InstanceGenerator.GenConfig g = new InstanceGenerator.GenConfig();
        g.rBarLow = 20.0;
        g.rBarHigh = 100.0;
        g.tauLow = 0.05;
        g.tauHigh = 0.30;
        g.mqcLow = 0.1;
        g.mqcHigh = 0.2;
        g.coverAllLanes = true;
        g.capacityMode = InstanceGenerator.CapacityMode.TIGHT;
        return g;
    }

    private static double[] buildBaselineDemand(SampleBuilder.BuildResult br) {
        int jSize = br.periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (PeriodData p : br.periods) {
            for (int j = 0; j < jSize; j++) sum[j] += p.demandSum[j];
        }
        double denom = Math.max(1, br.periods.size());
        for (int j = 0; j < jSize; j++) sum[j] /= denom;
        return sum;
    }

    private static AnalysisResult analyze(List<SingleSampleRow> sourceRows,
                                          ExperimentBatches rolling,
                                          ProcurementParams params,
                                          List<String> laneNames,
                                          double topShare) {
        List<SampleAnalysisRow> sampleRows = new ArrayList<>();
        Map<String, TrialAgg> trialAggs = new LinkedHashMap<>();

        for (SingleSampleRow row : sourceRows) {
            if (row.trialId < 0 || row.trialId >= rolling.trainSets.size()) continue;
            List<Sample> train = rolling.trainSets.get(row.trialId);
            if (row.samplePos < 0 || row.samplePos >= train.size()) continue;

            Sample sample = train.get(row.samplePos);
            double[] demand = sample.demand();
            HeadDemandInfo headInfo = buildHeadDemandInfo(demand, laneNames, params, topShare);
            Set<String> selected = parseSelectedCarriers(row.selectedCarriers);

            Set<String> inter = new HashSet<>(selected);
            inter.retainAll(headInfo.bestSupplierSet);
            Set<String> union = new HashSet<>(selected);
            union.addAll(headInfo.bestSupplierSet);

            int hitHeadLines = 0;
            for (String supplier : headInfo.bestSuppliersByLine) {
                if (selected.contains(supplier)) hitHeadLines++;
            }

            SampleAnalysisRow out = new SampleAnalysisRow(
                    row.standardizeTheta,
                    row.cH,
                    row.trialId,
                    row.testIdx,
                    row.samplePos,
                    row.sampleId,
                    selected.size(),
                    headInfo.headLineCount,
                    headInfo.top1Share,
                    headInfo.bestSupplierSet.size(),
                    inter.size(),
                    safeDiv(inter.size(), selected.size()),
                    safeDiv(inter.size(), headInfo.bestSupplierSet.size()),
                    safeDiv(inter.size(), union.size()),
                    safeDiv(hitHeadLines, headInfo.headLineCount),
                    String.join("|", sortedList(selected)),
                    String.join("|", headInfo.headLineNames),
                    String.join("|", sortedList(headInfo.bestSupplierSet))
            );
            sampleRows.add(out);

            String trialKey = trialKey(row.standardizeTheta, row.cH, row.trialId, row.testIdx);
            TrialAgg agg = trialAggs.computeIfAbsent(trialKey,
                    k -> new TrialAgg(row.standardizeTheta, row.cH, row.trialId, row.testIdx));
            agg.sampleCount++;
            agg.headLineHitRateSum += out.headLineHitRate;
            agg.selectedSets.add(new HashSet<>(selected));
            agg.headLineSets.add(new HashSet<>(headInfo.headLineNames));
            agg.headSupplierSets.add(new HashSet<>(headInfo.bestSupplierSet));
            agg.selectedPatternCount.merge(String.join("|", sortedList(selected)), 1, Integer::sum);
            agg.headSupplierPatternCount.merge(String.join("|", sortedList(headInfo.bestSupplierSet)), 1, Integer::sum);
        }

        List<TrialSummaryRow> trialRows = new ArrayList<>();
        for (TrialAgg agg : trialAggs.values()) {
            trialRows.add(new TrialSummaryRow(
                    agg.standardizeTheta,
                    agg.cH,
                    agg.trialId,
                    agg.testIdx,
                    agg.sampleCount,
                    agg.selectedPatternCount.size(),
                    maxPatternShare(agg.selectedPatternCount, agg.sampleCount),
                    agg.headSupplierPatternCount.size(),
                    maxPatternShare(agg.headSupplierPatternCount, agg.sampleCount),
                    avgPairwiseJaccard(agg.selectedSets),
                    avgPairwiseJaccard(agg.headLineSets),
                    avgPairwiseJaccard(agg.headSupplierSets),
                    safeDiv(agg.headLineHitRateSum, agg.sampleCount)
            ));
        }
        trialRows.sort(Comparator.comparingInt((TrialSummaryRow r) -> r.trialId)
                .thenComparing(r -> r.standardizeTheta));

        return new AnalysisResult(sampleRows, trialRows);
    }

    private static HeadDemandInfo buildHeadDemandInfo(double[] demand, List<String> laneNames,
                                                      ProcurementParams params, double topShare) {
        Integer[] order = new Integer[demand.length];
        double total = 0.0;
        for (int j = 0; j < demand.length; j++) {
            order[j] = j;
            total += demand[j];
        }
        Arrays.sort(order, Comparator.comparingDouble((Integer j) -> demand[j]).reversed());

        List<String> headLineNames = new ArrayList<>();
        List<String> bestSuppliersByLine = new ArrayList<>();
        Set<String> bestSupplierSet = new HashSet<>();
        double cum = 0.0;
        double top1Share = (order.length > 0 && total > 1e-12) ? demand[order[0]] / total : 0.0;

        for (int rank = 0; rank < order.length; rank++) {
            int j = order[rank];
            double share = total > 1e-12 ? demand[j] / total : 0.0;
            boolean isHead = (rank == 0) || (cum < topShare);
            if (!isHead) break;
            cum += share;
            headLineNames.add(laneNames.get(j));

            String best = bestSupplierForLine(params, j);
            bestSuppliersByLine.add(best);
            bestSupplierSet.add(best);
        }

        return new HeadDemandInfo(headLineNames, bestSuppliersByLine, bestSupplierSet, headLineNames.size(), top1Share);
    }

    private static String bestSupplierForLine(ProcurementParams params, int lineIdx) {
        double best = Double.POSITIVE_INFINITY;
        String bestSupplier = "";
        for (int i = 0; i < params.I; i++) {
            if (!params.eligible[i][lineIdx]) continue;
            if (params.r[i][lineIdx] < best) {
                best = params.r[i][lineIdx];
                bestSupplier = params.carriers.get(i);
            }
        }
        return bestSupplier;
    }

    private static double avgPairwiseJaccard(List<Set<String>> sets) {
        if (sets.size() <= 1) return 1.0;
        double sum = 0.0;
        int cnt = 0;
        for (int i = 0; i < sets.size(); i++) {
            for (int j = i + 1; j < sets.size(); j++) {
                Set<String> inter = new HashSet<>(sets.get(i));
                inter.retainAll(sets.get(j));
                Set<String> union = new HashSet<>(sets.get(i));
                union.addAll(sets.get(j));
                sum += safeDiv(inter.size(), union.size());
                cnt++;
            }
        }
        return safeDiv(sum, cnt);
    }

    private static double maxPatternShare(Map<String, Integer> patternCount, int total) {
        int best = 0;
        for (int c : patternCount.values()) best = Math.max(best, c);
        return safeDiv(best, total);
    }

    private static double safeDiv(double a, double b) {
        return b == 0.0 ? 0.0 : a / b;
    }

    private static List<String> sortedList(Set<String> s) {
        List<String> out = new ArrayList<>(s);
        Collections.sort(out);
        return out;
    }

    private static String trialKey(boolean standardizeTheta, double cH, int trialId, int testIdx) {
        return standardizeTheta + "_" + String.format(US, "%.6f", cH) + "_" + trialId + "_" + testIdx;
    }

    private static Set<String> parseSelectedCarriers(String raw) {
        Set<String> out = new HashSet<>();
        String s = raw.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);
        if (s.isBlank()) return out;
        for (String p : s.split(",")) {
            String x = p.trim();
            if (x.isEmpty()) continue;
            int idx = Integer.parseInt(x);
            out.add("C" + (idx + 1));
        }
        return out;
    }

    private static List<SingleSampleRow> readSingleSampleRows(Path csv, int k1Target) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<SingleSampleRow> out = new ArrayList<>();
        if (lines.isEmpty()) return out;
        Map<String, Integer> idx = headerIndex(parseCsvLine(lines.get(0)));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> f = parseCsvLine(lines.get(i));
            int k1 = parseInt(val(f, idx, "k1Lag"));
            if (k1 != k1Target) continue;
            out.add(new SingleSampleRow(
                    parseBool(val(f, idx, "standardizeTheta")),
                    parseDouble(val(f, idx, "C_h")),
                    parseInt(val(f, idx, "trialId")),
                    parseInt(val(f, idx, "testIdx")),
                    parseInt(val(f, idx, "samplePos")),
                    parseInt(val(f, idx, "sampleId")),
                    val(f, idx, "selectedCarriers")
            ));
        }
        return out;
    }

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.size(); i++) idx.put(header.get(i), i);
        return idx;
    }

    private static String val(List<String> f, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        return (i == null || i >= f.size()) ? "" : f.get(i);
    }

    private static int parseInt(String s) {
        return Integer.parseInt(s.trim());
    }

    private static double parseDouble(String s) {
        return Double.parseDouble(s.trim());
    }

    private static boolean parseBool(String s) {
        return Boolean.parseBoolean(s.trim());
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    private static void writeSampleCsv(Path outFile, List<SampleAnalysisRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("standardizeTheta,C_h,trial编号,test编号,sample位置,sampleId,已选供应商数,大头线路数,第一大线路占比,大头最低成本供应商数,交集数,已选集合精确率,大头集合召回率,Jaccard相似度,大头线路命中率,已选供应商集合,样本内大头线路集合,样本内大头线路最低成本供应商集合");
            bw.newLine();
            for (SampleAnalysisRow r : rows) {
                bw.write(String.format(US, "%s,%.6f,%d,%d,%d,%d,%d,%d,%.10f,%d,%d,%.10f,%.10f,%.10f,%.10f,%s,%s,%s%n",
                        r.standardizeTheta, r.cH, r.trialId, r.testIdx, r.samplePos, r.sampleId,
                        r.selectedCount, r.headLineCount, r.top1Share, r.headSupplierCount, r.hitSupplierCount,
                        r.precision, r.recall, r.jaccard, r.headLineHitRate,
                        csvSafe(r.selectedCarriers), csvSafe(r.headLineNames), csvSafe(r.headSuppliers)));
            }
        }
    }

    private static void writeTrialCsv(Path outFile, List<TrialSummaryRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("standardizeTheta,C_h,trial编号,test编号,sample数,不同已选供应商组合数,最常见已选组合占比,不同大头最低成本供应商组合数,最常见大头供应商组合占比,sample间已选组合平均Jaccard,sample间大头线路平均Jaccard,sample间大头最低成本供应商平均Jaccard,trial内样本平均大头线路命中率");
            bw.newLine();
            for (TrialSummaryRow r : rows) {
                bw.write(String.format(US, "%s,%.6f,%d,%d,%d,%d,%.10f,%d,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                        r.standardizeTheta, r.cH, r.trialId, r.testIdx, r.sampleCount,
                        r.distinctSelectedPatterns, r.topSelectedPatternShare,
                        r.distinctHeadSupplierPatterns, r.topHeadSupplierPatternShare,
                        r.avgSelectedJaccard, r.avgHeadLineJaccard, r.avgHeadSupplierJaccard,
                        r.avgHeadLineHitRate));
            }
        }
    }

    private static void writeSummaryTxt(Path outFile, AnalysisResult result) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("singleSample 文件下，k1=2 的样本内需求大头与供应商选择一致性分析");
            bw.newLine();
            bw.write("样本级记录数: " + result.sampleRows.size());
            bw.newLine();
            bw.write("trial级记录数: " + result.trialRows.size());
            bw.newLine();
            bw.write("关注 4 个指标：");
            bw.newLine();
            bw.write("1) sample间已选组合平均Jaccard：看不同sample下选中供应商是否稳定");
            bw.newLine();
            bw.write("2) sample间大头线路平均Jaccard：看不同sample下需求大头是否稳定");
            bw.newLine();
            bw.write("3) sample间大头最低成本供应商平均Jaccard：看不同sample下大头线路对应低价供应商是否稳定");
            bw.newLine();
            bw.write("4) trial内样本平均大头线路命中率：看样本内选中的供应商与该sample的大头线路低价供应商重合程度");
            bw.newLine();
        }
    }

    private static String csvSafe(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("|")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static class SingleSampleRow {
        final boolean standardizeTheta;
        final double cH;
        final int trialId;
        final int testIdx;
        final int samplePos;
        final int sampleId;
        final String selectedCarriers;

        SingleSampleRow(boolean standardizeTheta, double cH, int trialId, int testIdx,
                        int samplePos, int sampleId, String selectedCarriers) {
            this.standardizeTheta = standardizeTheta;
            this.cH = cH;
            this.trialId = trialId;
            this.testIdx = testIdx;
            this.samplePos = samplePos;
            this.sampleId = sampleId;
            this.selectedCarriers = selectedCarriers;
        }
    }

    private static class HeadDemandInfo {
        final List<String> headLineNames;
        final List<String> bestSuppliersByLine;
        final Set<String> bestSupplierSet;
        final int headLineCount;
        final double top1Share;

        HeadDemandInfo(List<String> headLineNames, List<String> bestSuppliersByLine,
                       Set<String> bestSupplierSet, int headLineCount, double top1Share) {
            this.headLineNames = headLineNames;
            this.bestSuppliersByLine = bestSuppliersByLine;
            this.bestSupplierSet = bestSupplierSet;
            this.headLineCount = headLineCount;
            this.top1Share = top1Share;
        }
    }

    private static class SampleAnalysisRow {
        final boolean standardizeTheta;
        final double cH;
        final int trialId;
        final int testIdx;
        final int samplePos;
        final int sampleId;
        final int selectedCount;
        final int headLineCount;
        final double top1Share;
        final int headSupplierCount;
        final int hitSupplierCount;
        final double precision;
        final double recall;
        final double jaccard;
        final double headLineHitRate;
        final String selectedCarriers;
        final String headLineNames;
        final String headSuppliers;

        SampleAnalysisRow(boolean standardizeTheta, double cH, int trialId, int testIdx,
                          int samplePos, int sampleId, int selectedCount, int headLineCount,
                          double top1Share, int headSupplierCount, int hitSupplierCount,
                          double precision, double recall, double jaccard, double headLineHitRate,
                          String selectedCarriers, String headLineNames, String headSuppliers) {
            this.standardizeTheta = standardizeTheta;
            this.cH = cH;
            this.trialId = trialId;
            this.testIdx = testIdx;
            this.samplePos = samplePos;
            this.sampleId = sampleId;
            this.selectedCount = selectedCount;
            this.headLineCount = headLineCount;
            this.top1Share = top1Share;
            this.headSupplierCount = headSupplierCount;
            this.hitSupplierCount = hitSupplierCount;
            this.precision = precision;
            this.recall = recall;
            this.jaccard = jaccard;
            this.headLineHitRate = headLineHitRate;
            this.selectedCarriers = selectedCarriers;
            this.headLineNames = headLineNames;
            this.headSuppliers = headSuppliers;
        }
    }

    private static class TrialAgg {
        final boolean standardizeTheta;
        final double cH;
        final int trialId;
        final int testIdx;
        int sampleCount;
        double headLineHitRateSum;
        final List<Set<String>> selectedSets = new ArrayList<>();
        final List<Set<String>> headLineSets = new ArrayList<>();
        final List<Set<String>> headSupplierSets = new ArrayList<>();
        final Map<String, Integer> selectedPatternCount = new HashMap<>();
        final Map<String, Integer> headSupplierPatternCount = new HashMap<>();

        TrialAgg(boolean standardizeTheta, double cH, int trialId, int testIdx) {
            this.standardizeTheta = standardizeTheta;
            this.cH = cH;
            this.trialId = trialId;
            this.testIdx = testIdx;
        }
    }

    private static class TrialSummaryRow {
        final boolean standardizeTheta;
        final double cH;
        final int trialId;
        final int testIdx;
        final int sampleCount;
        final int distinctSelectedPatterns;
        final double topSelectedPatternShare;
        final int distinctHeadSupplierPatterns;
        final double topHeadSupplierPatternShare;
        final double avgSelectedJaccard;
        final double avgHeadLineJaccard;
        final double avgHeadSupplierJaccard;
        final double avgHeadLineHitRate;

        TrialSummaryRow(boolean standardizeTheta, double cH, int trialId, int testIdx,
                        int sampleCount, int distinctSelectedPatterns, double topSelectedPatternShare,
                        int distinctHeadSupplierPatterns, double topHeadSupplierPatternShare,
                        double avgSelectedJaccard, double avgHeadLineJaccard,
                        double avgHeadSupplierJaccard, double avgHeadLineHitRate) {
            this.standardizeTheta = standardizeTheta;
            this.cH = cH;
            this.trialId = trialId;
            this.testIdx = testIdx;
            this.sampleCount = sampleCount;
            this.distinctSelectedPatterns = distinctSelectedPatterns;
            this.topSelectedPatternShare = topSelectedPatternShare;
            this.distinctHeadSupplierPatterns = distinctHeadSupplierPatterns;
            this.topHeadSupplierPatternShare = topHeadSupplierPatternShare;
            this.avgSelectedJaccard = avgSelectedJaccard;
            this.avgHeadLineJaccard = avgHeadLineJaccard;
            this.avgHeadSupplierJaccard = avgHeadSupplierJaccard;
            this.avgHeadLineHitRate = avgHeadLineHitRate;
        }
    }

    private static class AnalysisResult {
        final List<SampleAnalysisRow> sampleRows;
        final List<TrialSummaryRow> trialRows;

        AnalysisResult(List<SampleAnalysisRow> sampleRows, List<TrialSummaryRow> trialRows) {
            this.sampleRows = sampleRows;
            this.trialRows = trialRows;
        }
    }
}


