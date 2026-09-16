package Test;

/**
 * Sequential one-JVM dispatcher for the paired Olist coverage/MQC grid.
 * It deliberately starts no worker threads or child Java processes; each
 * condition is resumable in its own output directory.
 */
public final class BrazilOlistRollingMinHCoverageMqcGrid {

    private static final double[] COVERAGES = {1.00, 0.75, 0.50};
    private static final double[] MQC_SCALES = {0.50, 0.75, 1.00, 1.25};

    private BrazilOlistRollingMinHCoverageMqcGrid() {
    }

    public static void main(String[] args) throws Exception {
        String methods = args.length > 0 ? args[0] : "D,SAA,CSAA,RCSAA";
        int startTrial = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int maxTrials = args.length > 2 ? Integer.parseInt(args[2]) : 51;
        for (double coverage : COVERAGES) {
            for (double mqcScale : MQC_SCALES) {
                BrazilOlistRollingMinHCoverageMqcExperiment.main(new String[]{
                        Double.toString(coverage), Double.toString(mqcScale), methods,
                        Integer.toString(startTrial), Integer.toString(maxTrials)
                });
            }
        }
    }
}
