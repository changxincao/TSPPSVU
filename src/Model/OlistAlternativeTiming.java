package Model;

import Basic.Data;
import Helper.basicHelper.*;
import java.nio.file.*;
import java.util.*;

/** Same Olist input as the exact RCSAA timing pilot. No tuning or OOS selection. */
public final class OlistAlternativeTiming {
 static void solve(Data data,Config cfg,Path out,String method)throws Exception {
  double[] upper=new double[data.params.J],scale=new double[data.params.J];
  for(int j=0;j<scale.length;j++){
   double mean=0;for(var s:data.samples){mean+=s.demand()[j]/data.samples.size();upper[j]=Math.max(upper[j],1.5*s.demand()[j]);}
   double variance=0;for(var s:data.samples)variance+=Math.pow(s.demand()[j]-mean,2)/data.samples.size();
   scale[j]=Math.sqrt(variance);if(scale[j]<1e-8)scale[j]=1.0;
  }
  Files.writeString(out.resolve("ambiguity.txt"),"method="+method+"\nlambda="+cfg.lambda+"\nw1_radius=5\nlevel_fraction=0.5 (regularized methods only)\ndistance=sum_j abs(d-d')/trainingSD_j; population SD; constant lane scale=1\nupper="+Arrays.toString(upper)+"\nscale="+Arrays.toString(scale)+"\nwhole_solve_limit_seconds=1800\n");
  // CCG's existing per-subproblem limits do not bound total runtime. A separate
  // diagnostic process is stopped at the total deadline; no uncertified gap invented.
  Timer deadline=new Timer(true);
  deadline.schedule(new TimerTask(){public void run(){
   try{Files.writeString(out.resolve("TIME_LIMIT.txt"),"Whole solve deadline reached; no completed solver certificate.\n");}catch(Exception e){e.printStackTrace();}
   Runtime.getRuntime().halt(124);
  }},1800000L);
  long start=System.nanoTime();
  System.out.println("START "+method+" "+java.time.Instant.now());
  try{
   Solution sol;
   if(method.equals("chi2")){
    cfg.solveMode=SolveMode.CSAA;
    sol=new DROModel().solve(data,cfg);
   }else{
    cfg.maxBendersIter=10000;
    WassersteinBoxInput w1=WassersteinBoxInput.fromData(data,upper,scale,5.0);
    if(method.equals("w1_single"))
     sol=new ContextualWassersteinBoxSingleCutBendersSolver().solve(w1,cfg).solution();
    else if(method.equals("w1_callback_multi"))
     sol=new ContextualWassersteinBoxCallbackMultiCutSolver().solve(w1,cfg).solution();
    else if(method.equals("w1_regularized"))
     sol=new ContextualWassersteinBoxRegularizedSingleCutBendersSolver().solve(w1,cfg,0.5).solution();
    else if(method.equals("w1_regularized_multi"))
     sol=new ContextualWassersteinBoxRegularizedMultiCutBendersSolver().solve(w1,cfg,0.5).solution();
    else sol=new ContextualWassersteinBoxCcgSolver().solve(w1,cfg).solution();
   }
   Files.writeString(out.resolve("results.csv"),"method,seconds,objective,bound,gap,certified,status,y\n"+method+","+(System.nanoTime()-start)/1e9+","+sol.objValue+","+sol.bestBound+","+sol.relativeGap+","+sol.certifiedOptimal+","+sol.solverStatus+",\""+Arrays.toString(sol.y)+"\"\n");
   System.out.println("END "+method+" "+java.time.Instant.now());
  }catch(Exception ex){Files.writeString(out.resolve("ERROR.txt"),ex.toString());throw ex;}
  finally{deadline.cancel();}
 }
}
