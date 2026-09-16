package Basic;
import java.time.LocalDate;

public class HistoricalDay {
    public final LocalDate date;
    public final double[] laneDemand; // size = |J|

    public final int holidayFlag;         // 0/1
    public final double freightIndex;       // TSIFRGHT
    public final double consumptionIndex;   // PCEC96
    public final double weiIndex;              // WEI

    public HistoricalDay(LocalDate date,
                         double[] laneDemand,
                         int holidayFlag,	
                         double tsiFreightIndex,
                         double pceConsumptionIndex,
                         double weiIndex) {
        this.date = date;
        this.laneDemand = laneDemand;
        this.holidayFlag = holidayFlag;
        this.freightIndex = tsiFreightIndex;
        this.consumptionIndex = pceConsumptionIndex;
        this.weiIndex = weiIndex;
    }
}
