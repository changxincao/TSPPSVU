package Model;

import Basic.*;
import Helper.basicHelper.Config;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticInstanceIO;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSolveBridge;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Matched test of PDF (15)/(42), not a new demand/parameter experiment.
 * Usage: <saved-instance|tiny> <output-directory> [lambda] [seconds] [method|all].
 * Keeps the existing weight normalization and primal constraints unchanged.
 * For I<=6: independent primal enumeration, all-center/all-target repair audit;
 * signed coefficients, h>r, sparse eligibility, zero demand tested by 'tiny'.
 */
public final class RCSAAUpperReformulationTest {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        if (RCSAALBBDPrimalExactSolver.boundsConsistent(101.0, 100.0, 1e-4))
            throw new IllegalStateException("RCSAA certificate accepted LB > UB.");
        if (!RCSAALBBDPrimalExactSolver.boundsConsistent(100.00001, 100.0, 1e-4))
            throw new IllegalStateException("RCSAA certificate rejected numerical tolerance.");
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        Config cfg = new Config();
        cfg.lambda = args.length > 2 ? Double.parseDouble(args[2]) : 3.0;
        cfg.timeLimitSeconds = args.length > 3 ? Integer.parseInt(args[3]) : 120;
        cfg.threads = 1;
        cfg.tol = 1e-4;
        cfg.enforceDemandEquality = true;
        cfg.solveMode = SolveMode.RCSAA;
        cfg.standardizeTheta = true;
        cfg.C_h = 1.0;
        cfg.rcsaaSearchNeighborhoodRadius = 2;
        Data data;
        if (args[0].equals("tiny") || args[0].equals("tiny_zero")) {
            data = tiny();
            if (args[0].equals("tiny_zero")) {
                // Zero product bounds and a carrier without any eligible lanes.
                Arrays.fill(data.params.h, 0.0);
                Arrays.fill(data.params.e, 1.0);
                Arrays.fill(data.params.eligible[2], false);
                Arrays.fill(data.params.q[2], 0.0);
                data.params.M[2] = 0.0;
                data.params.validate();
            }
        }
        else {
            var saved = TRBReviewerR3M3SyntheticInstanceIO.load(Path.of(args[0]));
            cfg.k1LagPeriods = saved.settings.observedLagPeriods;
            data = TRBReviewerR3M3SyntheticSolveBridge.prepare(
                    saved.demandData, saved.procurementParams, cfg).solveData;
        }
        String selected = args.length > 4 ? args[4] : "all";
        if (selected.equals("fixed_switched")) {
            double[] fixed = Arrays.stream(args[5].split(",")).mapToDouble(Double::parseDouble).toArray();
            double maxError = 0;
            try (var m = new mosek.fusion.Model("fixed_switched_diagnostic")) {
                var y = m.variable("y", data.params.I, mosek.fusion.Domain.equalsTo(fixed));
                var z = m.variable("z", data.samples.size(), mosek.fusion.Domain.greaterThan(0.0));
                RCSAAUpperReformulation.addSwitched(m,y,z,data.params,data.samples,true);
                m.objective(mosek.fusion.ObjectiveSense.Maximize,mosek.fusion.Expr.sum(z));
                m.setSolverParam("numThreads",1);
                m.solve();
                double[] values = z.level();
                for(int w=0;w<values.length;w++) {
                    double primal=SecondStageEvaluator.evaluate(data.params,fixed,data.samples.get(w).demand(),true).objective;
                    maxError=Math.max(maxError,Math.abs(primal-values[w])/Math.max(1,Math.abs(primal)));
                }
            }
            Files.writeString(out.resolve("fixed_switched.txt"),"objective="+evaluate(data,fixed,cfg.lambda)+"\nmaxRelativeDualError="+maxError+"\n");
            if(maxError>1e-6) throw new IllegalStateException("Fixed switched mismatch "+maxError);
            // Diagnostic only: fix the complete existing master to the known feasible y.
            cfg.rcsaaCompactDual=true;
            cfg.rcsaaCompactSwitchedDual=true;
            Class<?> masterClass=Class.forName("Model.RCSAALBBDPrimalExactSolver$Master");
            var constructor=masterClass.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            try (AutoCloseable master=(AutoCloseable)constructor.newInstance(data.params,data.samples,
                    RCSAADecompositionSupport.validateAndNormalizeWeights(data.samples),cfg)) {
                var mf=masterClass.getDeclaredField("model"); mf.setAccessible(true);
                var yf=masterClass.getDeclaredField("y"); yf.setAccessible(true);
                var m=(mosek.fusion.Model)mf.get(master);
                m.constraint("diagnosticFixedY",(mosek.fusion.Variable)yf.get(master),mosek.fusion.Domain.equalsTo(fixed));
                m.setLogHandler(new java.io.PrintWriter(System.out,true));
                m.solve();
                Files.writeString(out.resolve("fixed_master.txt"),"objective="+m.primalObjValue()+"\nstatus="+m.getPrimalSolutionStatus()+"\n");
            }
            return;
        }
        Path csv = out.resolve(selected + ".csv");
        if (Files.exists(csv)) throw new IllegalArgumentException("Refusing overwrite " + csv);
        Files.writeString(out.resolve(selected + "_input.txt"),
                "source=" + args[0] + "\nI=" + data.params.I + "\nJ=" + data.params.J
                + "\nS=" + data.samples.size() + "\nlambda=" + cfg.lambda
                + "\nC_h=1.0\nthreads=1\nkappa=2\nequality=true\nseconds=" + cfg.timeLimitSeconds
                + "\nweights=" + Arrays.toString(RCSAADecompositionSupport.validateAndNormalizeWeights(data.samples)));
        double reference = data.params.I <= 6 ? audit(data, cfg, out.resolve(selected + "_audit.txt"), selected.contains("switched") || selected.equals("compact_family")) : Double.NaN;
        int failures = 0;
        try (var writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("method,I,J,S,lambda,objective,reevaluated,reference,bound,gap,certified,seconds,iterations,cuts,nodes,candidates,y,status\n");
            for (String mode : List.of("old_exact", "repair_exact", "old_search", "repair_search", "compact", "compact_anchors", "compact_switched", "compact_switched_anchors")) {
                if (!selected.equals("all") && !selected.equals(mode)
                        && !(selected.equals("compact_family") && (mode.equals("compact") || mode.equals("compact_anchors")))) continue;
                if (selected.equals("all") && mode.startsWith("compact_switched")) continue;
                cfg.rcsaaRepairCuts = mode.startsWith("repair");
                cfg.rcsaaCompactDual = mode.startsWith("compact");
                cfg.rcsaaCompactRepairAnchors = mode.endsWith("anchors");
                cfg.rcsaaCompactSwitchedDual = mode.startsWith("compact_switched");
                long start = System.nanoTime();
                try {
                    cfg.rcsaaSolverVariant = mode.endsWith("search")
                            ? RCSAASolverVariant.LBBD_PRIMAL_SEARCH : RCSAASolverVariant.LBBD_PRIMAL_EXACT;
                    Solution s = new DROModel().solve(data, cfg);
                    double value = evaluate(data, s.y, cfg.lambda);
                    if (Math.abs(value-s.objValue) > 1e-6*Math.max(1,Math.abs(value)))
                        throw new IllegalStateException("Objective reevaluation mismatch");
                    if (Double.isFinite(reference) && (s.bestBound > reference + 1e-5*Math.max(1, reference)
                            || (s.certifiedOptimal && Math.abs(value-reference) > 2e-4*Math.max(1,reference))))
                        throw new IllegalStateException("Enumeration or bound mismatch");
                    writer.write(String.format(Locale.US,
                            "%s,%d,%d,%d,%.9g,%.12g,%.12g,%.12g,%.12g,%.9g,%s,%.6f,%d,%d,%d,%d,\"%s\",%s%n",
                            mode,data.params.I,data.params.J,data.samples.size(),cfg.lambda,s.objValue,value,
                            reference,s.bestBound,s.relativeGap,s.certifiedOptimal,s.solveTimeSec,
                            s.iterationCount,s.cutCount,s.nodeCount,s.candidateCount,Arrays.toString(s.y),s.solverStatus));
                } catch (Exception e) {
                    failures++;
                    writer.write(mode+",ERROR,"+(System.nanoTime()-start)/1e9+",\""+e.toString().replace('"','\'')+"\"\n");
                    e.printStackTrace();
                }
                writer.flush();
            }
        }
        if (failures > 0) throw new IllegalStateException(failures + " failed comparisons; inspect " + csv);
    }

    private static double evaluate(Data d, double[] y, double lambda) throws Exception {
        double[] q = new double[d.samples.size()];
        for (int w=0; w<q.length; w++) q[w]=SecondStageEvaluator.evaluate(d.params,y,d.samples.get(w).demand(),true).objective;
        return RCSAADecompositionSupport.evaluateObjective(q,
                RCSAADecompositionSupport.validateAndNormalizeWeights(d.samples),lambda);
    }

    private static double audit(Data d, Config cfg, Path path, boolean switched) throws Exception {
        var p=d.params;
        List<double[]> ys=new ArrayList<>();
        for(int mask=0;mask<(1<<p.I);mask++) if(Integer.bitCount(mask)>=p.alpha && Integer.bitCount(mask)<=p.beta) {
            double[] y=new double[p.I];
            for(int i=0;i<p.I;i++) y[i]=(mask>>i)&1;
            ys.add(y);
        }
        int n=ys.size(), W=d.samples.size();
        var cuts=new RCSAADecompositionSupport.ScenarioCut[n][W];
        double best=Double.POSITIVE_INFINITY, maxViolation=0, maxTightness=0;
        int negativeRemoval=0;
        for(int k=0;k<n;k++) {
            double[] q=new double[W];
            for(int w=0;w<W;w++) {
                cuts[k][w]=RCSAAUpperReformulation.repair(p,d.samples.get(w).demand(),ys.get(k),w,true);
                q[w]=cuts[k][w].qValue;
                double dual=RCSAADecompositionSupport.solveScenarioCut(p,d.samples.get(w).demand(),ys.get(k),w,true).qValue;
                if(Math.abs(q[w]-dual)>1e-6*Math.max(1,Math.abs(q[w]))) throw new IllegalStateException("Primal dual mismatch");
                for(int i=0;i<p.I;i++) if(ys.get(k)[i]>0.5 && cuts[k][w].yCoeff[i]>1e-8) negativeRemoval++;
            }
            if (switched) {
                // Independently maximize the new dual at EVERY binary selection,
                // not just the final optimizer. Scenarios are separable here.
                try (var m = new mosek.fusion.Model("fixed_y_switched_audit")) {
                    var y = m.variable("y",p.I,mosek.fusion.Domain.equalsTo(ys.get(k)));
                    var z = m.variable("z",W,mosek.fusion.Domain.greaterThan(0.0));
                    RCSAAUpperReformulation.addSwitched(m,y,z,p,d.samples,true);
                    m.objective(mosek.fusion.ObjectiveSense.Maximize,mosek.fusion.Expr.sum(z));
                    m.setSolverParam("numThreads",1);
                    m.solve();
                    double[] dual=z.level();
                    for(int w=0;w<W;w++) if(Math.abs(dual[w]-q[w])>1e-6*Math.max(1,Math.abs(q[w])))
                        throw new IllegalStateException("Switched dual fixed-y mismatch");
                }
            }
            best=Math.min(best,RCSAADecompositionSupport.evaluateObjective(q,
                    RCSAADecompositionSupport.validateAndNormalizeWeights(d.samples),cfg.lambda));
        }
        for(int k=0;k<n;k++) for(int l=0;l<n;l++) for(int w=0;w<W;w++) {
            var c=cuts[k][w]; double rhs=c.constantPart;
            for(int i=0;i<p.I;i++) rhs+=c.yCoeff[i]*ys.get(l)[i];
            double delta=cuts[l][w].qValue-rhs;
            maxViolation=Math.max(maxViolation,delta);
            if(k==l)maxTightness=Math.max(maxTightness,Math.abs(delta));
            if(delta>1e-6*Math.max(1,Math.abs(cuts[l][w].qValue))) throw new IllegalStateException("Invalid repair cut");
        }
        Files.writeString(path,"selections="+n+"\nscenarios="+W+"\ncheckedUpperBounds="+(n*n*W)
                +"\nmaxViolation="+maxViolation+"\nmaxCenterResidual="+maxTightness
                +"\nnegativeRemovalCoefficients="+negativeRemoval+"\nexhaustiveObjective="+best
                +"\nswitchedDualFixedYChecks="+(switched?n*W:0)+"\n");
        return best;
    }

    private static Data tiny() {
        ProcurementParams p=new ProcurementParams(List.of("a","b","c"),2,
                new double[]{9,12},new double[]{7,6,4},new double[]{11,2,15},
                new double[][]{{8,0},{3,9},{0,7}},new double[][]{{3,0},{5,8},{0,4}},
                new boolean[][]{{true,false},{true,true},{false,true}},1,3);
        List<Sample> s=new ArrayList<>();
        double[][] demand={{0,0},{1,2},{7,4},{20,1},{2,25}};
        for(int w=0;w<demand.length;w++)s.add(new Sample(w,new PeriodData(w,
                LocalDate.of(2000,1,1),LocalDate.of(2000,1,7),demand[w],0,0,0,0),
                new CovariateVector(new double[]{0}),new double[]{.01,.09,.2,.3,.4}[w]));
        return new Data(List.of("x","y"),s,new CovariateVector(new double[]{0}),p);
    }
}
