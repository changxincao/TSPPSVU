package Basic;
import java.time.LocalDate;

/**
 * Aggregated period data (e.g., 7 days).
 *
 * avgTemp: currently not used; keep for compatibility (always 0.0).
 */
public class PeriodData {
    public final int tIndex; // 0-based period index
    public final LocalDate startDate;
    public final LocalDate endDate;

    public final double[] demandSum; // aggregated period demand, size=|J|
    public final int holidayCount;

    public final double avgFreightIndex;      // TSIFRGHT
    public final double avgConsumptionIndex;  // PCEC96
    public final double avgWEIIndex;          // WEI

    public PeriodData(int tIndex,
                      LocalDate startDate,
                      LocalDate endDate,
                      double[] demandSum,
                      int holidayCount,
                      double avgFreightIndex,
                      double avgConsumptionIndex,
                      double avgWEIIndex) {
        this.tIndex = tIndex;
        this.startDate = startDate;
        this.endDate = endDate;
        this.demandSum = demandSum;
        this.holidayCount = holidayCount;
        this.avgFreightIndex = avgFreightIndex;
        this.avgConsumptionIndex = avgConsumptionIndex;
        this.avgWEIIndex = avgWEIIndex;
    }
}
