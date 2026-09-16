package Test;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloNumVarType;
import ilog.cplex.IloCplex;

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
 * Diagnoses whether the current >= demand constraints create positive excess
 * allocation on the 51 selected RCSAA out-of-sample weeks. The first-stage y
 * vectors are read from the existing final-result CSV. For each y and realized
 * weekly demand, the same recourse LP is solved once with >= and once with =.
 */
public final class Constraint6EqualityDiagnostic {
    private static final double TOL = 1e-6;
    private static final int NUM_CARRIERS = 10;

    private static final Path WEEKLY_INPUT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                    + "按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");

    private static final Path SELECTED_CSV = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/"
                    + "03_35_15_滚动CV结果/02_RCSAAEXT_精确枚举结果/"
                    + "02_RCSAAEXT_35_15_CV选参最终选中参数结果/"
                    + "RCSAAEXT_35_15_CV选参_最终选中参数对应的样本外逐trial结果.csv");

    private static final Path OUT_DIR = Paths.get(
            "记录/修稿记录_20260806_TRB/约束6诊断_20260807");

    private Constraint6EqualityDiagnostic() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT_DIR);

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY_INPUT);
        Config baseCfg = buildConfig(3);
        ProcurementParams params = InstanceGenerator.generate(
                NUM_CARRIERS,
                buildBaselineDemand(weekly.periods),
                new InstanceGenerator.GenConfig(),
                baseCfg);

        List<Row> rows = loadRows(SELECTED_CSV);
        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (Row row : rows) {
            samplesByK.computeIfAbsent(row.k, k -> {
                try {
                    return SampleBuilder.buildFromPeriods(weekly.periods, weekly.laneNames, buildConfig(k)).samples;
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        }

        Path trialCsv = OUT_DIR.resolve("constraint6_trial_summary.csv");
        Path laneCsv = OUT_DIR.resolve("constraint6_lane_detail.csv");

        int trialsWithExcess = 0;
        int lanesWithExcess = 0;
        double totalExcess = 0.0;
        double maxExcess = 0.0;
        double totalObjectiveIncrease = 0.0;

        try (BufferedWriter trialOut = Files.newBufferedWriter(trialCsv, StandardCharsets.UTF_8);
             BufferedWriter laneOut = Files.newBufferedWriter(laneCsv, StandardCharsets.UTF_8)) {
            trialOut.write("trialId,testPeriodIdx,k,selectedCount,demandTotal,geObjective,eqObjective,"
                    + "eqMinusGe,geTransportCost,geSpotCost,gePenaltyCost,eqTransportCost,eqSpotCost,"
                    + "eqPenaltyCost,geExcessTotal,geExcessMax,geLanesWithExcess,yBinary");
            trialOut.newLine();
            laneOut.write("trialId,testPeriodIdx,lane,laneName,demand,geContract,geSpot,geTotal,geExcess,"
                    + "eqContract,eqSpot,eqTotal,eqResidual");
            laneOut.newLine();

            for (Row row : rows) {
                List<Sample> samples = samplesByK.get(row.k);
                int testSampleIdx = row.testPeriodIdx - row.k;
                if (testSampleIdx < 0 || testSampleIdx >= samples.size()) {
                    throw new IllegalArgumentException("Invalid test sample index for trial " + row.trialId);
                }
                double[] demand = samples.get(testSampleIdx).demand().clone();
                SolveResult ge = solve(params, row.y, demand, false);
                SolveResult eq = solve(params, row.y, demand, true);

                int trialLaneCount = 0;
                double trialExcess = 0.0;
                double trialMax = 0.0;
                double demandTotal = 0.0;
                for (int j = 0; j < demand.length; j++) {
                    double geTotal = ge.contractQty[j] + ge.spotQty[j];
                    double eqTotal = eq.contractQty[j] + eq.spotQty[j];
                    double excess = Math.max(0.0, geTotal - demand[j]);
                    double eqResidual = eqTotal - demand[j];
                    demandTotal += demand[j];
                    trialExcess += excess;
                    trialMax = Math.max(trialMax, excess);
                    if (excess > TOL) {
                        trialLaneCount++;
                    }
                    laneOut.write(String.format(Locale.US,
                            "%d,%d,%d,\"%s\",%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                            row.trialId, row.testPeriodIdx, j,
                            weekly.laneNames.get(j).replace("\"", "\"\""), demand[j],
                            ge.contractQty[j], ge.spotQty[j], geTotal, excess,
                            eq.contractQty[j], eq.spotQty[j], eqTotal, eqResidual));
                }

                double increase = eq.objective - ge.objective;
                if (trialLaneCount > 0) {
                    trialsWithExcess++;
                }
                lanesWithExcess += trialLaneCount;
                totalExcess += trialExcess;
                maxExcess = Math.max(maxExcess, trialMax);
                totalObjectiveIncrease += increase;

                trialOut.write(String.format(Locale.US,
                        "%d,%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%d,\"%s\"%n",
                        row.trialId, row.testPeriodIdx, row.k, countSelected(row.y), demandTotal,
                        ge.objective, eq.objective, increase,
                        ge.transportCost, ge.spotCost, ge.penaltyCost,
                        eq.transportCost, eq.spotCost, eq.penaltyCost,
                        trialExcess, trialMax, trialLaneCount, encodeY(row.y)));
            }
        }

        Path summary = OUT_DIR.resolve("constraint6_summary.txt");
        String text = String.format(Locale.US,
                "source=%s%ntrials=%d%nlanes_per_trial=%d%ntrials_with_excess_gt_1e-6=%d%n"
                        + "lane_observations_with_excess_gt_1e-6=%d%n"
                        + "total_excess=%.10f%nmax_lane_excess=%.10f%n"
                        + "mean_eq_minus_ge_objective=%.10f%ntrial_csv=%s%nlane_csv=%s%n",
                SELECTED_CSV.toAbsolutePath(), rows.size(), weekly.laneNames.size(),
                trialsWithExcess, lanesWithExcess, totalExcess, maxExcess,
                totalObjectiveIncrease / Math.max(1, rows.size()),
                trialCsv.toAbsolutePath(), laneCsv.toAbsolutePath());
        Files.writeString(summary, text, StandardCharsets.UTF_8);
        System.out.print(text);
    }

    private static SolveResult solve(ProcurementParams params, double[] y, double[] demand, boolean equality)
            throws Exception {
        int iSize = params.I;
        int jSize = params.J;
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);

            IloNumVar[][] x = new IloNumVar[iSize][jSize];
            for (int i = 0; i < iSize; i++) {
                for (int j = 0; j < jSize; j++) {
                    double ub = params.eligible[i][j] ? params.q[i][j] : 0.0;
                    x[i][j] = cplex.numVar(0.0, ub, IloNumVarType.Float);
                }
            }
            IloNumVar[] spot = new IloNumVar[jSize];
            for (int j = 0; j < jSize; j++) {
                spot[j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float);
            }
            IloNumVar[] shortfall = new IloNumVar[iSize];
            for (int i = 0; i < iSize; i++) {
                shortfall[i] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float);
            }

            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int i = 0; i < iSize; i++) {
                for (int j = 0; j < jSize; j++) {
                    objective.addTerm(params.r[i][j], x[i][j]);
                }
            }
            for (int j = 0; j < jSize; j++) {
                objective.addTerm(params.e[j], spot[j]);
            }
            for (int i = 0; i < iSize; i++) {
                objective.addTerm(params.h[i], shortfall[i]);
            }
            cplex.addMinimize(objective);

            for (int j = 0; j < jSize; j++) {
                IloLinearNumExpr balance = cplex.linearNumExpr();
                for (int i = 0; i < iSize; i++) {
                    balance.addTerm(1.0, x[i][j]);
                }
                balance.addTerm(1.0, spot[j]);
                if (equality) {
                    cplex.addEq(balance, demand[j]);
                } else {
                    cplex.addGe(balance, demand[j]);
                }
            }

            for (int i = 0; i < iSize; i++) {
                double yi = y[i] > 0.5 ? 1.0 : 0.0;
                IloLinearNumExpr sumX = cplex.linearNumExpr();
                for (int j = 0; j < jSize; j++) {
                    sumX.addTerm(1.0, x[i][j]);
                }
                IloLinearNumExpr lower = cplex.linearNumExpr(params.p[i] * yi);
                lower.addTerm(-1.0, shortfall[i]);
                cplex.addLe(lower, sumX);
                cplex.addLe(sumX, params.M[i] * yi);
            }

            if (!cplex.solve()) {
                throw new IllegalStateException("Recourse LP did not solve; equality=" + equality);
            }

            SolveResult out = new SolveResult(jSize);
            out.objective = cplex.getObjValue();
            for (int j = 0; j < jSize; j++) {
                for (int i = 0; i < iSize; i++) {
                    double value = cplex.getValue(x[i][j]);
                    out.contractQty[j] += value;
                    out.transportCost += params.r[i][j] * value;
                }
                out.spotQty[j] = cplex.getValue(spot[j]);
                out.spotCost += params.e[j] * out.spotQty[j];
            }
            for (int i = 0; i < iSize; i++) {
                out.penaltyCost += params.h[i] * cplex.getValue(shortfall[i]);
            }
            return out;
        }
    }

    private static Config buildConfig(int k) {
        Config cfg = new Config();
        cfg.fillMissingDates = false;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = k;
        cfg.demandAgg = false;
        cfg.lagDemandAsShare = false;
        cfg.featureFlags.includeLagDemand = true;
        cfg.featureFlags.includeHolidayCount = false;
        cfg.featureFlags.includeFreightIndex = false;
        cfg.featureFlags.includeConsumptionIndex = false;
        cfg.featureFlags.includeWEIIndex = false;
        cfg.standardizeTheta = true;
        cfg.seed = 0;
        cfg.writeCplexLogToFile = false;
        return cfg;
    }

    private static double[] buildBaselineDemand(List<PeriodData> periods) {
        int jSize = periods.get(0).demandSum.length;
        double[] mean = new double[jSize];
        for (PeriodData period : periods) {
            for (int j = 0; j < jSize; j++) {
                mean[j] += period.demandSum[j];
            }
        }
        for (int j = 0; j < jSize; j++) {
            mean[j] /= Math.max(1, periods.size());
        }
        return mean;
    }

    private static List<Row> loadRows(Path csv) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            String[] header = parseCsvLine(headerLine);
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                index.put(header[i].replace("\uFEFF", "").replace("\"", "").trim(), i);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = parseCsvLine(line);
                Row row = new Row();
                row.trialId = parseInt(fields[index.get("trialId")]);
                row.testPeriodIdx = parseInt(fields[index.get("actual_test_period")]);
                row.k = parseInt(fields[index.get("selected_k")]);
                row.y = parseY(fields[index.get("yBinary")]);
                rows.add(row);
            }
        }
        return rows;
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static int parseInt(String value) {
        return (int) Math.round(Double.parseDouble(value.replace("\"", "").trim()));
    }

    private static double[] parseY(String value) {
        String cleaned = value.replace("\"", "").replace("[", "").replace("]", "").trim();
        String[] parts = cleaned.split(",");
        double[] y = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            y[i] = Double.parseDouble(parts[i].trim());
        }
        return y;
    }

    private static int countSelected(double[] y) {
        int count = 0;
        for (double value : y) {
            if (value > 0.5) {
                count++;
            }
        }
        return count;
    }

    private static String encodeY(double[] y) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < y.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(y[i] > 0.5 ? '1' : '0');
        }
        return out.append(']').toString();
    }

    private static final class Row {
        int trialId;
        int testPeriodIdx;
        int k;
        double[] y;
    }

    private static final class SolveResult {
        final double[] contractQty;
        final double[] spotQty;
        double objective;
        double transportCost;
        double spotCost;
        double penaltyCost;

        SolveResult(int lanes) {
            contractQty = new double[lanes];
            spotQty = new double[lanes];
        }
    }
}
