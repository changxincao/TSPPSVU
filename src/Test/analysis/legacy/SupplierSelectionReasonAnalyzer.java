package Test.analysis.legacy;

import Basic.HistoricalDay;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.CsvHistoryLoader;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.calculateHelper.KernelType;

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
 * 供应商选择稳定性原因分析器。
 *
 * 这个类聚焦 `k1=2 + SAA` 的 global trial 结果，分析为什么某些供应商会在不同 trial 中反复被选中。
 * 它会把 trial 级选择结果与需求大头线路、线路低价供应商、供应商主导线路等信息拼接起来，
 * 输出：供应商入选频率、组合模式、已选供应商解释、主导大头线路明细和文字结论。
 */
public class SupplierSelectionReasonAnalyzer {

    private static final Locale US = Locale.US;

    public static void main(String[] args) throws Exception {
        Path historyCsv = Paths.get(args.length > 0 ? args[0] : "./fFreight_with_indicators_and_holidays.csv");
        Path globalCsv = Paths.get(args.length > 1 ? args[1] : "./global_trials_20.csv");
        Path headCsv = Paths.get(args.length > 2 ? args[2] : "./analysis/滚动试验_需求大头明细.csv");
        Path outDir = Paths.get(args.length > 3 ? args[3] : "./analysis");
        int numCarriers = (args.length > 4 ? Integer.parseInt(args[4]) : 20);

        Files.createDirectories(outDir);

        Config cfg = buildConfigK1_2();
        InstanceGenerator.GenConfig genCfg = buildGenConfig();

        CsvHistoryLoader.HistoryLoadResult hist = CsvHistoryLoader.load(historyCsv, cfg);
        SampleBuilder.BuildResult br = SampleBuilder.build(hist.days, hist.laneNames, cfg);
        ProcurementParams params = InstanceGenerator.generate(numCarriers, buildBaselineDemand(br), genCfg, cfg);

        double[] totalDemand = buildTotalDemand(hist.days, hist.laneNames.size());
        LineRankStats[] lineRankStats = buildLineRankStats(params, totalDemand);

        List<GlobalTrialRow> trialRows = readGlobalTrials(globalCsv);
        List<HeadLineRow> headRows = readHeadRows(headCsv);

        Map<String, List<HeadLineRow>> headByTrial = groupHeadRows(headRows);
        List<GlobalTrialRow> filteredTrials = filterK1_2SAA(trialRows, headByTrial);

        Path trialExplainCsv = outDir.resolve("试验内已选供应商解释_k1=2_SAA.csv");
        Path supplierSummaryCsv = outDir.resolve("供应商选择稳定性汇总_k1=2_SAA.csv");
        Path patternCsv = outDir.resolve("供应商组合模式_k1=2_SAA.csv");
        Path supplierHeadLineCsv = outDir.resolve("供应商主导大头线路明细_k1=2_SAA.csv");
        Path summaryTxt = outDir.resolve("供应商选择稳定性分析结论_k1=2_SAA.txt");

        AnalysisResult result = analyze(filteredTrials, headByTrial, lineRankStats, params);
        writeTrialExplainCsv(trialExplainCsv, result.trialSupplierRows);
        writeSupplierSummaryCsv(supplierSummaryCsv, result.supplierRows);
        writePatternCsv(patternCsv, result.patternRows);
        writeSupplierHeadLineCsv(supplierHeadLineCsv, result.supplierHeadLineRows);
        writeSummaryTxt(summaryTxt, result);

        System.out.println("[DONE] " + trialExplainCsv.toAbsolutePath());
        System.out.println("[DONE] " + supplierSummaryCsv.toAbsolutePath());
        System.out.println("[DONE] " + patternCsv.toAbsolutePath());
        System.out.println("[DONE] " + supplierHeadLineCsv.toAbsolutePath());
        System.out.println("[DONE] " + summaryTxt.toAbsolutePath());
    }

    private static Config buildConfigK1_2() {
        Config cfg = new Config();
        cfg.fillMissingDates = true;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = 2;
        cfg.kernelType = KernelType.EXPONENTIAL;
        cfg.standardizeTheta = true;
        cfg.bandwidthH = 1.0;
        cfg.featureFlags.includeLagDemand = true;
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
            for (int j = 0; j < jSize; j++) {
                sum[j] += p.demandSum[j];
            }
        }
        double denom = Math.max(1, br.periods.size());
        for (int j = 0; j < jSize; j++) {
            sum[j] /= denom;
        }
        return sum;
    }

    private static double[] buildTotalDemand(List<HistoricalDay> days, int jSize) {
        double[] total = new double[jSize];
        for (HistoricalDay d : days) {
            for (int j = 0; j < jSize; j++) {
                total[j] += d.laneDemand[j];
            }
        }
        return total;
    }

    private static LineRankStats[] buildLineRankStats(ProcurementParams params, double[] totalDemand) {
        int jSize = params.J;
        LineRankStats[] out = new LineRankStats[jSize];
        double grand = 0.0;
        for (double v : totalDemand) grand += v;

        Integer[] demandOrder = new Integer[jSize];
        for (int j = 0; j < jSize; j++) demandOrder[j] = j;
        Arrays.sort(demandOrder, Comparator.comparingDouble((Integer j) -> totalDemand[j]).reversed());
        int[] demandRank = new int[jSize];
        for (int r = 0; r < jSize; r++) demandRank[demandOrder[r]] = r + 1;

        for (int j = 0; j < jSize; j++) {
            List<SupplierCost> supplierCosts = new ArrayList<>();
            for (int i = 0; i < params.I; i++) {
                if (!params.eligible[i][j]) continue;
                supplierCosts.add(new SupplierCost(i, params.carriers.get(i), params.r[i][j]));
            }
            supplierCosts.sort(Comparator.comparingDouble(sc -> sc.cost));

            Map<String, Integer> rankBySupplier = new HashMap<>();
            Map<String, Double> costBySupplier = new HashMap<>();
            List<String> orderedSuppliers = new ArrayList<>();
            for (int r = 0; r < supplierCosts.size(); r++) {
                SupplierCost sc = supplierCosts.get(r);
                rankBySupplier.put(sc.supplier, r + 1);
                costBySupplier.put(sc.supplier, sc.cost);
                orderedSuppliers.add(sc.supplier);
            }

            double bestCost = supplierCosts.isEmpty() ? Double.NaN : supplierCosts.get(0).cost;
            out[j] = new LineRankStats(
                    totalDemand[j],
                    grand > 1e-12 ? totalDemand[j] / grand : 0.0,
                    demandRank[j],
                    bestCost,
                    rankBySupplier,
                    costBySupplier,
                    orderedSuppliers
            );
        }
        return out;
    }

    private static List<GlobalTrialRow> filterK1_2SAA(List<GlobalTrialRow> rows, Map<String, List<HeadLineRow>> headByTrial) {
        List<GlobalTrialRow> out = new ArrayList<>();
        for (GlobalTrialRow row : rows) {
            if (!"SAA".equals(row.solveMode)) continue;
            if (row.k1Lag != 2) continue;
            if (!headByTrial.containsKey(trialKey(row.trialId, row.testIdx))) continue;
            out.add(row);
        }
        return out;
    }

    private static AnalysisResult analyze(List<GlobalTrialRow> trials,
                                          Map<String, List<HeadLineRow>> headByTrial,
                                          LineRankStats[] lineRankStats,
                                          ProcurementParams params) {
        Map<String, SupplierAgg> supplierAgg = new LinkedHashMap<>();
        for (String carrier : params.carriers) supplierAgg.put(carrier, new SupplierAgg(carrier));

        Map<String, PatternAgg> patternAgg = new LinkedHashMap<>();
        Map<String, SupplierHeadLineAgg> supplierHeadLineAgg = new LinkedHashMap<>();
        List<TrialSupplierExplainRow> trialSupplierRows = new ArrayList<>();

        for (GlobalTrialRow trial : trials) {
            List<HeadLineRow> headLines = headByTrial.get(trialKey(trial.trialId, trial.testIdx));
            Set<String> selected = parseSelectedCarriers(trial.selectedCarriers);

            List<String> sortedSelected = new ArrayList<>(selected);
            Collections.sort(sortedSelected);
            String pattern = String.join("|", sortedSelected);
            PatternAgg pAgg = patternAgg.computeIfAbsent(pattern, k -> new PatternAgg(pattern));
            pAgg.count++;
            pAgg.realizedObjSum += trial.realizedObj;

            for (String supplier : selected) {
                SupplierAgg agg = supplierAgg.get(supplier);
                agg.selectedTrials++;

                int bestHeadLineCount = 0;
                int top3HeadLineCount = 0;
                double bestHeadDemandShare = 0.0;
                double top3HeadDemandShare = 0.0;
                double sumRank = 0.0;
                double sumGap = 0.0;
                int cnt = 0;

                for (HeadLineRow line : headLines) {
                    LineRankStats stats = lineRankStats[line.lineIdx];
                    Integer rank = stats.rankBySupplier.get(supplier);
                    Double cost = stats.costBySupplier.get(supplier);
                    if (rank == null || cost == null) continue;

                    cnt++;
                    sumRank += rank;
                    sumGap += (stats.bestCost > 1e-12 ? (cost - stats.bestCost) / stats.bestCost : 0.0);

                    if (rank == 1) {
                        bestHeadLineCount++;
                        bestHeadDemandShare += line.share;
                    }
                    if (rank <= 3) {
                        top3HeadLineCount++;
                        top3HeadDemandShare += line.share;
                    }

                    String aggKey = supplier + "_" + line.lineIdx;
                    SupplierHeadLineAgg shAgg = supplierHeadLineAgg.computeIfAbsent(
                            aggKey, k -> new SupplierHeadLineAgg(supplier, line.lineIdx, stats.demandRank, stats.demandShare));
                    shAgg.headAppearCount++;
                    if (rank == 1) {
                        shAgg.bestCount++;
                        shAgg.bestShareSum += line.share;
                    }
                    if (rank <= 3) {
                        shAgg.top3Count++;
                        shAgg.top3ShareSum += line.share;
                    }
                }

                if (bestHeadLineCount > 0) agg.selectedAndBestOnHeadTrials++;
                else agg.selectedWithoutBestOnHeadTrials++;
                if (top3HeadLineCount > 0) agg.selectedAndTop3OnHeadTrials++;

                agg.bestHeadLineCountSum += bestHeadLineCount;
                agg.bestHeadDemandShareSum += bestHeadDemandShare;
                agg.top3HeadLineCountSum += top3HeadLineCount;
                agg.top3HeadDemandShareSum += top3HeadDemandShare;
                agg.avgRankNumer += (cnt > 0 ? sumRank / cnt : 0.0);
                agg.avgGapNumer += (cnt > 0 ? sumGap / cnt : 0.0);

                trialSupplierRows.add(new TrialSupplierExplainRow(
                        trial.trialId, trial.testIdx, supplier, headLines.size(),
                        bestHeadLineCount, bestHeadDemandShare,
                        top3HeadLineCount, top3HeadDemandShare,
                        cnt > 0 ? sumRank / cnt : Double.NaN,
                        cnt > 0 ? sumGap / cnt : Double.NaN,
                        cnt == 0 || top3HeadLineCount == 0 ? "是" : "否"
                ));
            }
        }

        List<SupplierSummaryRow> supplierRows = new ArrayList<>();
        int totalTrials = trials.size();
        for (SupplierAgg agg : supplierAgg.values()) {
            if (agg.selectedTrials == 0) continue;
            supplierRows.add(new SupplierSummaryRow(
                    agg.supplier,
                    agg.selectedTrials,
                    totalTrials > 0 ? (double) agg.selectedTrials / totalTrials : 0.0,
                    agg.selectedAndBestOnHeadTrials,
                    agg.selectedWithoutBestOnHeadTrials,
                    agg.selectedAndTop3OnHeadTrials,
                    (double) agg.bestHeadLineCountSum / agg.selectedTrials,
                    agg.bestHeadDemandShareSum / agg.selectedTrials,
                    (double) agg.top3HeadLineCountSum / agg.selectedTrials,
                    agg.top3HeadDemandShareSum / agg.selectedTrials,
                    agg.avgRankNumer / agg.selectedTrials,
                    agg.avgGapNumer / agg.selectedTrials
            ));
        }
        supplierRows.sort(Comparator.comparingInt((SupplierSummaryRow r) -> r.selectedTrials).reversed());

        List<PatternSummaryRow> patternRows = new ArrayList<>();
        List<PatternAgg> patterns = new ArrayList<>(patternAgg.values());
        patterns.sort(Comparator.comparingInt((PatternAgg p) -> p.count).reversed());
        for (PatternAgg p : patterns) {
            patternRows.add(new PatternSummaryRow(
                    patternRows.size() + 1,
                    p.pattern,
                    p.count,
                    totalTrials > 0 ? (double) p.count / totalTrials : 0.0,
                    p.realizedObjSum / p.count
            ));
        }

        List<SupplierHeadLineSummaryRow> supplierHeadLineRows = new ArrayList<>();
        for (SupplierHeadLineAgg agg : supplierHeadLineAgg.values()) {
            supplierHeadLineRows.add(new SupplierHeadLineSummaryRow(
                    agg.supplier, agg.lineIdx, agg.lineDemandRank, agg.lineDemandShare,
                    agg.headAppearCount, agg.bestCount, agg.bestShareSum, agg.top3Count, agg.top3ShareSum
            ));
        }
        supplierHeadLineRows.sort(Comparator
                .comparingInt((SupplierHeadLineSummaryRow r) -> r.bestCount).reversed()
                .thenComparingDouble((SupplierHeadLineSummaryRow r) -> r.bestShareSum).reversed());

        return new AnalysisResult(totalTrials, supplierRows, trialSupplierRows, patternRows, supplierHeadLineRows);
    }

    private static void writeTrialExplainCsv(Path outFile, List<TrialSupplierExplainRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("trial编号,test编号,供应商,该trial大头线路数,该供应商是最低成本的大头线路数,该供应商作为最低成本覆盖的大头需求占比,该供应商进入成本前三的大头线路数,该供应商进入成本前三覆盖的大头需求占比,该供应商在大头线路上的平均成本排名,该供应商相对最低成本的平均溢价比例,是否从未进入任何大头线路成本前三");
            bw.newLine();
            for (TrialSupplierExplainRow r : rows) {
                bw.write(String.format(US, "%d,%d,%s,%d,%d,%.10f,%d,%.10f,%.10f,%.10f,%s%n",
                        r.trialId, r.testIdx, r.supplier, r.headLineCount,
                        r.bestHeadLineCount, r.bestHeadDemandShare,
                        r.top3HeadLineCount, r.top3HeadDemandShare,
                        r.avgHeadRank, r.avgGapToBest, r.neverTop3));
            }
        }
    }

    private static void writeSupplierSummaryCsv(Path outFile, List<SupplierSummaryRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("供应商,入选Trial数,入选频率,入选且至少主导1条大头线路的trial数,入选但未主导任何大头线路的trial数,入选且至少进入1条大头线路成本前三的trial数,入选时平均主导大头线路数,入选时平均主导大头需求占比,入选时平均进入成本前三的大头线路数,入选时平均进入成本前三覆盖的大头需求占比,入选时在大头线路上的平均成本排名,入选时相对最低成本的平均溢价比例");
            bw.newLine();
            for (SupplierSummaryRow r : rows) {
                bw.write(String.format(US, "%s,%d,%.10f,%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                        r.supplier, r.selectedTrials, r.selectedFreq,
                        r.selectedAndBestOnHeadTrials, r.selectedWithoutBestOnHeadTrials, r.selectedAndTop3OnHeadTrials,
                        r.avgBestHeadLineCount, r.avgBestHeadDemandShare,
                        r.avgTop3HeadLineCount, r.avgTop3HeadDemandShare,
                        r.avgHeadRank, r.avgGapToBest));
            }
        }
    }

    private static void writePatternCsv(Path outFile, List<PatternSummaryRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("模式排名,供应商组合,出现次数,出现频率,该组合平均样本外目标值");
            bw.newLine();
            for (PatternSummaryRow r : rows) {
                bw.write(String.format(US, "%d,%s,%d,%.10f,%.10f%n",
                        r.rank, csvSafe(r.pattern), r.count, r.freq, r.avgRealizedObj));
            }
        }
    }

    private static void writeSupplierHeadLineCsv(Path outFile, List<SupplierHeadLineSummaryRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("供应商,lineIdx,line需求排名(总量降序),line需求占比,该line进入大头次数,该供应商作为该line最低成本的次数,该供应商作为该line最低成本累计覆盖的大头需求占比,该供应商进入该line成本前三的次数,该供应商进入该line成本前三累计覆盖的大头需求占比");
            bw.newLine();
            for (SupplierHeadLineSummaryRow r : rows) {
                bw.write(String.format(US, "%s,%d,%d,%.10f,%d,%d,%.10f,%d,%.10f%n",
                        r.supplier, r.lineIdx, r.lineDemandRank, r.lineDemandShare, r.headAppearCount,
                        r.bestCount, r.bestShareSum, r.top3Count, r.top3ShareSum));
            }
        }
    }

    private static void writeSummaryTxt(Path outFile, AnalysisResult result) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("k1=2 + SAA 供应商选择稳定性分析结论");
            bw.newLine();
            bw.write("总trial数: " + result.totalTrials);
            bw.newLine();

            if (!result.patternRows.isEmpty()) {
                PatternSummaryRow top = result.patternRows.get(0);
                bw.write("最常见组合: " + top.pattern + "，出现 " + top.count + " 次，占比 "
                        + String.format(US, "%.4f", top.freq));
                bw.newLine();
            }

            bw.write("高频供应商优先看 3 个信号:");
            bw.newLine();
            bw.write("1) 入选频率是否高");
            bw.newLine();
            bw.write("2) 是否经常主导大头线路（最低成本）");
            bw.newLine();
            bw.write("3) 即使不主导，是否至少经常进入大头线路成本前三");
            bw.newLine();
            bw.write("如果某供应商入选频率高，但主导/前三覆盖都低，说明它更可能是约束补位或保险位，而非价格主力。");
            bw.newLine();
        }
    }
    private static Map<String, List<HeadLineRow>> groupHeadRows(List<HeadLineRow> rows) {
        Map<String, List<HeadLineRow>> out = new HashMap<>();
        for (HeadLineRow row : rows) {
            if (row.k1Lag != 2) continue;
            out.computeIfAbsent(trialKey(row.trialId, row.testIdx), k -> new ArrayList<>()).add(row);
        }
        return out;
    }

    private static String trialKey(int trialId, int testIdx) {
        return trialId + "_" + testIdx;
    }

    private static List<GlobalTrialRow> readGlobalTrials(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<GlobalTrialRow> out = new ArrayList<>();
        if (lines.isEmpty()) return out;
        Map<String, Integer> idx = headerIndex(parseCsvLine(lines.get(0)));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> f = parseCsvLine(lines.get(i));
            out.add(new GlobalTrialRow(
                    val(f, idx, "tag"),
                    val(f, idx, "solveMode"),
                    parseInt(val(f, idx, "k1Lag")),
                    parseInt(val(f, idx, "trialId")),
                    parseInt(val(f, idx, "testIdx")),
                    parseDouble(val(f, idx, "realizedObj")),
                    val(f, idx, "selectedCarriers")
            ));
        }
        return out;
    }

    private static List<HeadLineRow> readHeadRows(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<HeadLineRow> out = new ArrayList<>();
        if (lines.isEmpty()) return out;
        Map<String, Integer> idx = headerIndex(parseCsvLine(lines.get(0)));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> f = parseCsvLine(lines.get(i));
            out.add(new HeadLineRow(
                    parseInt(val(f, idx, "k1Lag")),
                    parseInt(val(f, idx, "trialId")),
                    parseInt(val(f, idx, "testIdx")),
                    parseInt(val(f, idx, "lineIdx")),
                    parseDouble(val(f, idx, "share")),
                    val(f, idx, "bestSupplier")
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

    private static String csvSafe(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("|")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static class GlobalTrialRow {
        final String tag;
        final String solveMode;
        final int k1Lag;
        final int trialId;
        final int testIdx;
        final double realizedObj;
        final String selectedCarriers;

        GlobalTrialRow(String tag, String solveMode, int k1Lag, int trialId, int testIdx,
                       double realizedObj, String selectedCarriers) {
            this.tag = tag;
            this.solveMode = solveMode;
            this.k1Lag = k1Lag;
            this.trialId = trialId;
            this.testIdx = testIdx;
            this.realizedObj = realizedObj;
            this.selectedCarriers = selectedCarriers;
        }
    }

    private static class HeadLineRow {
        final int k1Lag;
        final int trialId;
        final int testIdx;
        final int lineIdx;
        final double share;
        final String bestSupplier;

        HeadLineRow(int k1Lag, int trialId, int testIdx, int lineIdx, double share, String bestSupplier) {
            this.k1Lag = k1Lag;
            this.trialId = trialId;
            this.testIdx = testIdx;
            this.lineIdx = lineIdx;
            this.share = share;
            this.bestSupplier = bestSupplier;
        }
    }

    private static class SupplierCost {
        final int supplierIdx;
        final String supplier;
        final double cost;

        SupplierCost(int supplierIdx, String supplier, double cost) {
            this.supplierIdx = supplierIdx;
            this.supplier = supplier;
            this.cost = cost;
        }
    }

    private static class LineRankStats {
        final double totalDemand;
        final double demandShare;
        final int demandRank;
        final double bestCost;
        final Map<String, Integer> rankBySupplier;
        final Map<String, Double> costBySupplier;
        final List<String> orderedSuppliers;

        LineRankStats(double totalDemand, double demandShare, int demandRank, double bestCost,
                      Map<String, Integer> rankBySupplier, Map<String, Double> costBySupplier,
                      List<String> orderedSuppliers) {
            this.totalDemand = totalDemand;
            this.demandShare = demandShare;
            this.demandRank = demandRank;
            this.bestCost = bestCost;
            this.rankBySupplier = rankBySupplier;
            this.costBySupplier = costBySupplier;
            this.orderedSuppliers = orderedSuppliers;
        }
    }

    private static class SupplierAgg {
        final String supplier;
        int selectedTrials;
        int selectedAndBestOnHeadTrials;
        int selectedWithoutBestOnHeadTrials;
        int selectedAndTop3OnHeadTrials;
        int bestHeadLineCountSum;
        double bestHeadDemandShareSum;
        int top3HeadLineCountSum;
        double top3HeadDemandShareSum;
        double avgRankNumer;
        double avgGapNumer;

        SupplierAgg(String supplier) {
            this.supplier = supplier;
        }
    }

    private static class PatternAgg {
        final String pattern;
        int count;
        double realizedObjSum;

        PatternAgg(String pattern) {
            this.pattern = pattern;
        }
    }

    private static class SupplierHeadLineAgg {
        final String supplier;
        final int lineIdx;
        final int lineDemandRank;
        final double lineDemandShare;
        int headAppearCount;
        int bestCount;
        double bestShareSum;
        int top3Count;
        double top3ShareSum;

        SupplierHeadLineAgg(String supplier, int lineIdx, int lineDemandRank, double lineDemandShare) {
            this.supplier = supplier;
            this.lineIdx = lineIdx;
            this.lineDemandRank = lineDemandRank;
            this.lineDemandShare = lineDemandShare;
        }
    }

    private static class TrialSupplierExplainRow {
        final int trialId;
        final int testIdx;
        final String supplier;
        final int headLineCount;
        final int bestHeadLineCount;
        final double bestHeadDemandShare;
        final int top3HeadLineCount;
        final double top3HeadDemandShare;
        final double avgHeadRank;
        final double avgGapToBest;
        final String neverTop3;

        TrialSupplierExplainRow(int trialId, int testIdx, String supplier, int headLineCount,
                                int bestHeadLineCount, double bestHeadDemandShare,
                                int top3HeadLineCount, double top3HeadDemandShare,
                                double avgHeadRank, double avgGapToBest, String neverTop3) {
            this.trialId = trialId;
            this.testIdx = testIdx;
            this.supplier = supplier;
            this.headLineCount = headLineCount;
            this.bestHeadLineCount = bestHeadLineCount;
            this.bestHeadDemandShare = bestHeadDemandShare;
            this.top3HeadLineCount = top3HeadLineCount;
            this.top3HeadDemandShare = top3HeadDemandShare;
            this.avgHeadRank = avgHeadRank;
            this.avgGapToBest = avgGapToBest;
            this.neverTop3 = neverTop3;
        }
    }

    private static class SupplierSummaryRow {
        final String supplier;
        final int selectedTrials;
        final double selectedFreq;
        final int selectedAndBestOnHeadTrials;
        final int selectedWithoutBestOnHeadTrials;
        final int selectedAndTop3OnHeadTrials;
        final double avgBestHeadLineCount;
        final double avgBestHeadDemandShare;
        final double avgTop3HeadLineCount;
        final double avgTop3HeadDemandShare;
        final double avgHeadRank;
        final double avgGapToBest;

        SupplierSummaryRow(String supplier, int selectedTrials, double selectedFreq,
                           int selectedAndBestOnHeadTrials, int selectedWithoutBestOnHeadTrials,
                           int selectedAndTop3OnHeadTrials, double avgBestHeadLineCount,
                           double avgBestHeadDemandShare, double avgTop3HeadLineCount,
                           double avgTop3HeadDemandShare, double avgHeadRank, double avgGapToBest) {
            this.supplier = supplier;
            this.selectedTrials = selectedTrials;
            this.selectedFreq = selectedFreq;
            this.selectedAndBestOnHeadTrials = selectedAndBestOnHeadTrials;
            this.selectedWithoutBestOnHeadTrials = selectedWithoutBestOnHeadTrials;
            this.selectedAndTop3OnHeadTrials = selectedAndTop3OnHeadTrials;
            this.avgBestHeadLineCount = avgBestHeadLineCount;
            this.avgBestHeadDemandShare = avgBestHeadDemandShare;
            this.avgTop3HeadLineCount = avgTop3HeadLineCount;
            this.avgTop3HeadDemandShare = avgTop3HeadDemandShare;
            this.avgHeadRank = avgHeadRank;
            this.avgGapToBest = avgGapToBest;
        }
    }

    private static class PatternSummaryRow {
        final int rank;
        final String pattern;
        final int count;
        final double freq;
        final double avgRealizedObj;

        PatternSummaryRow(int rank, String pattern, int count, double freq, double avgRealizedObj) {
            this.rank = rank;
            this.pattern = pattern;
            this.count = count;
            this.freq = freq;
            this.avgRealizedObj = avgRealizedObj;
        }
    }

    private static class SupplierHeadLineSummaryRow {
        final String supplier;
        final int lineIdx;
        final int lineDemandRank;
        final double lineDemandShare;
        final int headAppearCount;
        final int bestCount;
        final double bestShareSum;
        final int top3Count;
        final double top3ShareSum;

        SupplierHeadLineSummaryRow(String supplier, int lineIdx, int lineDemandRank, double lineDemandShare,
                                   int headAppearCount, int bestCount, double bestShareSum,
                                   int top3Count, double top3ShareSum) {
            this.supplier = supplier;
            this.lineIdx = lineIdx;
            this.lineDemandRank = lineDemandRank;
            this.lineDemandShare = lineDemandShare;
            this.headAppearCount = headAppearCount;
            this.bestCount = bestCount;
            this.bestShareSum = bestShareSum;
            this.top3Count = top3Count;
            this.top3ShareSum = top3ShareSum;
        }
    }

    private static class AnalysisResult {
        final int totalTrials;
        final List<SupplierSummaryRow> supplierRows;
        final List<TrialSupplierExplainRow> trialSupplierRows;
        final List<PatternSummaryRow> patternRows;
        final List<SupplierHeadLineSummaryRow> supplierHeadLineRows;

        AnalysisResult(int totalTrials, List<SupplierSummaryRow> supplierRows,
                       List<TrialSupplierExplainRow> trialSupplierRows,
                       List<PatternSummaryRow> patternRows,
                       List<SupplierHeadLineSummaryRow> supplierHeadLineRows) {
            this.totalTrials = totalTrials;
            this.supplierRows = supplierRows;
            this.trialSupplierRows = trialSupplierRows;
            this.patternRows = patternRows;
            this.supplierHeadLineRows = supplierHeadLineRows;
        }
    }
}


