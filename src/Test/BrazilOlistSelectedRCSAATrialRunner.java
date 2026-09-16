package Test;

import Basic.CovariateVector;
import Basic.Data;
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
import Model.DROModel;
import Model.Solution;
import Model.SolveMode;
import Test.BatchRunner.RecourseEvaluator;
import Test.BatchRunner.TrialDiag;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

public class BrazilOlistSelectedRCSAATrialRunner {

    private static final int W = 50;

    public static void main(String[] args) throws Exception {
        Path tupleCsv = Paths.get(args.length > 0 ? args[0]
                : "analysis/第二组_按k最优参数提取结果/missing_selected_rcsaa_tuples.csv");
        Path outCsv = Paths.get(args.length > 1 ? args[1]
                : "analysis/第二组_按k最优参数提取结果/missing_selected_rcsaa_trials.csv");
        Path dailyCsv = Paths.get(args.length > 2 ? args[2]
                : "analysis/巴西数据分析/旧版/五大区合并后日度OD需求表_千克.csv");
        int numCarriers = (args.length > 3 ? Integer.parseInt(args[3]) : 10);

        Files.createDirectories(outCsv.getParent());
        initOutCsv(outCsv);

        Path weeklyCsv = outCsv.getParent().resolve("补算_RCSAA_周度宽表.csv");
        aggregateDailyLongToWeeklyWide(dailyCsv, weeklyCsv);
        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = w.laneNames;

        Map<Integer, ExperimentBatches> rollingByK = new HashMap<>();
        for (int k = 1; k <= 3; k++) {
            Config cfg = buildConfig(k, 1.0, 1.0);
            SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(w.periods, lanes, cfg);
            rollingByK.put(k, ExperimentBuilder.buildRolling(br.samples, W));
        }

        double[] dBase = buildBaselineDemand(w.periods);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, buildConfig(3, 1.0, 1.0));

        try (BufferedReader br = Files.newBufferedReader(tupleCsv)) {
            String header = br.readLine();
            if (header == null) throw new IllegalArgumentException("Empty tuple csv: " + tupleCsv);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                int trialId = Integer.parseInt(f[0].trim());
                int k = Integer.parseInt(f[1].trim());
                double cH = Double.parseDouble(f[2].trim());
                double lambda = Double.parseDouble(f[3].trim());
                solveOne(outCsv, lanes, params, rollingByK.get(k), trialId, k, cH, lambda);
                System.out.println("done trial=" + trialId + " k=" + k + " C=" + cH + " lambda=" + lambda);
            }
        }
    }

    private static void solveOne(Path outCsv,
                                 List<String> lanes,
                                 ProcurementParams params,
                                 ExperimentBatches rolling,
                                 int trialId,
                                 int k,
                                 double cH,
                                 double lambda) throws Exception {
        Config cfg = buildConfig(k, cH, lambda);

        List<Sample> trainRaw = rolling.trainSets.get(trialId);
        CovariateVector thetaNowRaw = rolling.thetaNowList.get(trialId);
        Sample test = rolling.testSamples.get(trialId);
        int testIdx = rolling.testIndex.get(trialId);

        List<Sample> train = BatchRunner.deepCopySamples(trainRaw);
        CovariateVector thetaNow = new CovariateVector(thetaNowRaw.values().clone());
        int thetaDim = (train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length);

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

        double[] dTest = test.demand().clone();
        TrialDiag diag = TrialDiag.compute(train, thetaNow, dTest, new EuclideanDistance());

        Data data = new Data(lanes, train, thetaNow, params);
        DROModel droModel = new DROModel();

        long st = System.nanoTime();
        Solution sol = droModel.solve(data, cfg);
        long ed = System.nanoTime();
        double solveTimeSec = (ed - st) / 1e9;

        RecourseEvaluator.RecourseEval rec = RecourseEvaluator.evaluate(params, sol.y, dTest);
        double[] qTrain = empiricalRecourseCosts(params, train, sol.y);
        double expected = weightedMean(qTrain, train);
        int selectedCount = countSelected(sol.y);

        String sourceDir = String.format(Locale.US, "RCSAA_W50_k1=%d_raw_C%.2f_lambda%.2f", k, cH, lambda);
        String tag = String.format(Locale.US, "巴西Olist23OD_RCSAA_W50_k1=%d_raw_lambda%.2f", k, lambda);
        String param = String.format(Locale.US, "C_h=%.0f, lambda=%s", cH, trimLambda(lambda));

        try (BufferedWriter bw = Files.newBufferedWriter(outCsv, java.nio.file.StandardOpenOption.APPEND)) {
            bw.write(String.format(Locale.US,
                    "%d,%s,%s,%s,%s,%d,%d,%s,%s,%s,%d,%s,%.6f,%.10f,%.10f,%d," +
                            "%.10f,%.10f,%.6f,%d," +
                            "%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%s,%s%n",
                    k,
                    sourceDir,
                    "RCSAA",
                    csvQuote(param),
                    tag,
                    trialId,
                    testIdx,
                    cfg.solveMode.name(),
                    String.valueOf(cfg.fillMissingDates),
                    String.valueOf(cfg.standardizeTheta),
                    cfg.k1LagPeriods,
                    cfg.kernelType.name(),
                    cfg.bandwidthH,
                    cfg.C_h,
                    cfg.lambda,
                    train.size(),
                    expected,
                    rec.objValue,
                    solveTimeSec,
                    selectedCount,
                    rec.transportTotalCost,
                    rec.spotTotalCost,
                    rec.penaltyTotalCost,
                    diag.sumW,
                    diag.sumW2,
                    diag.ess,
                    diag.top1W,
                    diag.top5Wsum,
                    diag.maxOverMean,
                    diag.thetaMean,
                    diag.thetaMedian,
                    diag.thetaMin,
                    diag.thetaMax,
                    diag.demMean,
                    diag.demMedian,
                    diag.demMin,
                    diag.demMax,
                    diag.corrWTheta,
                    diag.corrWDemand,
                    diag.corrThetaDemand,
                    csvQuote(yBinary(sol.y)),
                    csvQuote(selectedCarriers(sol.y))
            ));
        }
    }

    private static void initOutCsv(Path outCsv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outCsv)) {
            bw.write("k1,source_dir,method_name,param,标签,试验ID,测试索引,求解模式,fillMissingDates,standardizeTheta,k1Lag,kernelType,bandwidthH,C_h,lambda,训练集大小,期望目标值,实现目标值,求解时间秒,选中供应商数量,样本外运输成本,样本外现货成本,样本外罚金成本,权重和,权重平方和,有效样本量,最大权重,前5权重和,最大权重相对平均值,Theta距离均值,Theta距离中位数,Theta距离最小值,Theta距离最大值,需求距离均值,需求距离中位数,需求距离最小值,需求距离最大值,权重Theta距离相关系数,权重需求距离相关系数,Theta需求距离相关系数,y二进制向量,选中供应商集合");
            bw.newLine();
        }
    }

    private static Config buildConfig(int k1, double cH, double lambda) {
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
        cfg.writeCplexLogToFile = false;
        return cfg;
    }

    private static String trimLambda(double lambda) {
        if (Math.rint(lambda) == lambda) {
            return String.valueOf((long) lambda);
        }
        return String.format(Locale.US, "%.2f", lambda);
    }

    private static int countSelected(double[] y) {
        int c = 0;
        for (double v : y) if (v > 0.5) c++;
        return c;
    }

    private static String yBinary(double[] y) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < y.length; i++) {
            sb.append(y[i] > 0.5 ? 1 : 0);
            if (i < y.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String selectedCarriers(double[] y) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (int i = 0; i < y.length; i++) {
            if (y[i] > 0.5) {
                if (!first) sb.append(",");
                sb.append(i);
                first = false;
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String csvQuote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

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

    private static double[] empiricalRecourseCosts(ProcurementParams params, List<Sample> train, double[] y) throws Exception {
        double[] out = new double[train.size()];
        for (int i = 0; i < train.size(); i++) {
            Sample s = train.get(i);
            RecourseEvaluator.RecourseEval rec = RecourseEvaluator.evaluate(params, y, s.demand());
            out[i] = rec.objValue;
        }
        return out;
    }

    private static double weightedMean(double[] vals, List<Sample> train) {
        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < vals.length; i++) {
            double w = train.get(i).weight;
            if (!Double.isFinite(w) || w <= 0.0) continue;
            num += w * vals[i];
            den += w;
        }
        return den > 0 ? num / den : Double.NaN;
    }

    private static void aggregateDailyLongToWeeklyWide(Path dailyCsv, Path weeklyCsv) throws Exception {
        Map<LocalDate, Map<String, Double>> byDayLane = new HashMap<>();
        TreeSet<String> laneSet = new TreeSet<>();
        LocalDate minDate = null;
        LocalDate maxDate = null;

        try (BufferedReader br = Files.newBufferedReader(dailyCsv)) {
            String header = br.readLine();
            if (header == null) throw new IllegalArgumentException("Empty CSV: " + dailyCsv);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length < 3) continue;
                LocalDate date = LocalDate.parse(f[0].trim());
                String lane = f[1].trim();
                double demand = Double.parseDouble(f[2].trim());

                laneSet.add(lane);
                byDayLane.computeIfAbsent(date, k0 -> new HashMap<>()).merge(lane, demand, Double::sum);
                if (minDate == null || date.isBefore(minDate)) minDate = date;
                if (maxDate == null || date.isAfter(maxDate)) maxDate = date;
            }
        }

        if (minDate == null || maxDate == null || laneSet.isEmpty()) {
            throw new IllegalArgumentException("No valid daily rows in: " + dailyCsv);
        }

        List<String> lanes = new ArrayList<>(laneSet);
        List<LocalDate> allDays = new ArrayList<>();
        for (LocalDate d = minDate; !d.isAfter(maxDate); d = d.plusDays(1)) allDays.add(d);

        try (BufferedWriter bw = Files.newBufferedWriter(weeklyCsv)) {
            bw.write("weekIndex");
            for (String lane : lanes) bw.write("," + lane);
            bw.newLine();

            int week = 0;
            for (int start = 0; start + 6 < allDays.size(); start += 7) {
                bw.write(Integer.toString(week++));
                Map<String, Double> sum = new HashMap<>();
                for (int d = start; d < start + 7; d++) {
                    Map<String, Double> m = byDayLane.getOrDefault(allDays.get(d), java.util.Collections.emptyMap());
                    for (String lane : lanes) sum.merge(lane, m.getOrDefault(lane, 0.0), Double::sum);
                }
                for (String lane : lanes) {
                    bw.write("," + String.format(Locale.US, "%.10f", sum.getOrDefault(lane, 0.0)));
                }
                bw.newLine();
            }
        }
    }
}
