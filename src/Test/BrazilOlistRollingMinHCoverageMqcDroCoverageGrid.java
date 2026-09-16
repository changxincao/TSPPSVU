package Test;

/** Sequential one-JVM DRO dispatcher for the two coverage sensitivity cells. */
public final class BrazilOlistRollingMinHCoverageMqcDroCoverageGrid {

    private static final double[] COVERAGES = {0.75, 0.50};

    private BrazilOlistRollingMinHCoverageMqcDroCoverageGrid() {
    }

    public static void main(String[] args) throws Exception {
        int startTrial = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int maxTrials = args.length > 1 ? Integer.parseInt(args[1]) : 51;
        for (double coverage : COVERAGES) {
            BrazilOlistRollingMinHCoverageMqcExperiment.main(new String[]{
                    Double.toString(coverage), "1.0", "DRO",
                    Integer.toString(startTrial), Integer.toString(maxTrials)
            });
        }
    }
}
