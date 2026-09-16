package Helper.basicHelper;

import Basic.PeriodData;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Load weekly-wide demand CSV:
 * header: weekIndex,<lane1>,<lane2>,...
 * rows:   t, d1, d2, ...
 *
 * This loader is used for synthetic experiments where data is already aggregated per period.
 * Exogenous columns are not present, so they are set to 0.
 */
public class WeeklyWideLoader {

    public static class Result {
        public final List<String> laneNames;
        public final List<PeriodData> periods;

        public Result(List<String> laneNames, List<PeriodData> periods) {
            this.laneNames = laneNames;
            this.periods = periods;
        }
    }

    public static Result load(Path csvPath) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(csvPath)) {
            String header = br.readLine();
            if (header == null) throw new IllegalArgumentException("Empty CSV: " + csvPath);

            String[] cols = header.split(",", -1);
            String first = (cols.length == 0 ? "" : cols[0].trim());
            // Some CSV writers add UTF-8 BOM; strip it to avoid breaking the header check.
            if (!first.isEmpty() && first.charAt(0) == '\uFEFF') {
                first = first.substring(1);
            }
            if (cols.length < 2 || !"weekIndex".equals(first)) {
                throw new IllegalArgumentException("weekly_wide header must start with weekIndex: " + header);
            }

            List<String> laneNames = new ArrayList<>();
            for (int i = 1; i < cols.length; i++) {
                String name = cols[i].trim();
                if (!name.isEmpty()) laneNames.add(name);
            }
            int J = laneNames.size();
            if (J <= 0) throw new IllegalArgumentException("No lane columns in CSV: " + csvPath);

            List<PeriodData> periods = new ArrayList<>();
            String line;
            int row = 0;
            LocalDate base = LocalDate.of(2000, 1, 1);

            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length < J + 1) {
                    throw new IllegalArgumentException("Bad row (need " + (J + 1) + " cols) at row=" + row + ": " + line);
                }

                int tIndex;
                try {
                    tIndex = (int) Double.parseDouble(f[0].trim());
                } catch (Exception ex) {
                    tIndex = periods.size();
                }

                double[] dem = new double[J];
                for (int j = 0; j < J; j++) {
                    dem[j] = parseDoubleSafe(f[j + 1]);
                }

                LocalDate sd = base.plusDays((long) tIndex * 7L);
                LocalDate ed = sd.plusDays(6);

                periods.add(new PeriodData(
                        tIndex,
                        sd,
                        ed,
                        dem,
                        0,
                        0.0,
                        0.0,
                        0.0
                ));
                row++;
            }

            return new Result(laneNames, periods);
        }
    }

    private static double parseDoubleSafe(String s) {
        try {
            String t = (s == null ? "" : s.trim());
            if (t.isEmpty()) return 0.0;
            return Double.parseDouble(t);
        } catch (Exception ex) {
            return 0.0;
        }
    }
}
