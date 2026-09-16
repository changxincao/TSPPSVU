package Helper.basicHelper;

import Basic.FeatureFlags;
import Helper.calculateHelper.KernelType;
import Helper.calculateHelper.StandardScaler;
import Model.RCSAASolverVariant;
import Model.SolveMode;
import mosek.stakey;

public class Config {
	public static int count=0; 
	//Basic
	public double tol=1e-4;
	
    // Aggregation
    public int aggregationDays = 7;      // 7 or 14
    public int k1LagPeriods = 2;         // number of lag periods used in theta
    public boolean demandAgg = true;//是否把每个周期的需求聚合起来，即lag的周期只看总需求，不看每个lane的    
    public boolean fillMissingDates=false;

    // When includeLagDemand && demandAgg==false:
    // - false (default): use raw lag demand vector d^{t-lag}[j]
    // - true: use lag share vector d^{t-lag}[j] / sum_j d^{t-lag}[j]
    // Useful when theta should represent demand structure rather than scale.
    public boolean lagDemandAsShare = false;

    // Demand balance in every second-stage scenario.
    // false preserves the historical model: sum_i x_ij + s_j >= d_j.
    // true enforces physical flow balance: sum_i x_ij + s_j == d_j.
    public boolean enforceDemandEquality = false;
    // Feature switches
    public final FeatureFlags featureFlags = new FeatureFlags();
    
    // Weighting
    public boolean standardizeTheta = false;
    // Used only when standardizeTheta=true. Z_SCORE preserves all historical runs.
    public StandardScaler.Mode thetaScaling = StandardScaler.Mode.Z_SCORE;
    public double bandwidthH = 1.0;
    public double C_h=500;//[500,50000] 9个点均匀取值
    public int C_h_lower=500;
    public int C_h_upper=50000;
    public double epsDenominator = 1e-12;
    public double lambda_lower=0.01; //[0,1,2,5,10] [0.01,100]均匀17个点
    public double lambda_upper=100; //[0,1,2,5,10] [0.01,100]均匀17个点
    public double lambda=0.01;

    // KNN-SAA: 0 keeps the existing kernel/equal-weight behavior; positive K
    // selects the K nearest standardized training contexts with weight 1/K.
    public int knnNeighbors = 0;
    
    // Kernel choice
    public KernelType kernelType = KernelType.EXPONENTIAL;

    // CPLEX
    public boolean writeCplexLogToFile = false;
    // Emit the native solver progress log to stdout. Batch launchers redirect
    // stdout to the run-specific log file, avoiding shared-file collisions.
    public boolean writeSolverLogToConsole = false;
    public int timeLimitSeconds = 3600;
    public int threads = 4;
    public SolveMode solveMode=SolveMode.CSAA;
    
    // RCSAA 分解求解相关配置
    public int maxBendersIter=10000;
    public RCSAASolverVariant rcsaaSolverVariant = RCSAASolverVariant.DRO_EXTENSIVE;
    // LBBD_SEARCH 邻域搜索半径：0=关闭，1=只做距离1，2=做距离1和2
    public int rcsaaSearchNeighborhoodRadius = 2;
    // PDF RCSAA_latest_exact_reformulations, equations (15) and (42).
    // Opt-in: preserve historical solver behavior and change only z <= Q.
    public boolean rcsaaRepairCuts = false;
    public boolean rcsaaCompactDual = false;
    // Static global repair planes at zero/all-one anchors, no local branching.
    public boolean rcsaaCompactRepairAnchors = false;
    // QUARANTINED diagnostic: fixed-y identity passes, but I10 MIP bound failed
    // an independent feasible-solution check. Public solve rejects this flag.
    public boolean rcsaaCompactSwitchedDual = false;
    
    //Others
    public int seed=0;

    // Diagnostics: for SAA, evaluate each training sample as a single-sample solve in each trial.
    // When enabled, BatchRunner writes one aggregated CSV across all trials under current outDir.
    public boolean enableTrialSingleSampleDeterministicEval = false;
    
    //
}

