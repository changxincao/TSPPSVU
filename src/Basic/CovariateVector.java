package Basic;
import java.util.Arrays;

public class CovariateVector {
    private final double[] values;

    public CovariateVector(double[] values) {
        this.values = values;
    }

    public double[] values() { return values; }
    public int dim() { return values.length; }

    public CovariateVector copy() {
        return new CovariateVector(Arrays.copyOf(values, values.length));
    }
}
