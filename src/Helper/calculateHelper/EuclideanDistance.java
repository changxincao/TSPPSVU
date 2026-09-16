package Helper.calculateHelper;


public class EuclideanDistance implements DistanceMetric {
    @Override
    public double distance(double[] a, double[] b) {
        double sumSq = 0.0;
        for (int k = 0; k < a.length; k++) {
            double diff = a[k] - b[k];
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq);
    }
}

interface DistanceMetric {
    double distance(double[] a, double[] b);
}
