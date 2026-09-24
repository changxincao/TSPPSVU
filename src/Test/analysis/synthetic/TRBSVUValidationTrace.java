package Test.analysis.synthetic;

/** One fully reproducible training-origin solve used for parameter validation. */
public record TRBSVUValidationTrace(
        String method,
        double candidateParameter,
        int origin,
        int trainingStart,
        int trainingEnd,
        double effectiveContextBandwidth,
        int scenarioCount,
        int positiveWeightCount,
        double effectiveSampleSize,
        double trainingObjective,
        String solverStatus,
        double bestBound,
        double relativeGap,
        double solveTimeSec,
        double optimizerTimeSec,
        boolean certifiedOptimal,
        double[] decision,
        double realizedValidationCost) {

    public TRBSVUValidationTrace {
        decision = decision == null ? null : decision.clone();
    }

    @Override
    public double[] decision() {
        return decision == null ? null : decision.clone();
    }
}
