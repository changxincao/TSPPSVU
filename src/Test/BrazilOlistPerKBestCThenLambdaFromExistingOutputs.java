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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * New CV variant:
 * - For each outer trial:
 *   - For each k, select the best C_h (from existing stage-1 outputs).
 *   - For that (k, bestC_h), select best lambda (reuse existing stage-2 if available; otherwise solve).
 *
 * Intended usage: point {@code inRoot} at an experiment directory that already contains
 * {@code cv_stage1_k_c_candidates.csv} and (optionally) {@code cv_stage2_lambda_candidates.csv} files (possibly in chunks).
 */
public class BrazilOlistPerKBestCThenLambdaFromExistingOutputs {

    private static final int[] K_GRID = {1, 2, 3};
    private static final int SUBTRAIN_SIZE = 35;
    private static final int SUBVAL_SIZE = 15;
    private static final int K_MAX = 3;

    public static void main(String[] args) throws Exception {
        Path dailyCsv = Paths.get(args.length > 0 ? args[0]
                : "analysis/巴西数据分析/旧版/五大区合并后日度OD需求表_千克.csv");
        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 10);
        int W = (args.length > 2 ? Integer.parseInt(args[2]) : 50);
        Path inRoot = Paths.get(args.length > 3 ? args[3]
                : "analysis/purchase_raw_cv_10sup_full_4proc_live/chunks");
        Path outRoot = Paths.get(args.length > 4 ? args[4]
                : "analysis/purchase_raw_cv_perk_bestC_then_lambda");
        int startTrial = (args.length > 5 ? Integer.parseInt(args[5]) : 0);
        int maxTrials = (args.length > 6 ? Integer.parseInt(args[6]) : Integer.MAX_VALUE);

        if (W != SUBTRAIN_SIZE + SUBVAL_SIZE) {
            throw new IllegalArgumentException("This runner assumes W=35+15=50. Current W=" + W);
        }

        Files.createDirectories(outRoot);

        List<Path> stage1Files = findFilesRecursive(inRoot, "cv_stage1_k_c_candidates.csv");
        if (stage1Files.isEmpty()) {
            throw new IllegalArgumentException("No cv_stage1_k_c_candidates.csv found under: " + inRoot.toAbsolutePath());
        }
        List<Path> stage2Files = findFilesRecursive(inRoot, "cv_stage2_lambda_candidates.csv");

        Map<String, Integer> stage1Header;
        Map<TrialK, BestC> bestCByTrialK = new HashMap<>();
        Map<Integer, Integer> testPeriodByTrial = new HashMap<>();

        stage1Header = null;
        for (Path f : stage1Files) {
            Stage1LoadResult r = loadStage1BestCPerK(f, stage1Header);
            stage1Header = r.header;
            mergeBestC(bestCByTrialK, r.bestCByTrialK);
            testPeriodByTrial.putAll(r.testPeriodByTrial);
        }

        if (bestCByTrialK.isEmpty()) {
            throw new IllegalStateException("No usable stage-1 rows found in: " + inRoot.toAbsolutePath());
        }

        Map<Stage2Key, String[]> existingStage2Rows = new HashMap<>();
        Map<String, Integer> stage2Header = null;
        for (Path f : stage2Files) {
            Stage2LoadResult r = loadStage2Rows(f, stage2Header);
            stage2Header = r.header;
            existingStage2Rows.putAll(r.rowsByKey);
        }

        Path weeklyCsv = outRoot.resolve("weekly_wide.csv");
        aggregateDailyLongToWeeklyWide(dailyCsv, weeklyCsv);
        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = w.laneNames;

        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : K_GRID) {
            Config cfg = buildBaseConfig(k, 1.0);
            SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(w.periods, lanes, cfg);
            samplesByK.put(k, br.samples);
        }

        double[] dBase = buildBaselineDemand(w.periods);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, buildBaseConfig(K_MAX, 1.0));

        Path perKBestCCsv = outRoot.resolve("perk_bestC.csv");
        Path perKSelectionCsv = outRoot.resolve("perk_selected.csv");
        Path perKStage2Csv = outRoot.resolve("perk_lambda_candidates.csv");

        initIfMissingPerKBestC(perKBestCCsv);
        initIfMissingPerKSelected(perKSelectionCsv);
        initIfMissingStage2Like(perKStage2Csv);

        Map<TrialK, Boolean> alreadySelected = loadExistingSelections(perKSelectionCsv);

        int totalAlignedTrials = ExperimentBuilder.buildRolling(samplesByK.get(K_MAX), W).size();
        int endTrialExclusive = Math.min(totalAlignedTrials, startTrial + maxTrials);

        System.out.println("dailyCsv: " + dailyCsv.toAbsolutePath());
        System.out.println("inRoot: " + inRoot.toAbsolutePath());
        System.out.println("outRoot: " + outRoot.toAbsolutePath());
        System.out.println("trialRange: [" + startTrial + "," + endTrialExclusive + ")");
        System.out.println("stage1Files=" + stage1Files.size() + ", stage2Files=" + stage2Files.size());

        for (int trialId = startTrial; trialId < endTrialExclusive; trialId++) {
            Integer testPeriodIdx = testPeriodByTrial.get(trialId);
            if (testPeriodIdx == null) continue;
            for (int k : K_GRID) {
                TrialK tk = new TrialK(trialId, k);
                if (alreadySelected.containsKey(tk)) continue;
                BestC bestC = bestCByTrialK.get(tk);
                if (bestC == null) continue;

                appendPerKBestC(perKBestCCsv, trialId, testPeriodIdx, k, bestC.bestC, bestC.stage1Mean);

                // Try to reuse existing stage-2 rows if present for this exact (trial,k,bestC,lambda-grid).
                boolean canReuseAll = stage2Header != null && hasAllLambdas(existingStage2Rows, trialId, k, bestC.bestC, stage2Header);
                double bestLambda;
                double bestStage2Mean;
                if (canReuseAll) {
                    Stage2BestReuse reuse = chooseBestLambdaFromExisting(existingStage2Rows, trialId, k, bestC.bestC, stage2Header);
                    // Write rows to our output, with chosen flag.
                    appendExistingStage2Rows(perKStage2Csv, existingStage2Rows, trialId, k, bestC.bestC, stage2Header, reuse.bestLambda);
                    bestLambda = reuse.bestLambda;
                    bestStage2Mean = reuse.bestMeanRealized;
                } else {
                    BrazilOlistAdaptiveCVSolveComparison.Stage2Best r =
                            BrazilOlistAdaptiveCVSolveComparison.computeAndAppendStage2ForFixedKC(
                                    samplesByK, testPeriodIdx, lanes, params, trialId, k, bestC.bestC, perKStage2Csv);
                    bestLambda = r.bestLambda;
                    bestStage2Mean = r.bestStage2MeanRealized;
                }

                appendPerKSelected(perKSelectionCsv, trialId, testPeriodIdx, k, bestC.bestC, bestLambda, bestC.stage1Mean, bestStage2Mean);
                alreadySelected.put(tk, true);
                System.out.println("done trial=" + trialId + " k=" + k + " bestC=" + fmt(bestC.bestC)
                        + " bestLambda=" + fmt(bestLambda) + " stage2=" + fmt(bestStage2Mean));
            }
        }

        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static boolean hasAllLambdas(Map<Stage2Key, String[]> existing,
                                         int trialId, int k, double cH,
                                         Map<String, Integer> header) {
        Objects.requireNonNull(header, "header");
        // Uses stage2 rows' own lambda values. We just verify 8 rows exist for this triple.
        int count = 0;
        for (Stage2Key key : existing.keySet()) {
            if (key.trialId == trialId && key.k == k && Math.abs(key.cH - cH) <= 1e-9) count++;
        }
        return count >= 8;
    }

    private static Stage2BestReuse chooseBestLambdaFromExisting(Map<Stage2Key, String[]> existing,
                                                                int trialId, int k, double cH,
                                                                Map<String, Integer> header) {
        int idxLambda = require(header, "lambda");
        int idxMeanReal = require(header, "meanRealizedObj");
        double bestMean = Double.POSITIVE_INFINITY;
        double bestLambda = Double.NaN;
        for (Map.Entry<Stage2Key, String[]> e : existing.entrySet()) {
            Stage2Key key = e.getKey();
            if (key.trialId != trialId || key.k != k || Math.abs(key.cH - cH) > 1e-9) continue;
            String[] cols = e.getValue();
            double lambda = parseDouble(cols[idxLambda]);
            double meanReal = parseDouble(cols[idxMeanReal]);
            if (!Double.isFinite(meanReal)) continue;
            if (!Double.isFinite(bestMean) || meanReal < bestMean - 1e-9 || (Math.abs(meanReal - bestMean) <= 1e-9 && lambda < bestLambda)) {
                bestMean = meanReal;
                bestLambda = lambda;
            }
        }
        if (!Double.isFinite(bestLambda) || !Double.isFinite(bestMean)) {
            throw new IllegalStateException("No valid existing stage2 rows for trial=" + trialId + " k=" + k + " C=" + cH);
        }
        return new Stage2BestReuse(bestLambda, bestMean);
    }

    private static void appendExistingStage2Rows(Path targetStage2,
                                                 Map<Stage2Key, String[]> existing,
                                                 int trialId, int k, double cH,
                                                 Map<String, Integer> header,
                                                 double bestLambda) throws Exception {
        int idxIsChosen = require(header, "isChosen");
        int idxLambda = require(header, "lambda");
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<Stage2Key, String[]> e : existing.entrySet()) {
            Stage2Key key = e.getKey();
            if (key.trialId != trialId || key.k != k || Math.abs(key.cH - cH) > 1e-9) continue;
            String[] cols = Arrays.copyOf(e.getValue(), e.getValue().length);
            double lambda = parseDouble(cols[idxLambda]);
            cols[idxIsChosen] = String.valueOf(Math.abs(lambda - bestLambda) <= 1e-9);
            rows.add(cols);
        }
        rows.sort((a, b) -> Double.compare(parseDouble(a[idxLambda]), parseDouble(b[idxLambda])));
        appendCsvRows(targetStage2, rows);
    }

    private static void initIfMissingStage2Like(Path csv) throws Exception {
        if (Files.exists(csv)) return;
        // Match BrazilOlistAdaptiveCVSolveComparison stage2 header.
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write(String.join(",",
                    "trialId", "testPeriodIdx",
                    "fullTrainStartPeriod", "fullTrainEndPeriod",
                    "subTrainStartPeriod", "subTrainEndPeriod",
                    "subValStartPeriod", "subValEndPeriod",
                    "k", "C_h", "lambda", "isChosen",
                    "validCount",
                    "meanExpectedObj", "meanRealizedObj", "meanSolveTimeSec", "meanSelectedCount",
                    "meanOosTransportCost", "meanOosSpotCost", "meanOosPenaltyCost",
                    "meanSumW", "meanSumW2", "meanESS", "meanTop1W", "meanTop5Wsum", "meanMaxWOverMean",
                    "meanThetaDistMean", "meanThetaDistMedian", "meanThetaDistMin", "meanThetaDistMax",
                    "meanDemandDistMean", "meanDemandDistMedian", "meanDemandDistMin", "meanDemandDistMax",
                    "meanCorrWThetaDist", "meanCorrWDemandDist", "meanCorrThetaDemandDist"));
            bw.newLine();
        }
    }

    private static void initIfMissingPerKBestC(Path csv) throws Exception {
        if (Files.exists(csv)) return;
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write("trialId,testPeriodIdx,k,bestC_h,stage1ValMean");
            bw.newLine();
        }
    }

    private static void initIfMissingPerKSelected(Path csv) throws Exception {
        if (Files.exists(csv)) return;
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write("trialId,testPeriodIdx,k,bestC_h,bestLambda,stage1ValMean,stage2ValMean");
            bw.newLine();
        }
    }

    private static Map<TrialK, Boolean> loadExistingSelections(Path selectionCsv) throws Exception {
        Map<TrialK, Boolean> out = new HashMap<>();
        if (!Files.exists(selectionCsv)) return out;
        try (BufferedReader br = Files.newBufferedReader(selectionCsv, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) return out;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length < 3) continue;
                int trial = Integer.parseInt(f[0].trim());
                int k = Integer.parseInt(f[2].trim());
                out.put(new TrialK(trial, k), true);
            }
        }
        return out;
    }

    private static void appendPerKBestC(Path csv, int trialId, int testPeriodIdx, int k, double bestC, double stage1Mean) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)) {
            bw.write(String.format(Locale.US, "%d,%d,%d,%.10f,%.10f%n", trialId, testPeriodIdx, k, bestC, stage1Mean));
        }
    }

    private static void appendPerKSelected(Path csv, int trialId, int testPeriodIdx, int k, double bestC, double bestLambda, double stage1Mean, double stage2Mean) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)) {
            bw.write(String.format(Locale.US, "%d,%d,%d,%.10f,%.10f,%.10f,%.10f%n",
                    trialId, testPeriodIdx, k, bestC, bestLambda, stage1Mean, stage2Mean));
        }
    }

    private static void appendCsvRows(Path csv, List<String[]> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)) {
            for (String[] cols : rows) {
                bw.write(String.join(",", cols));
                bw.newLine();
            }
        }
    }

    private static void mergeBestC(Map<TrialK, BestC> bestCByTrialK, Map<TrialK, BestC> add) {
        for (Map.Entry<TrialK, BestC> e : add.entrySet()) {
            BestC cur = bestCByTrialK.get(e.getKey());
            if (cur == null || e.getValue().stage1Mean < cur.stage1Mean - 1e-9
                    || (Math.abs(e.getValue().stage1Mean - cur.stage1Mean) <= 1e-9 && e.getValue().bestC < cur.bestC)) {
                bestCByTrialK.put(e.getKey(), e.getValue());
            }
        }
    }

    private static Stage1LoadResult loadStage1BestCPerK(Path csv, Map<String, Integer> headerHint) throws Exception {
        Map<String, Integer> header = headerHint;
        Map<TrialK, BestC> best = new HashMap<>();
        Map<Integer, Integer> testPeriodByTrial = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String h = br.readLine();
            if (h == null) return new Stage1LoadResult(header, best, testPeriodByTrial);
            if (header == null) header = headerMap(h);
            int idxTrial = require(header, "trialId");
            int idxTestPeriod = require(header, "testPeriodIdx");
            int idxK = require(header, "k");
            int idxC = require(header, "C_h");
            int idxMeanReal = require(header, "meanRealizedObj");

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length <= idxMeanReal) continue;
                int trialId = Integer.parseInt(f[idxTrial].trim());
                int testPeriod = Integer.parseInt(f[idxTestPeriod].trim());
                int k = Integer.parseInt(f[idxK].trim());
                double cH = parseDouble(f[idxC]);
                double meanReal = parseDouble(f[idxMeanReal]);
                testPeriodByTrial.putIfAbsent(trialId, testPeriod);
                TrialK tk = new TrialK(trialId, k);
                BestC cur = best.get(tk);
                if (cur == null || meanReal < cur.stage1Mean - 1e-9 || (Math.abs(meanReal - cur.stage1Mean) <= 1e-9 && cH < cur.bestC)) {
                    best.put(tk, new BestC(cH, meanReal));
                }
            }
        }
        return new Stage1LoadResult(header, best, testPeriodByTrial);
    }

    private static Stage2LoadResult loadStage2Rows(Path csv, Map<String, Integer> headerHint) throws Exception {
        Map<String, Integer> header = headerHint;
        Map<Stage2Key, String[]> out = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String h = br.readLine();
            if (h == null) return new Stage2LoadResult(header, out);
            if (header == null) header = headerMap(h);
            int idxTrial = require(header, "trialId");
            int idxK = require(header, "k");
            int idxC = require(header, "C_h");
            int idxLambda = require(header, "lambda");
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length <= idxLambda) continue;
                int trialId = Integer.parseInt(f[idxTrial].trim());
                int k = Integer.parseInt(f[idxK].trim());
                double cH = parseDouble(f[idxC]);
                double lambda = parseDouble(f[idxLambda]);
                out.put(new Stage2Key(trialId, k, cH, lambda), f);
            }
        }
        return new Stage2LoadResult(header, out);
    }

    private static List<Path> findFilesRecursive(Path root, String fileName) throws Exception {
        if (!Files.exists(root)) return Collections.emptyList();
        List<Path> out = new ArrayList<>();
        try (var s = Files.walk(root)) {
            s.filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName)).forEach(out::add);
        }
        return out;
    }

    private static Map<String, Integer> headerMap(String headerLine) {
        String[] cols = headerLine.split(",", -1);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < cols.length; i++) out.put(cols[i].trim(), i);
        return out;
    }

    private static int require(Map<String, Integer> header, String name) {
        Integer idx = header.get(name);
        if (idx == null) throw new IllegalArgumentException("Missing column: " + name + " in header: " + header.keySet());
        return idx;
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception ex) {
            return Double.NaN;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private static class TrialK {
        final int trialId;
        final int k;

        TrialK(int trialId, int k) {
            this.trialId = trialId;
            this.k = k;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TrialK)) return false;
            TrialK other = (TrialK) o;
            return trialId == other.trialId && k == other.k;
        }

        @Override
        public int hashCode() {
            return 31 * trialId + k;
        }
    }

    private static class BestC {
        final double bestC;
        final double stage1Mean;

        BestC(double bestC, double stage1Mean) {
            this.bestC = bestC;
            this.stage1Mean = stage1Mean;
        }
    }

    private static class Stage2Key {
        final int trialId;
        final int k;
        final double cH;
        final double lambda;
        final long cHBits;
        final long lambdaBits;

        Stage2Key(int trialId, int k, double cH, double lambda) {
            this.trialId = trialId;
            this.k = k;
            this.cH = cH;
            this.lambda = lambda;
            this.cHBits = Double.doubleToLongBits(cH);
            this.lambdaBits = Double.doubleToLongBits(lambda);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Stage2Key)) return false;
            Stage2Key other = (Stage2Key) o;
            return trialId == other.trialId
                    && k == other.k
                    && cHBits == other.cHBits
                    && lambdaBits == other.lambdaBits;
        }

        @Override
        public int hashCode() {
            int r = 31 * trialId + k;
            r = 31 * r + (int) (cHBits ^ (cHBits >>> 32));
            r = 31 * r + (int) (lambdaBits ^ (lambdaBits >>> 32));
            return r;
        }
    }

    private static class Stage1LoadResult {
        final Map<String, Integer> header;
        final Map<TrialK, BestC> bestCByTrialK;
        final Map<Integer, Integer> testPeriodByTrial;

        Stage1LoadResult(Map<String, Integer> header, Map<TrialK, BestC> bestCByTrialK, Map<Integer, Integer> testPeriodByTrial) {
            this.header = header;
            this.bestCByTrialK = bestCByTrialK;
            this.testPeriodByTrial = testPeriodByTrial;
        }
    }

    private static class Stage2LoadResult {
        final Map<String, Integer> header;
        final Map<Stage2Key, String[]> rowsByKey;

        Stage2LoadResult(Map<String, Integer> header, Map<Stage2Key, String[]> rowsByKey) {
            this.header = header;
            this.rowsByKey = rowsByKey;
        }
    }

    private static class Stage2BestReuse {
        final double bestLambda;
        final double bestMeanRealized;

        Stage2BestReuse(double bestLambda, double bestMeanRealized) {
            this.bestLambda = bestLambda;
            this.bestMeanRealized = bestMeanRealized;
        }
    }

    // Minimal copies of a few utilities from BrazilOlistAdaptiveCVSolveComparison to keep this runner standalone.
    private static double[] buildBaselineDemand(List<PeriodData> periods) {
        int jSize = periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (PeriodData p : periods) {
            for (int j = 0; j < jSize; j++) sum[j] += p.demandSum[j];
        }
        double denom = Math.max(1, periods.size());
        for (int j = 0; j < jSize; j++) sum[j] /= denom;
        return sum;
    }

    private static Config buildBaseConfig(int k1, double cH) {
        return invokeBuildBaseConfig(k1, cH);
    }

    // Keep config consistent with the main experiment class without duplicating constants manually.
    private static Config invokeBuildBaseConfig(int k1, double cH) {
        try {
            var m = BrazilOlistAdaptiveCVSolveComparison.class.getDeclaredMethod("buildBaseConfig", int.class, double.class);
            m.setAccessible(true);
            return (Config) m.invoke(null, k1, cH);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void aggregateDailyLongToWeeklyWide(Path dailyCsv, Path weeklyCsv) throws Exception {
        Map<LocalDate, Map<String, Double>> byDayLane = new HashMap<>();
        TreeSet<String> laneSet = new TreeSet<>();
        LocalDate minDate = null;
        LocalDate maxDate = null;

        try (BufferedReader br = Files.newBufferedReader(dailyCsv, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) throw new IllegalArgumentException("Empty CSV: " + dailyCsv);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length < 3) continue;
                LocalDate date = LocalDate.parse(f[0].trim());
                String lane = f[1].trim();
                double demand = parseDouble(f[2]);
                laneSet.add(lane);
                byDayLane.computeIfAbsent(date, kk -> new HashMap<>()).merge(lane, demand, Double::sum);
                if (minDate == null || date.isBefore(minDate)) minDate = date;
                if (maxDate == null || date.isAfter(maxDate)) maxDate = date;
            }
        }

        if (minDate == null || maxDate == null || laneSet.isEmpty()) {
            throw new IllegalArgumentException("No valid daily rows in: " + dailyCsv);
        }

        List<String> lanes = new ArrayList<>(laneSet);
        List<LocalDate> allDays = new ArrayList<>();
        for (LocalDate d = minDate; !d.isAfter(maxDate); d = d.plusDays(1)) {
            allDays.add(d);
        }

        try (BufferedWriter bw = Files.newBufferedWriter(weeklyCsv, StandardCharsets.UTF_8)) {
            bw.write("weekIndex");
            for (String lane : lanes) bw.write("," + csvEscape(lane));
            bw.newLine();

            int weekIndex = 0;
            for (int start = 0; start + 7 <= allDays.size(); start += 7) {
                bw.write(String.valueOf(weekIndex++));
                for (String lane : lanes) {
                    double sum = 0.0;
                    for (int kk = start; kk < start + 7; kk++) {
                        LocalDate d = allDays.get(kk);
                        Map<String, Double> m = byDayLane.getOrDefault(d, Collections.emptyMap());
                        sum += m.getOrDefault(lane, 0.0);
                    }
                    bw.write("," + String.format(Locale.US, "%.10f", sum));
                }
                bw.newLine();
            }
        }
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (!s.contains(",") && !s.contains("\"")) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
