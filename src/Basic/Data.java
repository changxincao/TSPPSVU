package Basic;

import java.util.List;


public class Data {
    public final List<String> lanes;     // lane names
    public final List<Sample> samples;   // scenarios
    public CovariateVector thetaNow;     // covariates for current decision period (can be standardized later)
    public final ProcurementParams params; // costs/capacities/alpha/beta etc.

    public Data(List<String> lanes,
                   List<Sample> samples,
                   CovariateVector thetaNow,
                   ProcurementParams params) {
        this.lanes = lanes;
        this.samples = samples;
        this.thetaNow = thetaNow;
        this.params = params;
       
    }
}





