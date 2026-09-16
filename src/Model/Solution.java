package Model;

public  class Solution {
    public double objValue;
    public double[] y;
    public double solveTimeSec;
    public String solverStatus;
    public double bestBound;
    public double relativeGap;
    public long nodeCount;
    public int iterationCount;
    public int cutCount;
    public long candidateCount;
    public boolean certifiedOptimal;
    
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
    }
}
