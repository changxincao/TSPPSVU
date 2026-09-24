package Model;

public  class Solution {
    public double objValue;
    public double[] y;
    public double solveTimeSec;
    /** Wall time spent inside native optimizer solve calls, excluding Java model construction. */
    public double optimizerTimeSec;
    public String solverStatus;
    public double bestBound;
    public double relativeGap;
    public long nodeCount;
    public int iterationCount;
    public int cutCount;
    public long candidateCount;
    public boolean certifiedOptimal;
    public double wassersteinRadius;
    public double wassersteinEta;
    public int wassersteinInitialPointCount;
    public int wassersteinGeneratedCutCount;
    public int wassersteinTotalPointCount;
    public double[] wassersteinBoxUpper;
    public double[] wassersteinDistanceScale;
    
    public Solution() {
		this.objValue = 0;
		this.y = null;
		this.solveTimeSec = 0;
		initializeDiagnostics();
    	
    }
    public Solution(double objValue, double[] y, double solveTimeSec) {
        this.objValue = objValue;
        this.y = y;
        this.solveTimeSec = solveTimeSec;
        initializeDiagnostics();
    }

    private void initializeDiagnostics() {
        this.solverStatus = "UNKNOWN";
        this.bestBound = Double.NaN;
        this.relativeGap = Double.NaN;
        this.nodeCount = -1L;
        this.iterationCount = -1;
        this.cutCount = -1;
        this.candidateCount = -1L;
        this.certifiedOptimal = false;
        this.optimizerTimeSec = Double.NaN;
        this.wassersteinRadius = Double.NaN;
        this.wassersteinEta = Double.NaN;
        this.wassersteinInitialPointCount = -1;
        this.wassersteinGeneratedCutCount = -1;
        this.wassersteinTotalPointCount = -1;
        this.wassersteinBoxUpper = null;
        this.wassersteinDistanceScale = null;
    }
}
