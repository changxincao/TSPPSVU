// FeatureFlags.java
package Basic;

public class FeatureFlags {
    // theta components
    public boolean includeLagDemand = true;           // [d^{t-1},...,d^{t-k1}]
    public boolean includeHolidayCount = true;        // will be 0 unless you add calendar later
    public boolean includeFreightIndex = true;        // TSIFRGHT (monthly)
    public boolean includeConsumptionIndex = true;    // PCEC96 (monthly)
    public boolean includeWEIIndex = true;                 // WEI (weekly)
}
