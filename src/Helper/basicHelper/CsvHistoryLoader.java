package Helper.basicHelper;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import Basic.HistoricalDay;


public class CsvHistoryLoader {

    public static class HistoryLoadResult {
        public final List<String> laneNames;     // City list (sorted)
        public final List<HistoricalDay> days;   // daily aggregated rows (possibly filled)

        public HistoryLoadResult(List<String> laneNames, List<HistoricalDay> days) {
            this.laneNames = laneNames;
            this.days = days;
        }
    }

    // Accept "yyyy/MM/dd" or "yyyy/M/d"
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy/M/d");

    // Fixed header order:
    // Date,Customer ID,Truck ID,Invoice Number,Freight ID,City,Net Revenue,Weight (Kg),
    // Weight (Cubic),Goods Value,TSIFRGHT,PCEC96,WEI,holidayFlag
    private static final int IDX_DATE   = 0;
    private static final int IDX_CITY   = 5;
    private static final int IDX_WKG    = 7;
    private static final int IDX_TSI    = 10;
    private static final int IDX_PCE    = 11;
    private static final int IDX_WEI    = 12;
    private static final int IDX_HOL    = 13;

    public static HistoryLoadResult load(Path csvPath, Config cfg) throws IOException {

        // ---------- Pass 1: collect all cities ----------
        Set<String> citySet = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath.toFile()))) {
            String header = br.readLine();
            if (header == null) throw new IllegalArgumentException("Empty CSV: " + csvPath);

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length <= IDX_CITY) continue;

                String city = f[IDX_CITY].trim();
                if (!city.isEmpty()) citySet.add(city);
            }
        }

        List<String> laneNames = new ArrayList<>(citySet);
        Collections.sort(laneNames);

        Map<String, Integer> cityIndex = new HashMap<>();
        for (int j = 0; j < laneNames.size(); j++) cityIndex.put(laneNames.get(j), j);
        int J = laneNames.size();

        // ---------- Pass 2: aggregate to daily by city ----------
        TreeMap<LocalDate, DayAgg> agg = new TreeMap<>();

        try (BufferedReader br = new BufferedReader(new FileReader(csvPath.toFile()))) {
            br.readLine(); // skip header

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;

                String[] f = line.split(",", -1);
                if (f.length <= IDX_HOL) continue;

                LocalDate date = parseDateSafe(f[IDX_DATE].trim());
                String city = f[IDX_CITY].trim();
                Integer j = cityIndex.get(city);
                if (j == null) continue;

                double wKg = parseDoubleSafe(f[IDX_WKG]);
                double tsi = parseDoubleSafe(f[IDX_TSI]);
                double pce = parseDoubleSafe(f[IDX_PCE]);
                double wei = parseDoubleSafe(f[IDX_WEI]);

                int hol = (int) parseDoubleSafe(f[IDX_HOL]); // already 0/1 in your file

                DayAgg da = agg.computeIfAbsent(date, d -> new DayAgg(J));
                da.demand[j] += wKg;

                // store first seen indicators/holidayFlag for that date
                if (!da.metaSet) {
                    da.tsi = tsi;
                    da.pce = pce;
                    da.wei = wei;
                    da.holidayFlag = hol;
                    da.metaSet = true;
                }
            }
        }

        if (agg.isEmpty()) return new HistoryLoadResult(laneNames, Collections.emptyList());

        // ---------- OPTIONAL: fill missing dates ----------
        if (cfg.fillMissingDates) {
            agg = fillMissingDates(agg, J);
        }

        // ---------- Build HistoricalDay list ----------

        List<HistoricalDay> days = new ArrayList<>(agg.size());
        for (Map.Entry<LocalDate, DayAgg> e : agg.entrySet()) {
            LocalDate date = e.getKey();
            DayAgg da = e.getValue();

            days.add(new HistoricalDay(
                    date,
                    da.demand,
                    da.holidayFlag,
                    da.tsi,
                    da.pce,
                    da.wei
            ));
        }

        return new HistoryLoadResult(laneNames, days);
    }

    // ===================== fill missing =====================

    private static TreeMap<LocalDate, DayAgg> fillMissingDates(TreeMap<LocalDate, DayAgg> agg, int J) {
        LocalDate min = agg.firstKey();
        LocalDate max = agg.lastKey();

        DayAgg first = agg.firstEntry().getValue();
        double lastTSI = first.tsi, lastPCE = first.pce, lastWEI = first.wei;

        TreeMap<LocalDate, DayAgg> out = new TreeMap<>();
        for (LocalDate d = min; !d.isAfter(max); d = d.plusDays(1)) {
            DayAgg exist = agg.get(d);
            if (exist != null) {
                lastTSI = exist.tsi;
                lastPCE = exist.pce;
                lastWEI = exist.wei;
                out.put(d, exist);
            } else {
                DayAgg z = new DayAgg(J);
                z.tsi = lastTSI;
                z.pce = lastPCE;
                z.wei = lastWEI;
                z.holidayFlag = computeHolidayFlag(d); // missing day -> default 0 (or compute weekend later if you want)
                z.metaSet = true;
                out.put(d, z);
            }
        }
        return out;
    }
    
 // ===================== holiday helpers =====================

    /** weekend (Sat/Sun). */
    private static boolean isWeekend(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }

    /** US federal holidays (observed). */
    private static boolean isUSFederalHolidayObserved(LocalDate d) {
        int y = d.getYear();

        // New Year's Day (Jan 1) observed
        if (isObserved(d, LocalDate.of(y, 1, 1))) return true;

        // Martin Luther King Jr. Day: 3rd Monday in Jan
        if (d.equals(nthWeekdayOfMonth(y, Month.JANUARY, DayOfWeek.MONDAY, 3))) return true;

        // Washington's Birthday (Presidents Day): 3rd Monday in Feb
        if (d.equals(nthWeekdayOfMonth(y, Month.FEBRUARY, DayOfWeek.MONDAY, 3))) return true;

        // Memorial Day: last Monday in May
        if (d.equals(lastWeekdayOfMonth(y, Month.MAY, DayOfWeek.MONDAY))) return true;

        // Independence Day (Jul 4) observed
        if (isObserved(d, LocalDate.of(y, 7, 4))) return true;

        // Labor Day: 1st Monday in Sep
        if (d.equals(nthWeekdayOfMonth(y, Month.SEPTEMBER, DayOfWeek.MONDAY, 1))) return true;

        // Columbus Day: 2nd Monday in Oct
        if (d.equals(nthWeekdayOfMonth(y, Month.OCTOBER, DayOfWeek.MONDAY, 2))) return true;

        // Veterans Day (Nov 11) observed
        if (isObserved(d, LocalDate.of(y, 11, 11))) return true;

        // Thanksgiving Day: 4th Thursday in Nov
        if (d.equals(nthWeekdayOfMonth(y, Month.NOVEMBER, DayOfWeek.THURSDAY, 4))) return true;

        // Christmas Day (Dec 25) observed
        if (isObserved(d, LocalDate.of(y, 12, 25))) return true;

        return false;
    }

    /** Observed rule: if holiday on Sat -> observed Fri; if on Sun -> observed Mon; else same day. */
    private static boolean isObserved(LocalDate d, LocalDate holiday) {
        DayOfWeek w = holiday.getDayOfWeek();
        if (w == DayOfWeek.SATURDAY) return d.equals(holiday.minusDays(1));
        if (w == DayOfWeek.SUNDAY) return d.equals(holiday.plusDays(1));
        return d.equals(holiday);
    }

    /** n-th weekday of a month, e.g., 3rd Monday in Jan. */
    private static LocalDate nthWeekdayOfMonth(int year, Month month, DayOfWeek dow, int n) {
        LocalDate first = LocalDate.of(year, month, 1);
        LocalDate firstDow = first.with(TemporalAdjusters.nextOrSame(dow));
        return firstDow.plusWeeks(n - 1L);
    }

    /** last weekday of a month, e.g., last Monday in May. */
    private static LocalDate lastWeekdayOfMonth(int year, Month month, DayOfWeek dow) {
        LocalDate last = LocalDate.of(year, month, month.length(Year.isLeap(year)));
        return last.with(TemporalAdjusters.previousOrSame(dow));
    }

    /** Your final holiday flag rule: weekend OR observed US federal holiday. */
    private static int computeHolidayFlag(LocalDate d) {
        return (isWeekend(d) || isUSFederalHolidayObserved(d)) ? 1 : 0;
    }


    // ===================== helpers =====================

    private static class DayAgg {
        final double[] demand;
        double tsi = 0.0, pce = 0.0, wei = 0.0;
        int holidayFlag = 0;
        boolean metaSet = false;
        DayAgg(int J) { this.demand = new double[J]; }
    }

    private static LocalDate parseDateSafe(String s) {
        try {
            return LocalDate.parse(s, DATE_FMT);
        } catch (Exception ex) {
            // fallback ISO
            return LocalDate.parse(s);
        }
    }

    private static double parseDoubleSafe(String raw) {
        if (raw == null) return 0.0;
        String s = raw.trim();
        if (s.isEmpty()) return 0.0;
        return Double.parseDouble(s);
    }
}