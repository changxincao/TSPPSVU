package Model;

import Basic.*;
import Helper.basicHelper.*;
import Helper.calculateHelper.*;
import Test.*;
import java.nio.file.*;
import java.util.*;
import mosek.fusion.*;

/** Paper-input algorithm comparison, no parameter tuning/OOS selection.
 * Reuses the historical single-variant runner's input construction.
 * Switched compact is invoked diagnostically without enabling its public gate.
 */
public class OlistCompactLongComparison {
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]), out=Path.of(args[1]);
  Files.createDirectories(out);
  if(Files.exists(out.resolve("results.csv")))throw new IllegalArgumentException("Existing output");
  var loader=WeeklyWideLoader.load(input);
  Class<?> historical=Class.forName("Test.analysis.brazil.BrazilOlistSingleRCSAAVariantRunner");
  var configMethod=historical.getDeclaredMethod("buildConfig",int.class,double.class,double.class);configMethod.setAccessible(true);
  int lag=args.length>6?Integer.parseInt(args[6]):1;
  double bandwidth=args.length>7?Double.parseDouble(args[7]):0.1;
  int sampleCount=args.length>8?Integer.parseInt(args[8]):50;
  int carrierCount=args.length>9?Integer.parseInt(args[9]):10;
  double riskParameter=args.length>10?Double.parseDouble(args[10]):5.0;
  if(carrierCount<1)throw new IllegalArgumentException("Positive carrier count required");
  Config cfg=(Config)configMethod.invoke(null,lag,bandwidth,riskParameter);
  cfg.threads=1;cfg.timeLimitSeconds=args.length>4?Integer.parseInt(args[4]):1800;
  cfg.enforceDemandEquality=args.length>3?Boolean.parseBoolean(args[3]):true;
  var br=SampleBuilder.buildFromPeriods(loader.periods,loader.laneNames,cfg);
  var baseline=historical.getDeclaredMethod("buildBaselineDemand",SampleBuilder.BuildResult.class);baseline.setAccessible(true);
  var p=InstanceGenerator.generate(carrierCount,(double[])baseline.invoke(null,br),new InstanceGenerator.GenConfig(),cfg);
  var rolling=ExperimentBuilder.buildRolling(br.samples,sampleCount);
  // Align by original period index, not by k-dependent rolling trial number.
  int period=args.length>2?Integer.parseInt(args[2]):51;
  int trial=period-cfg.k1LagPeriods-sampleCount;
  if(trial<0||trial>=rolling.size())throw new IllegalArgumentException("Insufficient history for window");
  var train=BatchRunner.deepCopySamples(rolling.trainSets.get(trial));
  var theta=new CovariateVector(rolling.thetaNowList.get(trial).values().clone());
  StandardScaler scaler=new StandardScaler();scaler.fit(train,theta.values().length);
  for(var s:train)s.theta=new CovariateVector(scaler.transform(s.theta.values()));
  theta=new CovariateVector(scaler.transform(theta.values()));
  new WeightCalculator(WeightCalculator.buildKernel(cfg),new EuclideanDistance()).computeKernelWeights(train,theta,cfg);
  Data data=new Data(loader.laneNames,train,theta,p);
  if(train.size()!=sampleCount||p.I!=carrierCount||p.J!=23)throw new IllegalStateException("Wrong input scale");
  double sumW2=0;for(var sample:train)sumW2+=sample.weight*sample.weight;
  // Current formal 1e-8 floor produces a ~1e-6 ESS difference from old export.
  // Both balance variants use this identical current weight vector.
  if(period==78 && sampleCount==50 && lag==1 && bandwidth==0.1 && Math.abs(1.0/sumW2-1.2974673969)>1e-5)
   throw new IllegalStateException("Historical trial25 ESS mismatch: "+1.0/sumW2);
  StringBuilder manifest=new StringBuilder("input="+input.toAbsolutePath()+"\nrollingIndex="+trial+"\ntestPeriod="+period+"\nESS="+1.0/sumW2+"\nW="+sampleCount+"\nI=10\nJ=23\nk="+lag+"\nC_h="+bandwidth+"\nlambda="+riskParameter+"\nseed=0\nthreads=1\nlimit="+cfg.timeLimitSeconds+"\nequality="+cfg.enforceDemandEquality+"\nh=max eligible r\n");
  manifest.append("sha256=").append(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)))).append('\n');
  int carrierLabel=manifest.indexOf("\nI=10\n");
  manifest.replace(carrierLabel,carrierLabel+6,"\nI="+carrierCount+"\n");
  manifest.append("h=").append(Arrays.toString(p.h)).append("\ng=").append(Arrays.toString(p.p)).append("\nM=").append(Arrays.toString(p.M)).append('\n');
  manifest.append("theta=").append(Arrays.toString(theta.values())).append('\n');
  for(int i=0;i<p.I;i++)manifest.append("carrier "+i+" r="+Arrays.toString(p.r[i])+" q="+Arrays.toString(p.q[i])+"\n");
  for(var s:train)manifest.append("sample "+s.id+" weight="+s.weight+" d="+Arrays.toString(s.demand())+"\n");
  Files.writeString(out.resolve("input.txt"),manifest);
  if(args.length>5 && (args[5].equals("chi2")||args[5].equals("w1")
          ||args[5].equals("w1_single")||args[5].equals("w1_regularized")
          ||args[5].equals("w1_regularized_multi")
          ||args[5].equals("w1_callback_multi"))){
   OlistAlternativeTiming.solve(data,cfg,out,args[5]);return;
  }
  double best=Double.POSITIVE_INFINITY;
  try(var csv=Files.newBufferedWriter(out.resolve("results.csv"))){
   csv.write("method,seconds,objective,bound,gap,certified,status,y\n");csv.flush();
   for(String mode:List.of("old_search","repair_search","compact","switched")){
    if(args.length>5 && !args[5].equals(mode)) continue;
    System.out.println("START "+mode+" "+java.time.Instant.now());
    cfg.rcsaaRepairCuts=mode.equals("repair_search");cfg.rcsaaCompactDual=!mode.endsWith("search");cfg.rcsaaCompactSwitchedDual=mode.equals("switched");
    long start=System.nanoTime();
    try {
     double[] y;double bound;boolean certified;String status;
     if(mode.endsWith("search")){
      cfg.rcsaaSolverVariant=RCSAASolverVariant.LBBD_PRIMAL_SEARCH;
      Solution sol=new DROModel().solve(data,cfg);y=sol.y;bound=sol.bestBound;certified=sol.certifiedOptimal;status=sol.solverStatus;
     }else{
      var c=Class.forName("Model.RCSAALBBDPrimalExactSolver$Master");var ctor=c.getDeclaredConstructors()[0];ctor.setAccessible(true);
      try(AutoCloseable master=(AutoCloseable)ctor.newInstance(p,train,RCSAADecompositionSupport.validateAndNormalizeWeights(train),cfg);
          var log=Files.newBufferedWriter(out.resolve(mode+"_mosek.log"))){
       var mf=c.getDeclaredField("model");mf.setAccessible(true);var m=(mosek.fusion.Model)mf.get(master);
       m.setLogHandler(log);m.solve();
       var yf=c.getDeclaredField("y");yf.setAccessible(true);y=((Variable)yf.get(master)).level();
       bound=m.getSolverIntInfo("mioObjBoundDefined")>0?m.getSolverDoubleInfo("mioObjBound"):Double.NaN;
       status=m.getPrimalSolutionStatus().toString();certified=m.getPrimalSolutionStatus()==SolutionStatus.Optimal;
       var zf=c.getDeclaredField("z");zf.setAccessible(true);double[] z=((Variable)zf.get(master)).level();
       for(int w=0;w<z.length;w++){
        double q=SecondStageEvaluator.evaluate(p,y,train.get(w).demand(),cfg.enforceDemandEquality).objective;
        if(Math.abs(z[w]-q)>1e-5*Math.max(1,Math.abs(q)))throw new IllegalStateException("z/Q mismatch");
       }
      }
     }
     double[] q=new double[train.size()];for(int w=0;w<q.length;w++)q[w]=SecondStageEvaluator.evaluate(p,y,train.get(w).demand(),cfg.enforceDemandEquality).objective;
     double value=RCSAADecompositionSupport.evaluateObjective(q,RCSAADecompositionSupport.validateAndNormalizeWeights(train),cfg.lambda);
     best=Math.min(best,value);
     if(Double.isFinite(bound)&&bound>best+1e-5*Math.max(1,Math.abs(best)))throw new IllegalStateException("BOUND CONTRADICTION bound="+bound+" known feasible="+best);
     double gap=Double.isFinite(bound)?Math.max(0,value-bound)/Math.max(1,Math.abs(value)):Double.NaN;
     csv.write(mode+","+(System.nanoTime()-start)/1e9+","+value+","+bound+","+gap+","+(certified&&gap<=cfg.tol)+","+status+",\""+Arrays.toString(y)+"\"\n");
    }catch(Exception ex){csv.write(mode+",ERROR,\""+ex.toString().replace('"','\'')+"\"\n");ex.printStackTrace();}
    csv.flush();System.out.println("END "+mode+" "+java.time.Instant.now());
   }
  }
 }
}
