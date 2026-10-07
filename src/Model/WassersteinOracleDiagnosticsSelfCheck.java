package Model;

import Basic.ProcurementParams;
import ilog.cplex.IloCplex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Native regression gate for opt-in logging and replayable SAV export. */
public final class WassersteinOracleDiagnosticsSelfCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected empty output directory");
        Path output = Path.of(args[0]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Use an empty output directory");
        var params = new ProcurementParams(List.of("carrier"), 1, new double[]{10},
                new double[]{2}, new double[]{3}, new double[][]{{5}}, new double[][]{{4}},
                new boolean[][]{{true}}, 1, 1);
        var input = new WassersteinBoxInput(params, new double[][]{{4}}, new double[]{1},
                new double[]{0}, new double[]{8}, new double[]{8}, .025);
        System.clearProperty("trb.svu.w1OracleDiagnosticDirectory");
        var plain = WassersteinBoxOracle.solve(input, 0, new double[]{1}, 20, 1, 30);
        System.setProperty("trb.svu.w1OracleDiagnosticDirectory", output.toString());
        WassersteinBoxOracle.Result logged;
        try {
            logged = WassersteinBoxOracle.solve(input, 0, new double[]{1}, 20, 1, 30);
            // Exercise replacement of the current snapshot and append of the same log.
            WassersteinBoxOracle.solve(input, 0, new double[]{1}, 20, 1, 30);
        } finally { System.clearProperty("trb.svu.w1OracleDiagnosticDirectory"); }
        if (Math.abs(plain.value() - logged.value()) > 1e-8
                || Math.abs(plain.upperBound() - logged.upperBound()) > 1e-8
                || !Arrays.equals(plain.worstDemand(), logged.worstDemand()))
            throw new AssertionError("Logging changed oracle result");
        String log = Files.readString(output.resolve("oracle.log"), StandardCharsets.UTF_8);
        if (!log.contains("W1_ORACLE_BEGIN") || !log.contains("W1_ORACLE_BOUNDS")
                || !log.contains("sequence=2") || !Files.isRegularFile(output.resolve("current_oracle.txt")))
            throw new AssertionError("Incomplete diagnostics");
        var replay = new IloCplex();
        try {
            replay.setOut(null);
            replay.setParam(IloCplex.Param.Threads, 1);
            replay.setParam(IloCplex.Param.TimeLimit, 30);
            replay.importModel(output.resolve("current_oracle.sav").toString());
            if (!replay.solve() || Math.abs(replay.getObjValue() - plain.value()) > 1e-8)
                throw new AssertionError("Exported model replay differs");
        } finally { replay.end(); }
        System.out.println("W1_ORACLE_DIAGNOSTICS_SELF_CHECK_PASS objective=" + plain.value());
    }
}
