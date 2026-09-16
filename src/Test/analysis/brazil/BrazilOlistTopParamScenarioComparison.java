package Test;

import Basic.CovariateVector;
import Basic.Data;
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
import Model.DROModel;
import Model.RCSAASolverVariant;
import Model.Solution;
import Model.SolveMode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Early supplementary runner for comparing top parameter groups on selected scenario or trial subsets.
 * It focuses on a small number of representative parameter groups instead of the full experiment grid.
 */
public class BrazilOlistTopParamScenarioComparison {

    private static final int TOP_N = 5;
    private static final int SEARCH_RADIUS = 2;
    private static final String RCSAA_MODE_HYBRID = "hybrid_exact_lbbd_time";
    private static final String RCSAA_MODE_LBBD_ONLY = "lbbd_only";

    private static final Path MERGED_PARAMS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/结果/"
                    + "04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果_ExactRCSAA枚举_merged/"
                    + "RCSAA_每个trial最优参数_合并结果.csv");

    private static final Path WEEKLY_INPUT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                    + "按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");

    private static final Path OUT_ROOT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/结果/"
                    + "07_RCSAA高频参数_场景测试");

    public static void main(String[] args) throws Exception {
        int rankStart = (args.length > 0 ? Integer.parseInt(args[0]) : 1);
        int rankEnd = (args.length > 1 ? Integer.parseInt(args[1]) : TOP_N);
        int trainingWindow = (args.length > 2 ? Integer.parseInt(args[2]) : 50);
        int numCarriers = (args.length > 3 ? Integer.parseInt(args[3]) : 10);
        String scenarioName = (args.length > 4 ? args[4] : String.format(Locale.US, "W%d_I%d", trainingWindow, numCarriers));
        String rcsaaMode = (args.length > 5 ? args[5] : RCSAA_MODE_HYBRID);
        Path outRoot = (args.length > 6 ? Paths.get(args[6]) : OUT_ROOT.resolve(scenarioName));

        Files.createDirectories(outRoot);

        List<SelectionRow> selections = loadSelections(MERGED_PARAMS);
        List<FrequencyGroup> ranked = rankGroups(selections);

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY_INPUT);
        List<String> lanes = weekly.laneNames;
        double[] dBase = buildBaselineDemand(weekly.periods);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();

        try (BufferedWriter bw = Files.newBufferedWriter(
                outRoot.resolve("top5_param_scenario_summary.csv"), StandardCharsets.UTF_8)) {
            bw.write("scenario,rank,group_name,trial_count,skipped_invalid_trials,method,mean_expected_obj,mean_model_obj,mean_realized_obj,mean_solve_time_sec,mean_selected_count,mean_transport_cost,mean_spot_cost,mean_penalty_cost");
            bw.newLine();

            for (int i = 0; i < ranked.size(); i++) {
                int rank = i + 1;
                if (rank < rankStart || rank > rankEnd) {
                    continue;
                }
                FrequencyGroup group = ranked.get(i);
                Path groupRoot = outRoot.resolve(group.groupName(rank));
                Files.createDirectories(groupRoot);

                Config baseCfg = buildBaseConfig(group.key.bestK, group.key.bestCH, group.key.bestLambda);
                SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(weekly.periods, lanes, baseCfg);
                ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, baseCfg);

                List<TrialComparisonRow> compareRows = new ArrayList<>();
                SummaryAccumulator droAcc = new SummaryAccumulator();
                SummaryAccumulator rcsaaAcc = new SummaryAccumulator();
                int skippedInvalid = 0;

                for (SelectionRow sel : group.rows) {
                    if (!hasValidTrainingWindow(br.samples, sel.testPeriodIdx, sel.bestK, trainingWindow)) {
                        skippedInvalid++;
                        continue;
                    }

                    TrialSolveResult dro = solveOneTrial(br.samples, lanes, params, sel,
                            buildDroConfig(group.key.bestK, group.key.bestCH, group.key.bestLambda), trainingWindow);
                    TrialSolveResult lbbd = solveOneTrial(br.samples, lanes, params, sel,
                            buildLbbdConfig(group.key.bestK, group.key.bestCH, group.key.bestLambda), trainingWindow);
                    TrialSolveResult exact = null;
                    if (RCSAA_MODE_HYBRID.equalsIgnoreCase(rcsaaMode)) {
                        exact = solveOneTrial(br.samples, lanes, params, sel,
                                buildExactConfig(group.key.bestK, group.key.bestCH, group.key.bestLambda), trainingWindow);
                    }

                    TrialComparisonRow droRow = TrialComparisonRow.fromResult(scenarioName, rank, group, sel, "DRO", dro);
                    TrialComparisonRow rcsaaRow = RCSAA_MODE_HYBRID.equalsIgnoreCase(rcsaaMode)
                            ? TrialComparisonRow.fromHybrid(scenarioName, rank, group, sel, exact, lbbd.solveTimeSec)
                            : TrialComparisonRow.fromResult(scenarioName, rank, group, sel, "RCSAA_LBBD", lbbd);

                    compareRows.add(droRow);
                    compareRows.add(rcsaaRow);
                    droAcc.add(droRow);
                    rcsaaAcc.add(rcsaaRow);
                }

                String rcsaaMethodName = RCSAA_MODE_HYBRID.equalsIgnoreCase(rcsaaMode)
                        ? "RCSAA_exact_with_LBBD_time"
                        : "RCSAA_LBBD";

                writePerTrialCsv(groupRoot.resolve("group_trial_compare.csv"), compareRows);
                writeSummaryCsv(groupRoot.resolve("group_summary.csv"), scenarioName, rank, group, skippedInvalid, droAcc, rcsaaAcc, rcsaaMethodName);

                bw.write(droAcc.toSummaryLine(scenarioName, rank, group, skippedInvalid, "DRO"));
                bw.newLine();
                bw.write(rcsaaAcc.toSummaryLine(scenarioName, rank, group, skippedInvalid, rcsaaMethodName));
                bw.newLine();
            }
        }

        System.out.println("done: " + outRoot.toAbsolutePath());
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
        cfg.solveMode = SolveMode.RCSAA;
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        return cfg;
    }

    private static Config buildDroConfig(int k1, double cH, double lambda) {
        Config cfg = buildBaseConfig(k1, cH, lambda);
        cfg.rcsaaSolverVariant = RCSAASolverVariant.DRO_EXTENSIVE;
        return cfg;
    }

    private static Config buildExactConfig(int k1, double cH, double lambda) {
        Config cfg = buildBaseConfig(k1, cH, lambda);
        cfg.rcsaaSolverVariant = RCSAASolverVariant.ENUMERATE_EXACT;
        return cfg;
    }

    private static Config buildLbbdConfig(int k1, double cH, double lambda) {
        Config cfg = buildBaseConfig(k1, cH, lambda);
        cfg.rcsaaSolverVariant = RCSAASolverVariant.LBBD_PRIMAL_SEARCH;
        cfg.rcsaaSearchNeighborhoodRadius = SEARCH_RADIUS;
        return cfg;
    }

    private static TrialSolveResult solveOneTrial(List<Sample> samplesForK,
                                                  List<String> lanes,
                                                  ProcurementParams params,
                                                  SelectionRow row,
                                                  Config cfg,
                                                  int trainingWindow) throws Exception {
        List<Sample> trainRaw = buildTrainingWindow(samplesForK, row.testPeriodIdx, row.bestK, trainingWindow);
        Sample testSample = getTestSample(samplesForK, row.testPeriodIdx, row.bestK);

        List<Sample> train = BatchRunner.deepCopySamples(trainRaw);
        CovariateVector thetaNow = new CovariateVector(testSample.theta.values().clone());
        int thetaDim = train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length;

        if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
            StandardScaler scaler = new StandardScaler();
            scaler.fit(train, thetaDim);
            for (Sample s : train) {
                s.theta = new CovariateVector(scaler.transform(s.theta.values()));
            }
            thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
        }

        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
        wc.computeKernelWeights(train, thetaNow, cfg);

        double[] dTest = testSample.demand().clone();
        DROModel model = new DROModel();
        long st = System.nanoTime();
        Solution sol = model.solve(new Data(lanes, train, thetaNow, params), cfg);
        long ed = System.nanoTime();
        BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(params, sol.y, dTest);
        double[] qTrain = empiricalRecourseCosts(params, train, sol.y);
        double expected = weightedMean(qTrain, train);

        TrialSolveResult out = new TrialSolveResult();
        out.expectedObj = expected;
        out.modelObj = sol.objValue;
        out.realizedObj = rec.objValue;
        out.solveTimeSec = (ed - st) / 1e9;
        out.selectedCount = countSelected(sol.y);
        out.transportCost = rec.transportTotalCost;
        out.spotCost = rec.spotTotalCost;
        out.penaltyCost = rec.penaltyTotalCost;
        return out;
    }

    private static List<SelectionRow> loadSelections(Path csv) throws Exception {
        List<SelectionRow> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = parseCsvLine(line);
                out.add(new SelectionRow(
                        Integer.parseInt(f[0]),
                        Integer.parseInt(f[1]),
                        Integer.parseInt(f[2]),
                        Double.parseDouble(f[3]),
                        Double.parseDouble(f[4])));
            }
        }
        return out;
    }

    private static List<FrequencyGroup> rankGroups(List<SelectionRow> rows) {
        Map<ParamKey, FrequencyGroup> map = new LinkedHashMap<>();
        for (SelectionRow row : rows) {
            ParamKey key = new ParamKey(row.bestK, row.bestCH, row.bestLambda);
            FrequencyGroup g = map.computeIfAbsent(key, k -> new FrequencyGroup(k));
            g.rows.add(row);
        }
        List<FrequencyGroup> out = new ArrayList<>(map.values());
        out.sort(Comparator
                .comparingInt((FrequencyGroup g) -> g.rows.size()).reversed()
                .thenComparingInt(g -> g.key.bestK)
                .thenComparingDouble(g -> g.key.bestCH)
                .thenComparingDouble(g -> g.key.bestLambda));
        return out.subList(0, Math.min(TOP_N, out.size()));
    }

    private static boolean hasValidTrainingWindow(List<Sample> samplesForK, int testPeriodIdx, int k, int trainingWindow) {
        int testSampleIdx = testPeriodIdx - k;
        int start = testSampleIdx - trainingWindow;
        int end = testSampleIdx;
        return start >= 0 && end <= samplesForK.size();
    }

    private static List<Sample> buildTrainingWindow(List<Sample> samplesForK, int testPeriodIdx, int k, int trainingWindow) {
        int testSampleIdx = testPeriodIdx - k;
        int start = testSampleIdx - trainingWindow;
        int end = testSampleIdx;
        if (start < 0 || end > samplesForK.size()) {
            throw new IllegalArgumentException("Invalid training window for testPeriod=" + testPeriodIdx + ", k=" + k + ", W=" + trainingWindow);
        }
        return new ArrayList<>(samplesForK.subList(start, end));
    }

    private static Sample getTestSample(List<Sample> samplesForK, int testPeriodIdx, int k) {
        int testSampleIdx = testPeriodIdx - k;
        return samplesForK.get(testSampleIdx);
    }

    private static double[] buildBaselineDemand(List<Basic.PeriodData> periods) {
        int jSize = periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (Basic.PeriodData p : periods) {
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

    private static double[] empiricalRecourseCosts(ProcurementParams params, List<Sample> train, double[] y) throws Exception {
        double[] vals = new double[train.size()];
        for (int i = 0; i < train.size(); i++) {
            vals[i] = BatchRunner.RecourseEvaluator.evaluate(params, y, train.get(i).demand().clone()).objValue;
        }
        return vals;
    }

    private static double weightedMean(double[] vals, List<Sample> train) {
        double sumW = 0.0;
        double sum = 0.0;
        for (int i = 0; i < train.size(); i++) {
            double w = train.get(i).weight;
            if (!Double.isFinite(w)) continue;
            sumW += w;
            sum += w * vals[i];
        }
        return sumW > 0.0 ? sum / sumW : Double.NaN;
    }

    private static int countSelected(double[] y) {
        int c = 0;
        for (double v : y) if (v > 0.5) c++;
        return c;
    }

    private static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    private static void writePerTrialCsv(Path csv, List<TrialComparisonRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write("scenario,rank,group_name,trialId,testPeriodIdx,method,expected_obj,model_obj,realized_obj,solve_time_sec,selected_count,transport_cost,spot_cost,penalty_cost");
            bw.newLine();
            for (TrialComparisonRow row : rows) {
                bw.write(row.toCsvLine());
                bw.newLine();
            }
        }
    }

    private static void writeSummaryCsv(Path csv,
                                        String scenarioName,
                                        int rank,
                                        FrequencyGroup group,
                                        int skippedInvalid,
                                        SummaryAccumulator droAcc,
                                        SummaryAccumulator rcsaaAcc,
                                        String rcsaaMethodName) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write("scenario,rank,group_name,trial_count,skipped_invalid_trials,method,mean_expected_obj,mean_model_obj,mean_realized_obj,mean_solve_time_sec,mean_selected_count,mean_transport_cost,mean_spot_cost,mean_penalty_cost");
            bw.newLine();
            bw.write(droAcc.toSummaryLine(scenarioName, rank, group, skippedInvalid, "DRO"));
            bw.newLine();
            bw.write(rcsaaAcc.toSummaryLine(scenarioName, rank, group, skippedInvalid, rcsaaMethodName));
            bw.newLine();
        }
    }

    private record ParamKey(int bestK, double bestCH, double bestLambda) {}

    private static final class SelectionRow {
        final int trialId;
        final int testPeriodIdx;
        final int bestK;
        final double bestCH;
        final double bestLambda;

        SelectionRow(int trialId, int testPeriodIdx, int bestK, double bestCH, double bestLambda) {
            this.trialId = trialId;
            this.testPeriodIdx = testPeriodIdx;
            this.bestK = bestK;
            this.bestCH = bestCH;
            this.bestLambda = bestLambda;
        }
    }

    private static final class FrequencyGroup {
        final ParamKey key;
        final List<SelectionRow> rows = new ArrayList<>();

        FrequencyGroup(ParamKey key) {
            this.key = key;
        }

        String groupName(int rank) {
            return String.format(Locale.US, "rank%02d_k%d_C%.1f_lambda%.2f_freq%d",
                    rank, key.bestK, key.bestCH, key.bestLambda, rows.size());
        }
    }

    private static final class TrialSolveResult {
        double expectedObj;
        double modelObj;
        double realizedObj;
        double solveTimeSec;
        int selectedCount;
        double transportCost;
        double spotCost;
        double penaltyCost;
    }

    private static final class TrialComparisonRow {
        String scenario;
        int rank;
        String groupName;
        int trialId;
        int testPeriodIdx;
        String method;
        double expectedObj;
        double modelObj;
        double realizedObj;
        double solveTimeSec;
        int selectedCount;
        double transportCost;
        double spotCost;
        double penaltyCost;

        static TrialComparisonRow fromResult(String scenario, int rank, FrequencyGroup group, SelectionRow sel, String method, TrialSolveResult result) {
            TrialComparisonRow row = new TrialComparisonRow();
            row.scenario = scenario;
            row.rank = rank;
            row.groupName = group.groupName(rank);
            row.trialId = sel.trialId;
            row.testPeriodIdx = sel.testPeriodIdx;
            row.method = method;
            row.expectedObj = result.expectedObj;
            row.modelObj = result.modelObj;
            row.realizedObj = result.realizedObj;
            row.solveTimeSec = result.solveTimeSec;
            row.selectedCount = result.selectedCount;
            row.transportCost = result.transportCost;
            row.spotCost = result.spotCost;
            row.penaltyCost = result.penaltyCost;
            return row;
        }

        static TrialComparisonRow fromHybrid(String scenario, int rank, FrequencyGroup group, SelectionRow sel, TrialSolveResult exact, double lbbdTime) {
            TrialComparisonRow row = fromResult(scenario, rank, group, sel, "RCSAA_exact_with_LBBD_time", exact);
            row.modelObj = exact.expectedObj;
            row.solveTimeSec = lbbdTime;
            return row;
        }

        String toCsvLine() {
            return String.format(Locale.US, "%s,%d,%s,%d,%d,%s,%.10f,%.10f,%.10f,%.6f,%d,%.10f,%.10f,%.10f",
                    scenario, rank, groupName, trialId, testPeriodIdx, method,
                    expectedObj, modelObj, realizedObj, solveTimeSec, selectedCount,
                    transportCost, spotCost, penaltyCost);
        }
    }

    private static final class SummaryAccumulator {
        int n;
        double sumExpected;
        double sumModel;
        double sumRealized;
        double sumSolveTime;
        double sumSelectedCount;
        double sumTransport;
        double sumSpot;
        double sumPenalty;

        void add(TrialComparisonRow row) {
            n++;
            sumExpected += row.expectedObj;
            sumModel += row.modelObj;
            sumRealized += row.realizedObj;
            sumSolveTime += row.solveTimeSec;
            sumSelectedCount += row.selectedCount;
            sumTransport += row.transportCost;
            sumSpot += row.spotCost;
            sumPenalty += row.penaltyCost;
        }

        String toSummaryLine(String scenario, int rank, FrequencyGroup group, int skippedInvalid, String method) {
            return String.format(Locale.US, "%s,%d,%s,%d,%d,%s,%.10f,%.10f,%.10f,%.6f,%.10f,%.10f,%.10f,%.10f",
                    scenario, rank, group.groupName(rank), n, skippedInvalid, method,
                    sumExpected / n, sumModel / n, sumRealized / n, sumSolveTime / n,
                    sumSelectedCount / n, sumTransport / n, sumSpot / n, sumPenalty / n);
        }
    }
}

