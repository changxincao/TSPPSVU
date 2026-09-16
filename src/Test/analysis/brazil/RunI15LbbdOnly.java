package Test;

/**
 * Minimal launcher for the 15-supplier LBBD-only experiment variant.
 * It starts the corresponding subset comparison without handling the broader main-experiment workflow.
 */
public class RunI15LbbdOnly {
    public static void main(String[] args) throws Exception {
        BrazilOlistTopParamScenarioComparison.main(new String[]{
                "1",
                "5",
                "50",
                "15",
                "I15",
                "lbbd_only"
        });
    }
}
