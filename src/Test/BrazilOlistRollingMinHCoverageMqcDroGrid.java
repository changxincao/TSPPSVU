package Test;

/** Sequential DRO dispatcher for the six predeclared key sensitivity cells. */
public final class BrazilOlistRollingMinHCoverageMqcDroGrid {

    private static final double[][] CONDITIONS = {
            {1.00, 0.50}, {1.00, 0.75}, {1.00, 1.00}, {1.00, 1.25},
            {0.75, 1.00}, {0.50, 1.00}
    };

    private BrazilOlistRollingMinHCoverageMqcDroGrid() {
    }

    public static void main(String[] args) throws Exception {
        int startTrial = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int maxTrials = args.length > 1 ? Integer.parseInt(args[1]) : 51;
        for (double[] condition : CONDITIONS) {
            BrazilOlistRollingMinHCoverageMqcExperiment.main(new String[]{
                    Double.toString(condition[0]), Double.toString(condition[1]), "DRO",
                    Integer.toString(startTrial), Integer.toString(maxTrials)
            });
        }
    }
}
