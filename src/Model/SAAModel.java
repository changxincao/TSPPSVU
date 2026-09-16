package Model;

import ilog.concert.*;
import ilog.cplex.IloCplex;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.OutputManager;

public class SAAModel {

    public Solution solve(Data data, Config cfg, OutputManager out) throws Exception {
        ProcurementParams P = data.params;
        List<Sample> samples = data.samples;
        int W = samples.size();

        IloCplex cplex = new IloCplex();

        // CPLEX params
        cplex.setParam(IloCplex.Param.Threads, cfg.threads);
        cplex.setParam(IloCplex.Param.TimeLimit, cfg.timeLimitSeconds);

        // log handling
        PrintStream ps = null;
        if (cfg.writeCplexLogToFile && out != null) {
            Path logFile = out.logsDir.resolve("cplex.log");
            ps = new PrintStream(new FileOutputStream(logFile.toFile()));
            cplex.setOut(ps);
            cplex.setWarning(ps);
        } else if (cfg.writeSolverLogToConsole) {
            cplex.setOut(System.out);
            cplex.setWarning(System.err);
        } else {
            cplex.setOut(null);
        }

        // --------- Variables ----------
        // First-stage y_i
        IloNumVar[] y = new IloNumVar[P.I];
        for (int i = 0; i < P.I; i++) {
            y[i] = cplex.boolVar("y_" + i);
        }

        // Second-stage vars per scenario w
        // x[i][j][w] only if eligible; store as 3D but could be large
        IloNumVar[][][] x = new IloNumVar[P.I][P.J][W];
        IloNumVar[][] s = new IloNumVar[P.J][W];
        IloNumVar[][] u = new IloNumVar[P.I][W];

        for (int w = 0; w < W; w++) {
            for (int j = 0; j < P.J; j++) {
                s[j][w] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, "s_" + j + "_w" + w);
            }
            for (int i = 0; i < P.I; i++) {
                u[i][w] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, "u_" + i + "_w" + w);
                for (int j = 0; j < P.J; j++) {
                    if (!P.eligible[i][j]) continue;
                    x[i][j][w] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, "x_" + i + "_" + j + "_w" + w);
                }
            }
        }

        // --------- Objective ----------
        IloLinearNumExpr obj = cplex.linearNumExpr();
        for (int w = 0; w < W; w++) {
            double pw = samples.get(w).weight;
            // contracted cost + spot + MQC penalty
            for (int i = 0; i < P.I; i++) {
                for (int j = 0; j < P.J; j++) {
                    if (!P.eligible[i][j]) continue;
                    obj.addTerm(pw * P.r[i][j], x[i][j][w]);
                }
                obj.addTerm(pw * P.h[i], u[i][w]);
            }
            for (int j = 0; j < P.J; j++) {
                obj.addTerm(pw * P.e[j], s[j][w]);
            }
        }
        cplex.addMinimize(obj);

        // --------- Constraints ----------
        // First-stage: alpha <= sum y <= beta
        IloLinearNumExpr sumY = cplex.linearNumExpr();
        for (int i = 0; i < P.I; i++) sumY.addTerm(1.0, y[i]);
        cplex.addGe(sumY, P.alpha, "minSelected");
        cplex.addLe(sumY, P.beta, "maxSelected");

        // Scenario-wise constraints
        for (int w = 0; w < W; w++) {
            double[] d = samples.get(w).demand();
            // Demand satisfaction per lane: sum_i x_ij^w + s_j^w >= d_j^w
            for (int j = 0; j < P.J; j++) {
                IloLinearNumExpr lhs = cplex.linearNumExpr();
                for (int i = 0; i < P.I; i++) {
                    if (!P.eligible[i][j]) continue;
                    lhs.addTerm(1.0, x[i][j][w]);
                }
                lhs.addTerm(1.0, s[j][w]);
                if (cfg.enforceDemandEquality) {
                    cplex.addEq(lhs, d[j], "dem_j" + j + "_w" + w);
                } else {
                    cplex.addGe(lhs, d[j], "dem_j" + j + "_w" + w);
                }
            }

            // For each carrier: p_i*y_i - u_i^w <= sum_j x_ij^w <= M_i*y_i
            for (int i = 0; i < P.I; i++) {
                IloLinearNumExpr sumX = cplex.linearNumExpr();
                for (int j = 0; j < P.J; j++) {
                    if (!P.eligible[i][j]) continue;
                    sumX.addTerm(1.0, x[i][j][w]);
                }

                // lower: p_i y_i - u_i^w <= sumX
                IloLinearNumExpr lower = cplex.linearNumExpr();
                lower.addTerm(P.p[i], y[i]);
                lower.addTerm(-1.0, u[i][w]);
                cplex.addLe(lower, sumX, "mqcLow_i" + i + "_w" + w);

                // upper: sumX <= M_i y_i
                IloLinearNumExpr upper = cplex.linearNumExpr();
                upper.addTerm(P.M[i], y[i]);
                cplex.addLe(sumX, upper, "capTot_i" + i + "_w" + w);

                // lane caps: x_ij^w <= q_ij * y_i
                for (int j = 0; j < P.J; j++) {
                    if (!P.eligible[i][j]) continue;
                    IloLinearNumExpr rhs = cplex.linearNumExpr();
                    rhs.addTerm(P.q[i][j], y[i]);
                    cplex.addLe(x[i][j][w], rhs, "capLane_i" + i + "_j" + j + "_w" + w);
                }
            }
        }

        // --------- Solve ----------
        long t0 = System.nanoTime();
        boolean ok = cplex.solve();
        long t1 = System.nanoTime();

        if (!ok) {
            String status;
            try {
                status = String.valueOf(cplex.getStatus());
            } catch (Exception e) {
                status = "UNKNOWN(" + e.getClass().getSimpleName() + ")";
            }
            if (ps != null) ps.close();
            cplex.end();
            throw new IllegalStateException("CPLEX failed. Status=" + status);
        }

        double objVal = cplex.getObjValue();
        double[] yVal = new double[P.I];
        for (int i = 0; i < P.I; i++) yVal[i] = ((cplex.getValue(y[i])>0.5)?1:0);

        double timeSec = (t1 - t0) / 1e9;

        String solverStatus = String.valueOf(cplex.getStatus());
        boolean certifiedOptimal = cplex.getStatus() == IloCplex.Status.Optimal;
        double bestBound = Double.NaN;
        double mipGap = Double.NaN;
        long nodes = -1;
        try { bestBound = cplex.getBestObjValue(); } catch (Exception ignore) {}
        try { mipGap = cplex.getMIPRelativeGap(); } catch (Exception ignore) {}
        try { nodes = cplex.getNnodes(); } catch (Exception ignore) {}

       if (out != null) {
            OutputManager.writeCplexStats(out.resultsDir.resolve("cplex_stats.csv"),
                    solverStatus, bestBound, mipGap, nodes, timeSec);
        }

        if (ps != null) ps.close();
        cplex.end();

        Solution solution = new Solution(objVal, yVal, timeSec);
        solution.solverStatus = solverStatus;
        solution.bestBound = bestBound;
        solution.relativeGap = mipGap;
        solution.nodeCount = nodes;
        solution.certifiedOptimal = certifiedOptimal;
        return solution;
    }
}
