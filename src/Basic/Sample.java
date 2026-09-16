package Basic;
import java.time.LocalDate;
import java.util.*;

import Helper.basicHelper.Config;

// Sample.java
public class Sample {
    public final int id;
    public final PeriodData period;   // contains demandSum and aggregated exogenous vars
    public CovariateVector theta;     // covariate vector (may be standardized later)
    public double weight;             // default 1/N, can be overwritten by kernel
    //这俩略有重复，先这样
    
    public Sample(int id, PeriodData period, CovariateVector theta, double weight) {
        this.id = id;
        this.period = period;
        this.theta = theta;
        this.weight = weight;
    }

    /** Scenario demand vector d^w (aggregated over the period). */
    public double[] demand() {
        return period.demandSum;
    }
}

