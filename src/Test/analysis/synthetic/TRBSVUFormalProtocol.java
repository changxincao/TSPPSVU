package Test.analysis.synthetic;

/** Frozen dimensions and validation split for the formal synthetic experiments. */
final class TRBSVUFormalProtocol {
    static final String EXPERIMENT12_VERSION = "TRBSVU_EXP12_V5";
    static final int CARRIERS = 15;
    static final int LANES = 50;
    static final int HISTORY_PERIODS = 75;
    static final int OOS_DRAWS = 1000;
    static final int VALIDATION_TRAINING_PERIODS = 50;
    static final int VALIDATION_ORIGINS = HISTORY_PERIODS - VALIDATION_TRAINING_PERIODS;
    static final int BASELINE_REPLICATIONS = 20;

    private TRBSVUFormalProtocol() { }
}
