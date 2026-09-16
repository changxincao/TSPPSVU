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

public class GlobalTrialCollector {

    private final Path csvPath;

    public GlobalTrialCollector(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        this.csvPath = outDir.resolve("global_trials.csv");
    }

    private static String headerLine() {
        return String.join(",",
                // config keys (same spirit as global_summary.csv)
                "tag","solveMode","fillMissingDates","standardizeTheta","k1Lag","kernelType","bandwidthH","C_h","lambda",

                // trial keys
                "trialId","testIdx","trainSize",

                // metrics (same as trials_*.csv)
                "expectedObj","modelObj","realizedObj","solveTimeSec","selectedCount",
                "oosTransportCost","oosSpotCost","oosPenaltyCost",
                "sumW","sumW2","ESS","top1W","top5Wsum","maxW_over_meanW",
                "thetaDist_mean","thetaDist_median","thetaDist_min","thetaDist_max",
                "demandDist_mean","demandDist_median","demandDist_min","demandDist_max",
                "corrW_thetaDist","corrW_demandDist","corrTheta_demandDist",

                // NEW: decisions
                "yBinary","selectedCarriers"
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

                       int trialId,
                       int testIdx,
                       int trainSize,

                       double expectedObj,
                       double modelObj,
                       double realizedObj,
                       double solveTimeSec,
                       int selectedCount,

                       double oosTransportCost,
                       double oosSpotCost,
                       double oosPenaltyCost,

                       double sumW,
                       double sumW2,
                       double ess,
                       double top1W,
                       double top5Wsum,
                       double maxW_over_meanW,

                       double thetaMean,
                       double thetaMedian,
                       double thetaMin,
                       double thetaMax,

                       double demandMean,
                       double demandMedian,
                       double demandMin,
                       double demandMax,

                       double corrW_thetaDist,
                       double corrW_demandDist,
                       double corrTheta_demandDist,

                       String yBinary,
                       String selectedCarriers) throws IOException {

        Locale us = Locale.US;

        String line = String.format(us,
                "%s,%s,%s,%s,%d,%s,%.6f,%.6f,%.6f," +
                        "%d,%d,%d," +
                        "%.10f,%.10f,%.10f,%.6f,%d," +
                        "%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f," +
                        "%s,%s",
                safe(tag),
                (solveMode == null ? "" : solveMode.name()),
                String.valueOf(fillMissingDates),
                String.valueOf(standardizeTheta),
                k1Lag,
                (kernelType == null ? "" : kernelType.name()),
                bandwidthH,
                C_h,
                lambda,

                trialId, testIdx, trainSize,

                expectedObj, modelObj, realizedObj, solveTimeSec, selectedCount,

                oosTransportCost, oosSpotCost, oosPenaltyCost,

                sumW, sumW2, ess, top1W, top5Wsum, maxW_over_meanW,

                thetaMean, thetaMedian, thetaMin, thetaMax,
                demandMean, demandMedian, demandMin, demandMax,

                corrW_thetaDist, corrW_demandDist, corrTheta_demandDist,

                csvQuote(yBinary),
                csvQuote(selectedCarriers)
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

    private static String csvQuote(String s) {
        if (s == null) return "\"\"";
        String t = s.replace("\"", "\"\"");
        return "\"" + t + "\"";
    }
}
