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
import Model.RCSAASolverVariant;
import Model.Solution;
import Model.SolveMode;
import mosek.fusion.Domain;
import mosek.fusion.Expr;
import mosek.fusion.Expression;
import mosek.fusion.Model;
import mosek.fusion.ObjectiveSense;
import mosek.fusion.Variable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Spot-check runner for a single parameter configuration.
 * Use it to re-evaluate one chosen (trial, k, C_h, lambda, method) tuple when debugging discrepancies.
 */
public class BrazilOlistSingleParamCompare {

    private static final int W = 50;
    private static final int NUM_CARRIERS = 10;

    private static final int TARGET_TRIAL = 0;
    private static final int TARGET_TEST_PERIOD = 53;
    private static final int TARGET_K = 3;
    private static final double TARGET_C = 0.5;
    private static final double TARGET_LAMBDA = 5.0;

    private static final Path WEEKLY_INPUT = Paths.get(
            "analysis/\u5df4\u897f\u6570\u636e\u5206\u6790/\u65b0\u7248_purchase\u65f6\u95f4/\u8f93\u5165/\u805a\u5408\u9700\u6c42\u8868_\u65e5\u5ea6\u4e0e\u5468\u5ea6/"
                    + "\u6309purchase\u65f6\u95f4_\u4e94\u5927\u533a23OD_\u5468\u5ea6\u5bbd\u8868_10\u4f9b\u5e94\u5546\u5b9e\u9a8c\u8f93\u5165.csv");

    public static void main(String[] args) throws Exception {
        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY_INPUT);
        List<String> lanes = weekly.laneNames;

        Config sampleCfg = buildBaseConfig(TARGET_K, TARGET_C, TARGET_LAMBDA);
        SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(weekly.periods, lanes, sampleCfg);
        List<Sample> samplesForK = br.samples;

        double[] dBase = buildBaselineDemand(weekly.periods);
        ProcurementParams params = InstanceGenerator.generate(
                NUM_CARRIERS, dBase, new InstanceGenerator.GenConfig(), buildBaseConfig(3, 1.0, 0.01));

        TrainWindow window = buildTrainingWindow(samplesForK, TARGET_TEST_PERIOD, TARGET_K);
        List<Sample> train = BatchRunner.deepCopySamples(window.trainSamples);
        Sample testSample = window.testSample;
        CovariateVector thetaNow = new CovariateVector(testSample.theta.values().clone());
        int thetaDim = train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length;

        if (!train.isEmpty() && train.size() >= 2) {
            StandardScaler scaler = new StandardScaler();
            scaler.fit(train, thetaDim);
            for (Sample s : train) {
                s.theta = new CovariateVector(scaler.transform(s.theta.values()));
            }
            thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
        }

        Config weightCfg = buildBaseConfig(TARGET_K, TARGET_C, TARGET_LAMBDA);
        KernelFunction kernel = WeightCalculator.buildKernel(weightCfg);
        WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
        wc.computeKernelWeights(train, thetaNow, weightCfg);

        List<Sample> exactTrain = BatchRunner.deepCopySamples(train);
        List<Sample> droTrain = BatchRunner.deepCopySamples(train);
        double maxWeightDiff = maxWeightDiff(exactTrain, droTrain);

        Config exactCfg = buildBaseConfig(TARGET_K, TARGET_C, TARGET_LAMBDA);
        exactCfg.solveMode = SolveMode.RCSAA;
        exactCfg.rcsaaSolverVariant = RCSAASolverVariant.ENUMERATE_EXACT;
        Config primalCfg = buildBaseConfig(TARGET_K, TARGET_C, TARGET_LAMBDA);
        primalCfg.solveMode = SolveMode.RCSAA;
        primalCfg.rcsaaSolverVariant = RCSAASolverVariant.LBBD_PRIMAL_EXACT;

        Config droCfg = buildBaseConfig(TARGET_K, TARGET_C, TARGET_LAMBDA);
        droCfg.solveMode = SolveMode.RCSAA;
        droCfg.rcsaaSolverVariant = RCSAASolverVariant.DRO_EXTENSIVE;

        double[] dTest = testSample.demand().clone();

        Solution exactSol = new DROModel().solve(new Data(lanes, exactTrain, thetaNow, params), exactCfg);
        Solution primalSol = new DROModel().solve(new Data(lanes, BatchRunner.deepCopySamples(train), thetaNow, params), primalCfg);
        BatchRunner.RecourseEvaluator.RecourseEval exactRec =
                BatchRunner.RecourseEvaluator.evaluate(params, exactSol.y, dTest);
        double exactExpected = weightedMean(empiricalRecourseCosts(params, exactTrain, exactSol.y), exactTrain);

        DroDiagnostic dro = solveDroWithGap(lanes, droTrain, thetaNow, params, droCfg, dTest);
        DroDiagnostic droOnExactY = solveDroWithGapFixedY(lanes, droTrain, thetaNow, params, droCfg, dTest, exactSol.y);
        RiskStats exactRiskOnExactY = computeRiskStats(params, exactTrain, exactSol.y, TARGET_LAMBDA);
        RiskStats exactRiskOnDroY = computeRiskStats(params, exactTrain, dro.y, TARGET_LAMBDA);

        System.out.println("===== Single Param Compare =====");
        System.out.println(String.format(Locale.US,
                "trial=%d testPeriod=%d k=%d C=%.6f lambda=%.6f",
                TARGET_TRIAL, TARGET_TEST_PERIOD, TARGET_K, TARGET_C, TARGET_LAMBDA));
        System.out.println(String.format(Locale.US, "weightCount=%d maxWeightDiff=%.12f ess=%.10f",
                train.size(), maxWeightDiff, ess(train)));
        System.out.println("weights=" + encodeWeights(train));

        System.out.println("----- RCSAAEXT -----");
        System.out.println("y_binary=" + encodeY(exactSol.y));
        System.out.println("selected_carriers=" + encodeSelectedCarriers(exactSol.y));
        System.out.println(String.format(Locale.US,
                "model_obj=%.10f expected_obj=%.10f realized_obj=%.10f selected_count=%d",
                exactSol.objValue, exactExpected, exactRec.objValue, countSelected(exactSol.y)));

        System.out.println("----- DRO -----");
        System.out.println("y_binary=" + encodeY(dro.y));
        System.out.println("selected_carriers=" + encodeSelectedCarriers(dro.y));
        System.out.println(String.format(Locale.US,
                "model_obj=%.10f expected_obj=%.10f realized_obj=%.10f selected_count=%d",
                dro.modelObj, dro.expectedObj, dro.realizedObj, countSelected(dro.y)));
        System.out.println(String.format(Locale.US,
                "problem_status=%s mio_rel_gap=%s mio_abs_gap=%s",
                dro.problemStatus, fmt(dro.mioRelGap), fmt(dro.mioAbsGap)));

        System.out.println("----- LBBD_PRIMAL_EXACT -----");
        System.out.println("y_binary=" + encodeY(primalSol.y));
        System.out.println("selected_carriers=" + encodeSelectedCarriers(primalSol.y));
        System.out.println(String.format(Locale.US,
                "model_obj=%.10f solve_time_sec=%.10f same_as_enum=%s",
                primalSol.objValue, primalSol.solveTimeSec, String.valueOf(sameY(primalSol.y, exactSol.y))));

        System.out.println("----- DRO on RCSAAEXT y -----");
        System.out.println("y_binary=" + encodeY(droOnExactY.y));
        System.out.println("selected_carriers=" + encodeSelectedCarriers(droOnExactY.y));
        System.out.println(String.format(Locale.US,
                "model_obj=%.10f expected_obj=%.10f realized_obj=%.10f selected_count=%d",
                droOnExactY.modelObj, droOnExactY.expectedObj, droOnExactY.realizedObj, countSelected(droOnExactY.y)));
        System.out.println(String.format(Locale.US,
                "problem_status=%s mio_rel_gap=%s mio_abs_gap=%s",
                droOnExactY.problemStatus, fmt(droOnExactY.mioRelGap), fmt(droOnExactY.mioAbsGap)));

        System.out.println("----- Proposition 5 check on RCSAAEXT y -----");
        System.out.println(String.format(Locale.US,
                "E=%.10f std=%.10f var=%.10f lower=%.10f upper=%.10f dro_fixed_exact_y_obj=%.10f holds=%s",
                exactRiskOnExactY.mean, exactRiskOnExactY.std, exactRiskOnExactY.var,
                exactRiskOnExactY.lowerBound, exactRiskOnExactY.upperBound, droOnExactY.modelObj,
                String.valueOf(exactRiskOnExactY.lowerBound - 1e-8 <= droOnExactY.modelObj
                        && droOnExactY.modelObj <= exactRiskOnExactY.upperBound + 1e-8)));

        System.out.println("----- Proposition 5 check on DRO y -----");
        System.out.println(String.format(Locale.US,
                "E=%.10f std=%.10f var=%.10f lower=%.10f upper=%.10f dro_opt_obj=%.10f holds=%s",
                exactRiskOnDroY.mean, exactRiskOnDroY.std, exactRiskOnDroY.var,
                exactRiskOnDroY.lowerBound, exactRiskOnDroY.upperBound, dro.modelObj,
                String.valueOf(exactRiskOnDroY.lowerBound - 1e-8 <= dro.modelObj
                        && dro.modelObj <= exactRiskOnDroY.upperBound + 1e-8)));

        boolean sameY = sameY(exactSol.y, dro.y);
        System.out.println("----- Compare -----");
        System.out.println("same_y=" + sameY);
        System.out.println(String.format(Locale.US, "model_obj_diff_ext_minus_dro=%.10f", exactSol.objValue - dro.modelObj));
        System.out.println(String.format(Locale.US, "expected_obj_diff_ext_minus_dro=%.10f", exactExpected - dro.expectedObj));
        System.out.println(String.format(Locale.US, "realized_obj_diff_ext_minus_dro=%.10f", exactRec.objValue - dro.realizedObj));
        System.out.println(String.format(Locale.US,
                "dro_fixed_exact_y_minus_dro_opt_model_obj=%.10f",
                droOnExactY.modelObj - dro.modelObj));
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
        cfg.rcsaaSolverVariant = RCSAASolverVariant.DRO_EXTENSIVE;
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
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

    private static double[] empiricalRecourseCosts(ProcurementParams params, List<Sample> train, double[] y) throws Exception {
        double[] q = new double[train.size()];
        for (int i = 0; i < train.size(); i++) {
            q[i] = BatchRunner.RecourseEvaluator.evaluate(params, y, train.get(i).demand().clone()).objValue;
        }
        return q;
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

    private static RiskStats computeRiskStats(ProcurementParams params, List<Sample> train, double[] y, double lambda) throws Exception {
        double[] q = empiricalRecourseCosts(params, train, y);
        double mean = weightedMean(q, train);
        double var = weightedVariance(q, train, mean);
        double std = Math.sqrt(Math.max(var, 0.0));
        double upper = mean + lambda * std;
        double lower = mean + Math.max(lambda * std - lambda * lambda, 0.0);
        RiskStats out = new RiskStats();
        out.mean = mean;
        out.var = var;
        out.std = std;
        out.upperBound = upper;
        out.lowerBound = lower;
        return out;
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

    private static double ess(List<Sample> weightedSamples) {
        double sumW = 0.0;
        double sumW2 = 0.0;
        for (Sample s : weightedSamples) {
            double w = s.weight;
            sumW += w;
            sumW2 += w * w;
        }
        return sumW * sumW / sumW2;
    }

    private static double maxWeightDiff(List<Sample> a, List<Sample> b) {
        double max = 0.0;
        for (int i = 0; i < a.size(); i++) {
            max = Math.max(max, Math.abs(a.get(i).weight - b.get(i).weight));
        }
        return max;
    }

    private static String encodeWeights(List<Sample> weightedSamples) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < weightedSamples.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(String.format(Locale.US, "%.10f", weightedSamples.get(i).weight));
        }
        return sb.toString();
    }

    private static int countSelected(double[] y) {
        int count = 0;
        for (double v : y) {
            if (v > 0.5) count++;
        }
        return count;
    }

    private static String encodeY(double[] y) {
        StringBuilder sb = new StringBuilder(y.length);
        for (double v : y) {
            sb.append(v > 0.5 ? '1' : '0');
        }
        return sb.toString();
    }

    private static String encodeSelectedCarriers(double[] y) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < y.length; i++) {
            if (y[i] > 0.5) {
                if (sb.length() > 0) sb.append('|');
                sb.append(i);
            }
        }
        return sb.toString();
    }

    private static boolean sameY(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if ((a[i] > 0.5) != (b[i] > 0.5)) {
                return false;
            }
        }
        return true;
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "Inf" : "-Inf";
        return String.format(Locale.US, "%.10f", v);
    }

    private static DroDiagnostic solveDroWithGap(List<String> lanes,
                                                 List<Sample> train,
                                                 CovariateVector thetaNow,
                                                 ProcurementParams params,
                                                 Config cfg,
                                                 double[] dTest) throws Exception {
        return solveDroInternal(train, params, cfg, dTest, null);
    }

    private static DroDiagnostic solveDroWithGapFixedY(List<String> lanes,
                                                       List<Sample> train,
                                                       CovariateVector thetaNow,
                                                       ProcurementParams params,
                                                       Config cfg,
                                                       double[] dTest,
                                                       double[] fixedY) throws Exception {
        return solveDroInternal(train, params, cfg, dTest, fixedY);
    }

    private static DroDiagnostic solveDroInternal(List<Sample> train,
                                                  ProcurementParams params,
                                                  Config cfg,
                                                  double[] dTest,
                                                  double[] fixedY) throws Exception {
        int Wn = train.size();
        int I = params.I;
        int J = params.J;

        double[] pi = new double[Wn];
        double[] sqrtPi = new double[Wn];
        double[] invSqrtPi = new double[Wn];
        double totalMass = 0.0;
        for (int w = 0; w < Wn; w++) {
            double pw = Math.max(train.get(w).weight, 1e-8);
            pi[w] = pw;
            totalMass += pw;
        }
        for (int w = 0; w < Wn; w++) {
            pi[w] /= totalMass;
            sqrtPi[w] = Math.sqrt(pi[w]);
            invSqrtPi[w] = 1.0 / sqrtPi[w];
        }

        try (Model M = new Model("DRO-Extensive-Diag")) {
            Variable y = M.variable("y", I, Domain.binary());
            Variable eta = M.variable("eta", 1, Domain.unbounded());
            Variable psi = M.variable("psi", Wn, Domain.unbounded());
            Variable nu = M.variable("nu", 1, Domain.greaterThan(0.0));
            Variable x = M.variable("x", new int[]{I, J, Wn}, Domain.greaterThan(0.0));
            Variable s = M.variable("s", new int[]{J, Wn}, Domain.greaterThan(0.0));
            Variable u = M.variable("u", new int[]{I, Wn}, Domain.greaterThan(0.0));

            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    if (params.eligible[i][j]) continue;
                    for (int w = 0; w < Wn; w++) {
                        M.constraint(x.index(i, j, w), Domain.equalsTo(0.0));
                    }
                }
            }

            Expression sumY = Expr.sum(y);
            M.constraint(sumY, Domain.greaterThan(params.alpha));
            M.constraint(sumY, Domain.lessThan(params.beta));
            M.constraint(Expr.vstack(nu, psi), Domain.inQCone());
            if (fixedY != null) {
                for (int i = 0; i < I; i++) {
                    M.constraint(y.index(i), Domain.equalsTo(fixedY[i] > 0.5 ? 1.0 : 0.0));
                }
            }

            for (int w = 0; w < Wn; w++) {
                double[] d = train.get(w).demand();
                for (int j = 0; j < J; j++) {
                    Expression lhs = Expr.add(Expr.sum(x.slice(new int[]{0, j, w}, new int[]{I, j + 1, w + 1})),
                            s.index(j, w));
                    M.constraint(lhs, Domain.greaterThan(d[j]));
                }
                for (int i = 0; i < I; i++) {
                    Expression sumX = Expr.sum(x.slice(new int[]{i, 0, w}, new int[]{i + 1, J, w + 1}));
                    Expression rhsLow = Expr.sub(Expr.mul(params.p[i], y.index(i)), u.index(i, w));
                    M.constraint(Expr.sub(sumX, rhsLow), Domain.greaterThan(0.0));
                    Expression rhsUp = Expr.mul(params.M[i], y.index(i));
                    M.constraint(Expr.sub(sumX, rhsUp), Domain.lessThan(0.0));
                    for (int j = 0; j < J; j++) {
                        if (!params.eligible[i][j]) continue;
                        Expression capRhs = Expr.mul(params.q[i][j], y.index(i));
                        M.constraint(Expr.sub(x.index(i, j, w), capRhs), Domain.lessThan(0.0));
                    }
                }

                List<Expression> exprs = new ArrayList<>();
                for (int i = 0; i < I; i++) {
                    for (int j = 0; j < J; j++) {
                        if (!params.eligible[i][j]) continue;
                        exprs.add(Expr.mul(params.r[i][j], x.index(i, j, w)));
                    }
                }
                for (int j = 0; j < J; j++) exprs.add(Expr.mul(params.e[j], s.index(j, w)));
                for (int i = 0; i < I; i++) exprs.add(Expr.mul(params.h[i], u.index(i, w)));
                Expression scenarioCost = exprs.isEmpty() ? Expr.constTerm(0.0) : Expr.add(exprs.toArray(new Expression[0]));
                Expression rhsEta = Expr.add(scenarioCost, Expr.mul(invSqrtPi[w], psi.index(w)));
                M.constraint(Expr.sub(eta, rhsEta), Domain.greaterThan(0.0));
            }

            Expression obj = eta;
            obj = Expr.sub(obj, Expr.dot(sqrtPi, psi));
            obj = Expr.add(obj, Expr.mul(cfg.lambda, nu));
            M.objective(ObjectiveSense.Minimize, obj);
            M.setSolverParam("numThreads", cfg.threads);
            M.setSolverParam("mioMaxTime", cfg.timeLimitSeconds);
            M.solve();

            double[] yVal = y.level();
            BatchRunner.RecourseEvaluator.RecourseEval rec =
                    BatchRunner.RecourseEvaluator.evaluate(params, yVal, dTest.clone());

            DroDiagnostic out = new DroDiagnostic();
            out.y = yVal;
            out.modelObj = M.primalObjValue();
            out.expectedObj = weightedMean(empiricalRecourseCosts(params, train, yVal), train);
            out.realizedObj = rec.objValue;
            out.problemStatus = String.valueOf(M.getProblemStatus());
            out.mioRelGap = trySolverDoubleInfo(M, "mioObjRelGap");
            out.mioAbsGap = trySolverDoubleInfo(M, "mioObjAbsGap");
            return out;
        }
    }

    private static double trySolverDoubleInfo(Model model, String key) {
        try {
            return model.getSolverDoubleInfo(key);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    private static final class TrainWindow {
        final List<Sample> trainSamples;
        final Sample testSample;

        TrainWindow(List<Sample> trainSamples, Sample testSample) {
            this.trainSamples = trainSamples;
            this.testSample = testSample;
        }
    }

    private static final class DroDiagnostic {
        double[] y;
        double modelObj;
        double expectedObj;
        double realizedObj;
        String problemStatus;
        double mioRelGap;
        double mioAbsGap;
    }

    private static final class RiskStats {
        double mean;
        double var;
        double std;
        double lowerBound;
        double upperBound;
    }
}
