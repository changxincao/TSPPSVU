package Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Merges chunk-level outputs produced by the exact RCSAA full-lambda sweep.
 * It creates consolidated rows and summary tables used by later directory cleanup, Excel comparisons,
 * and paper-ready result aggregation.
 */
public class BrazilOlistExactLambdaOosSweepMerge {

    private static final int EXPECTED_LAMBDA_COUNT = 8;

    private static final Path IN_ROOT = Paths.get(
            "analysis/宸磋タ鏁版嵁鍒嗘瀽/鏂扮増_purchase鏃堕棿/杈撳嚭/缁撴灉/"
                    + "09_RCSAA鍥哄畾kC_鍏ㄩ儴lambda鏍锋湰澶栨祴璇?");

    private static final Path OUT_ROOT = Paths.get(
            "analysis/宸磋タ鏁版嵁鍒嗘瀽/鏂扮増_purchase鏃堕棿/杈撳嚭/缁撴灉/"
                    + "09_RCSAA鍥哄畾kC_鍏ㄩ儴lambda鏍锋湰澶栨祴璇?姹囨€荤粨鏋?");

    public static void main(String[] args) throws Exception {
        Path inRoot = args.length > 0 ? Paths.get(args[0]) : IN_ROOT;
        Path outRoot = args.length > 1 ? Paths.get(args[1]) : OUT_ROOT;
        Files.createDirectories(outRoot);

        List<Row> rows = loadAllRows(inRoot);
        rows.sort(Comparator
                .comparingInt((Row r) -> r.trialId)
                .thenComparingDouble(r -> r.lambda));

        Path mergedRowsCsv = outRoot.resolve("lambda_oos_scan_rows_merged.csv");
        Path mergedTrialCsv = outRoot.resolve("lambda_oos_scan_trial_summary_merged.csv");
        Path overviewCsv = outRoot.resolve("lambda_oos_scan_overview.csv");

        writeMergedRows(mergedRowsCsv, rows);
        List<TrialSummary> summaries = summarize(rows);
        writeTrialSummaries(mergedTrialCsv, summaries);
        writeOverview(overviewCsv, summaries);

        System.out.println("rows=" + rows.size());
        System.out.println("trials=" + summaries.size());
        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static List<Row> loadAllRows(Path inRoot) throws Exception {
        List<Row> out = new ArrayList<>();
        for (Path p : findFilesRecursive(inRoot, "lambda_oos_scan_rows.csv")) {
            String chunk = p.getParent().getFileName().toString();
            try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                Map<String, Integer> h = headerMap(parseCsvLine(br.readLine()));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    List<String> f = parseCsvLine(line);
                    Row r = new Row();
                    r.chunk = chunk;
                    r.trialId = parseInt(f.get(require(h, "trialId")));
                    r.testPeriodIdx = parseInt(f.get(require(h, "testPeriodIdx")));
                    r.fixedK = parseInt(f.get(require(h, "fixed_k")));
                    r.fixedCH = parseDouble(f.get(require(h, "fixed_C_h")));
                    r.selectedLambda = parseDouble(f.get(require(h, "selected_lambda")));
                    r.lambda = parseDouble(f.get(require(h, "lambda")));
                    r.expectedObj = parseDouble(f.get(require(h, "expected_obj")));
                    r.modelObj = parseDouble(f.get(require(h, "model_obj")));
                    r.realizedObj = parseDouble(f.get(require(h, "realized_obj")));
                    r.solveTimeSec = parseDouble(f.get(require(h, "solve_time_sec")));
                    r.selectedCount = parseInt(f.get(require(h, "selected_count")));
                    r.transport = parseDouble(f.get(require(h, "oos_transport_cost")));
                    r.spot = parseDouble(f.get(require(h, "oos_spot_cost")));
                    r.penalty = parseDouble(f.get(require(h, "oos_penalty_cost")));
                    r.ess = parseDouble(f.get(require(h, "ESS")));
                    r.top1W = parseDouble(f.get(require(h, "top1W")));
                    r.top5Wsum = parseDouble(f.get(require(h, "top5Wsum")));
                    out.add(r);
                }
            }
        }
        return out;
    }

    private static List<TrialSummary> summarize(List<Row> rows) {
        Map<Integer, List<Row>> grouped = new LinkedHashMap<>();
        for (Row row : rows) grouped.computeIfAbsent(row.trialId, k -> new ArrayList<>()).add(row);

        List<TrialSummary> out = new ArrayList<>();
        for (Map.Entry<Integer, List<Row>> e : grouped.entrySet()) {
            List<Row> rs = e.getValue();
            rs.sort(Comparator.comparingDouble(r -> r.lambda));

            TrialSummary s = new TrialSummary();
            Row first = rs.get(0);
            s.trialId = first.trialId;
            s.testPeriodIdx = first.testPeriodIdx;
            s.fixedK = first.fixedK;
            s.fixedCH = first.fixedCH;
            s.selectedLambda = first.selectedLambda;
            s.observedLambdaCount = rs.size();
            s.isComplete = (rs.size() == EXPECTED_LAMBDA_COUNT);

            Row selected = null;
            Row bestRealized = null;
            Row bestExpected = null;
            int rank = 1;
            List<Row> realizedSorted = new ArrayList<>(rs);
            realizedSorted.sort(Comparator.comparingDouble(r -> r.realizedObj));
            for (Row r : realizedSorted) {
                if (Math.abs(r.lambda - s.selectedLambda) <= 1e-9 && selected == null) {
                    s.selectedLambdaRankByRealizedObserved = rank;
                }
                rank++;
            }

            for (Row r : rs) {
                if (Math.abs(r.lambda - s.selectedLambda) <= 1e-9) selected = r;
                if (bestRealized == null || r.realizedObj < bestRealized.realizedObj) bestRealized = r;
                if (bestExpected == null || r.expectedObj < bestExpected.expectedObj) bestExpected = r;
            }

            s.selectedRowExists = selected != null;
            if (selected != null) {
                s.selectedRealizedObj = selected.realizedObj;
                s.selectedExpectedObj = selected.expectedObj;
                s.selectedSolveTimeSec = selected.solveTimeSec;
            }
            if (bestRealized != null) {
                s.bestOosLambda = bestRealized.lambda;
                s.bestOosRealizedObj = bestRealized.realizedObj;
            }
            if (bestExpected != null) {
                s.bestExpectedLambda = bestExpected.lambda;
                s.bestExpectedObj = bestExpected.expectedObj;
            }
            s.isSelectedLambdaBestOosObserved =
                    selected != null && bestRealized != null && Math.abs(selected.lambda - bestRealized.lambda) <= 1e-9;
            out.add(s);
        }
        out.sort(Comparator.comparingInt(t -> t.trialId));
        return out;
    }

    private static void writeMergedRows(Path csv, List<Row> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write("chunk,trialId,testPeriodIdx,fixed_k,fixed_C_h,selected_lambda,lambda,expected_obj,model_obj,realized_obj,solve_time_sec,selected_count,oos_transport_cost,oos_spot_cost,oos_penalty_cost,ESS,top1W,top5Wsum");
            bw.newLine();
            for (Row r : rows) {
                bw.write(String.format(Locale.US,
                        "%s,%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                        csvEscape(r.chunk), r.trialId, r.testPeriodIdx, r.fixedK, r.fixedCH, r.selectedLambda, r.lambda,
                        r.expectedObj, r.modelObj, r.realizedObj, r.solveTimeSec, r.selectedCount,
                        r.transport, r.spot, r.penalty, r.ess, r.top1W, r.top5Wsum));
            }
        }
    }

    private static void writeTrialSummaries(Path csv, List<TrialSummary> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write("trialId,testPeriodIdx,fixed_k,fixed_C_h,selected_lambda,observed_lambda_count,is_complete,selected_row_exists,selected_realized_obj,selected_expected_obj,selected_solve_time_sec,best_oos_lambda,best_oos_realized_obj,best_expected_lambda,best_expected_obj,selected_lambda_rank_by_realized_observed,is_selected_lambda_best_oos_observed");
            bw.newLine();
            for (TrialSummary r : rows) {
                bw.write(String.format(Locale.US,
                        "%d,%d,%d,%.10f,%.10f,%d,%s,%s,%.10f,%.10f,%.6f,%.10f,%.10f,%.10f,%.10f,%d,%s%n",
                        r.trialId, r.testPeriodIdx, r.fixedK, r.fixedCH, r.selectedLambda,
                        r.observedLambdaCount, String.valueOf(r.isComplete), String.valueOf(r.selectedRowExists),
                        r.selectedRealizedObj, r.selectedExpectedObj, r.selectedSolveTimeSec,
                        r.bestOosLambda, r.bestOosRealizedObj, r.bestExpectedLambda, r.bestExpectedObj,
                        r.selectedLambdaRankByRealizedObserved, String.valueOf(r.isSelectedLambdaBestOosObserved)));
            }
        }
    }

    private static void writeOverview(Path csv, List<TrialSummary> rows) throws Exception {
        int complete = 0, selectedExists = 0, selectedBest = 0;
        for (TrialSummary r : rows) {
            if (r.isComplete) complete++;
            if (r.selectedRowExists) selectedExists++;
            if (r.isSelectedLambdaBestOosObserved) selectedBest++;
        }
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write("trial_count,complete_trial_count,selected_row_exists_count,selected_best_oos_count");
            bw.newLine();
            bw.write(String.format(Locale.US, "%d,%d,%d,%d%n", rows.size(), complete, selectedExists, selectedBest));
        }
    }

    private static List<Path> findFilesRecursive(Path root, String fileName) throws Exception {
        List<Path> out = new ArrayList<>();
        if (!Files.exists(root)) return out;
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static Map<String, Integer> headerMap(List<String> cols) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < cols.size(); i++) out.put(cols.get(i).replace("\uFEFF", "").trim(), i);
        return out;
    }

    private static int require(Map<String, Integer> header, String name) {
        Integer idx = header.get(name);
        if (idx == null) throw new IllegalArgumentException("Missing column: " + name);
        return idx;
    }

    private static int parseInt(String s) { return Integer.parseInt(s.trim()); }
    private static double parseDouble(String s) { return Double.parseDouble(s.trim()); }
    private static String csvEscape(String s) {
        if (s == null) return "";
        if (!s.contains(",") && !s.contains("\"")) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static final class Row {
        String chunk;
        int trialId, testPeriodIdx, fixedK, selectedCount;
        double fixedCH, selectedLambda, lambda, expectedObj, modelObj, realizedObj, solveTimeSec;
        double transport, spot, penalty, ess, top1W, top5Wsum;
    }

    private static final class TrialSummary {
        int trialId, testPeriodIdx, fixedK, observedLambdaCount, selectedLambdaRankByRealizedObserved;
        double fixedCH, selectedLambda, selectedRealizedObj, selectedExpectedObj, selectedSolveTimeSec;
        double bestOosLambda, bestOosRealizedObj, bestExpectedLambda, bestExpectedObj;
        boolean isComplete, selectedRowExists, isSelectedLambdaBestOosObserved;
    }
}
