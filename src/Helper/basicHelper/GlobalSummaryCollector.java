package Helper.basicHelper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

import Model.SolveMode;
import Helper.calculateHelper.KernelType;

public class GlobalSummaryCollector {

    private final Path csvPath;

    public GlobalSummaryCollector(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        this.csvPath = outDir.resolve("global_summary.csv");
    }

  private static String headerLine() {
    return String.join(",",
            // config keys
            "tag","solveMode","fillMissingDates","standardizeTheta","k1Lag","kernelType","bandwidthH","C_h","lambda",
            "nTrials",

            // expected stats
            "exp_mean","exp_std","exp_min","exp_p20","exp_p50","exp_p80","exp_p95","exp_max",
            // model objective stats
            "model_mean","model_std","model_min","model_p20","model_p50","model_p80","model_p95","model_max",

            // realized stats
            "real_mean","real_std","real_min","real_p20","real_p50","real_p80","real_p95","real_max",

            // ESS stats
            "ess_mean","ess_std","ess_min","ess_p20","ess_p50","ess_p80","ess_p95","ess_max",

            // corr(thetaDist, demandDist) stats
            "corrTD_mean","corrTD_std","corrTD_min","corrTD_p20","corrTD_p50","corrTD_p80","corrTD_p95","corrTD_max",

            // NEW: cost component means
            "transport_mean","spot_mean","penalty_mean",

            // time mean only
            "time_mean"
    );
}

    /**
     * 多进程安全追加：FileChannel + exclusive lock
     */
    public void append(String tag,
                   SolveMode solveMode,
                   boolean fillMissingDates,
                   boolean standardizeTheta,
                   int k1Lag,
                   KernelType kernelType,
                   double bandwidthH,
                   double C_h,
                   double lambda,
                   int nTrials,
                   Stats exp,
                   Stats model,
                   Stats real,
                   Stats ess,
                   Stats corrTD,
                   double transportMean,
                   double spotMean,
                   double penaltyMean,
                   double timeMean) throws IOException {

    Locale us = Locale.US;

    String line = String.format(us,
            "%s,%s,%s,%s,%d,%s,%.6f,%.6f,%.6f,%d," +

                    "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +  // exp

                    "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +  // model
                    "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +  // real

                    "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +  // ess

                    "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +  // corrTD

                    "%.10f,%.10f,%.10f," +                                   // NEW cost means

                    "%.10f",
            safe(tag),
            (solveMode == null ? "" : solveMode.name()),
            String.valueOf(fillMissingDates),
            String.valueOf(standardizeTheta),
            k1Lag,
            (kernelType == null ? "" : kernelType.name()),
            bandwidthH,
            C_h,
            lambda,
            nTrials,

            exp.mean, exp.std, exp.min, exp.p20, exp.p50, exp.p80, exp.p95, exp.max,
            model.mean, model.std, model.min, model.p20, model.p50, model.p80, model.p95, model.max,
            real.mean, real.std, real.min, real.p20, real.p50, real.p80, real.p95, real.max,
            ess.mean, ess.std, ess.min, ess.p20, ess.p50, ess.p80, ess.p95, ess.max,
            corrTD.mean, corrTD.std, corrTD.min, corrTD.p20, corrTD.p50, corrTD.p80, corrTD.p95, corrTD.max,

            transportMean, spotMean, penaltyMean,

            timeMean
    );

    try (FileChannel ch = FileChannel.open(
            csvPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
    );
         FileLock lock = ch.lock()
    ) {
        if (ch.size() == 0) {
            writeUtf8Line(ch, headerLine());
        }
        writeUtf8Line(ch, line);
    }
}

    private static void writeUtf8Line(FileChannel ch, String s) throws IOException {
        byte[] bytes = (s + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        ch.write(ByteBuffer.wrap(bytes));
    }

    private static String safe(String s) { return s == null ? "" : s; }

    public static class Stats {
        public final int n;
        public final double mean, std, min, max, p20, p50, p80, p95;

        public Stats(int n, double mean, double std, double min, double max,
                     double p20, double p50, double p80, double p95) {
            this.n = n;
            this.mean = mean;
            this.std = std;
            this.min = min;
            this.max = max;
            this.p20 = p20;
            this.p50 = p50;
            this.p80 = p80;
            this.p95 = p95;
        }
    }
}
