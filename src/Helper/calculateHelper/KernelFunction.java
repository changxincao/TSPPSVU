package Helper.calculateHelper;

public interface KernelFunction {
    double value(double distance, double h);
}


class GaussianKernel implements KernelFunction {
    @Override
    public double value(double distance, double h) {
        double z = distance / h;
        return Math.exp(-0.5 * z * z);
    }
}

class ExponentialKernel implements KernelFunction {
    @Override
    public double value(double distance, double h) {
        double z = Math.abs(distance / h);
        return Math.exp(-z);
    }
}




