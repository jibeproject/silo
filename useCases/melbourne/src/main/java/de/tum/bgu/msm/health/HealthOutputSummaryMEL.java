package de.tum.bgu.msm.health;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Standalone post-processing summary of Melbourne health model outputs, to support
 * debugging and validation (e.g. jibeproject/silo#190).
 *
 * Reads the person exposure file (and optionally the disease tracker and zone system
 * files) directly, without initialising SILO, and prints to screen:
 *   1. population overview (age band x sex, IRSD decile distribution)
 *   2. disease prevalence overall (n, %)
 *   3. disease prevalence by age band x sex (%)
 *   4. disease prevalence by IRSD decile (%)
 *   5. physical activity / exposure summary statistics (n, % zero, mean, sd, quantiles)
 *   6. recreational sport mMET hours/week by age band x sex (hurdle model diagnostics)
 *
 * Usage:
 *   HealthOutputSummaryMEL &lt;pp_exposure_YYYY.csv&gt; [pp_healthDiseaseTracker_YYYY.csv] [zoneSystem.csv]
 *
 * e.g. from the melbourne base directory:
 *   ... HealthOutputSummaryMEL scenOutput/base/microData/pp_exposure_2018.csv \
 *       scenOutput/base/microData/pp_healthDiseaseTracker_2018.csv input/zoneSystem.csv
 *
 * Pass "-" to skip an optional file (e.g. supply zoneSystem.csv without a tracker).
 *
 * @author Carl Higgs
 */
public class HealthOutputSummaryMEL {

    // Age bands chosen for debugging: distinguishes children (<18, excluded from the
    // sport PA model), the 18-19 sliver, then 20-year bands as used in silo#190.
    private static final String[] AGE_BANDS = {"0-17", "18-19", "20-39", "40-59", "60-79", "80+"};
    private static final String[] SEXES = {"male", "female"};

    // Exposure file columns summarised in the statistics tables
    private static final String[] SUMMARY_COLUMNS = {
            "mmetHr_walk", "mmetHr_cycle", "mmetHr_otherSport",
            "exposure_normalised_pm25", "exposure_normalised_no2",
            "exposure_normalised_noise_Lden", "exposure_normalised_ndvi"
    };

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: HealthOutputSummaryMEL <pp_exposure.csv> [pp_healthDiseaseTracker.csv] [zoneSystem.csv]");
            System.err.println("Pass \"-\" to skip an optional argument.");
            System.exit(1);
        }
        String exposurePath = args[0];
        String trackerPath = args.length > 1 && !"-".equals(args[1]) ? args[1] : null;
        String zonePath = args.length > 2 && !"-".equals(args[2]) ? args[2] : null;

        Map<String, Integer> zoneIrsd = zonePath == null ? Map.of() : readZoneIrsd(zonePath);

        ExposureData data = readExposure(exposurePath, zoneIrsd);
        System.out.printf("%nRead %,d persons from %s%n", data.n, exposurePath);
        if (zonePath != null) {
            System.out.printf("IRSD deciles joined from %s (%,d zones; %,d persons in zones without an IRSD decile)%n",
                    zonePath, zoneIrsd.size(), data.countIrsdNa());
        }

        printPopulationOverview(data);
        printExposureSummaries(data);
        printSportByAgeSex(data);
        if (zonePath != null) {
            printSportByIrsd(data);
        }

        if (trackerPath != null) {
            DiseaseData diseases = readTracker(trackerPath);
            System.out.printf("%nRead disease states for %,d persons from %s (state year: %s; %,d not matched to exposure file)%n",
                    diseases.stateByPerson.size(), trackerPath, diseases.stateYear,
                    diseases.countUnmatched(data.indexById));
            printDiseasePrevalence(data, diseases);
            printDiseaseByAgeSex(data, diseases);
            if (zonePath != null) {
                printDiseaseByIrsd(data, diseases);
            }
        } else {
            System.out.println("\n(No disease tracker file supplied; disease prevalence tables skipped.)");
        }
    }

    // ------------------------------------------------------------------ reading

    private static Map<String, Integer> readZoneIrsd(String path) throws IOException {
        Map<String, Integer> zoneIrsd = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String[] header = splitCsv(br.readLine());
            int posZone = indexOf(header, "SA1_7DIG16");
            int posIrsd = indexOf(header, "SEIFA_IRSD_DECILE_2016");
            if (posZone < 0) {
                posZone = 0; // fall back to first column as the zone id
            }
            if (posIrsd < 0) {
                System.err.println("Column SEIFA_IRSD_DECILE_2016 not found in " + path + "; IRSD tables will be empty.");
                return zoneIrsd;
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = splitCsv(line);
                float decile = parseFloatSafe(cols[posIrsd]); // missing values appear as "NA" or "-"
                if (!Float.isNaN(decile)) {
                    zoneIrsd.put(cols[posZone], (int) decile);
                }
            }
        }
        return zoneIrsd;
    }

    private static ExposureData readExposure(String path, Map<String, Integer> zoneIrsd) throws IOException {
        ExposureData data = new ExposureData();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String[] header = splitCsv(br.readLine());
            int posId = indexOf(header, "id");
            int posAge = indexOf(header, "age");
            int posGender = indexOf(header, "gender");
            int posZone = indexOf(header, "zone");
            int[] posSummary = new int[SUMMARY_COLUMNS.length];
            for (int c = 0; c < SUMMARY_COLUMNS.length; c++) {
                posSummary[c] = indexOf(header, SUMMARY_COLUMNS[c]);
                if (posSummary[c] < 0) {
                    System.err.println("Column " + SUMMARY_COLUMNS[c] + " not found in " + path + "; reported as NaN.");
                }
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = splitCsv(line);
                int irsd = -1;
                if (posZone >= 0) {
                    Integer decile = zoneIrsd.get(cols[posZone]);
                    if (decile != null) irsd = decile;
                }
                float[] values = new float[SUMMARY_COLUMNS.length];
                for (int c = 0; c < SUMMARY_COLUMNS.length; c++) {
                    values[c] = posSummary[c] < 0 ? Float.NaN : parseFloatSafe(cols[posSummary[c]]);
                }
                data.add(Integer.parseInt(cols[posId]),
                        Integer.parseInt(cols[posAge]),
                        Integer.parseInt(cols[posGender]),
                        irsd, values);
            }
        }
        return data;
    }

    private static DiseaseData readTracker(String path) throws IOException {
        DiseaseData diseases = new DiseaseData();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String[] header = splitCsv(br.readLine());
            int lastCol = header.length - 1; // latest simulated year
            diseases.stateYear = header[lastCol];
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = splitCsv(line);
                if (cols.length <= lastCol) continue;
                diseases.stateByPerson.put(Integer.parseInt(cols[0]), cols[lastCol]);
            }
        }
        return diseases;
    }

    // ------------------------------------------------------------------ tables

    private static void printPopulationOverview(ExposureData data) {
        printHeading("Population overview: persons by age band and sex");
        long[][] counts = new long[AGE_BANDS.length][SEXES.length];
        for (int i = 0; i < data.n; i++) {
            int band = ageBand(data.age[i]);
            int sex = sexIndex(data.gender[i]);
            if (sex >= 0) counts[band][sex]++;
        }
        System.out.printf("%-10s %12s %12s %12s%n", "Age band", "male", "female", "total");
        long totalAll = 0;
        for (int b = 0; b < AGE_BANDS.length; b++) {
            long rowTotal = counts[b][0] + counts[b][1];
            totalAll += rowTotal;
            System.out.printf("%-10s %,12d %,12d %,12d%n", AGE_BANDS[b], counts[b][0], counts[b][1], rowTotal);
        }
        System.out.printf("%-10s %38s%n", "Total", String.format("%,d", totalAll));

        printHeading("Population overview: persons by IRSD decile (1 = most disadvantaged)");
        Map<Integer, Long> irsdCounts = new TreeMap<>();
        for (int i = 0; i < data.n; i++) {
            irsdCounts.merge(data.irsd[i] < 1 ? -1 : (int) data.irsd[i], 1L, Long::sum);
        }
        System.out.printf("%-10s %12s %8s%n", "Decile", "n", "%");
        for (Map.Entry<Integer, Long> e : irsdCounts.entrySet()) {
            System.out.printf("%-10s %,12d %7.2f%%%n",
                    e.getKey() == -1 ? "NA" : String.valueOf(e.getKey()), e.getValue(), 100.0 * e.getValue() / data.n);
        }
    }

    private static void printExposureSummaries(ExposureData data) {
        printHeading("Summary statistics (all persons)");
        System.out.printf("%-32s %10s %8s %9s %9s %8s %8s %8s %8s %9s%n",
                "Variable", "n", "% zero", "mean", "sd", "min", "p25", "p50", "p75", "max");
        for (int c = 0; c < SUMMARY_COLUMNS.length; c++) {
            printSummaryRow(SUMMARY_COLUMNS[c], data.column(c));
        }
    }

    private static void printSportByAgeSex(ExposureData data) {
        printHeading("Recreational sport mMET hours/week (mmetHr_otherSport) by age band and sex");
        System.out.printf("%-10s %-8s %10s %8s %9s %8s %8s %8s %9s%n",
                "Age band", "Sex", "n", "% zero", "mean", "p25", "p50", "p75", "max");
        for (int b = 0; b < AGE_BANDS.length; b++) {
            for (int s = 0; s < SEXES.length; s++) {
                List<Float> values = new ArrayList<>();
                for (int i = 0; i < data.n; i++) {
                    if (ageBand(data.age[i]) == b && sexIndex(data.gender[i]) == s) {
                        values.add(data.values[i][2]); // mmetHr_otherSport
                    }
                }
                printGroupStatsRow(AGE_BANDS[b], SEXES[s], values);
            }
        }
    }

    private static void printSportByIrsd(ExposureData data) {
        printHeading("Recreational sport mMET hours/week (mmetHr_otherSport), adults 18+, by IRSD decile");
        System.out.printf("%-10s %-8s %10s %8s %9s %8s %8s %8s %9s%n",
                "Decile", "", "n", "% zero", "mean", "p25", "p50", "p75", "max");
        for (int d = 1; d <= 10; d++) {
            List<Float> values = new ArrayList<>();
            for (int i = 0; i < data.n; i++) {
                if (data.irsd[i] == d && data.age[i] >= 18) {
                    values.add(data.values[i][2]);
                }
            }
            printGroupStatsRow(String.valueOf(d), "", values);
        }
        List<Float> naValues = new ArrayList<>();
        for (int i = 0; i < data.n; i++) {
            if (data.irsd[i] < 1 && data.age[i] >= 18) {
                naValues.add(data.values[i][2]);
            }
        }
        printGroupStatsRow("NA", "", naValues);
    }

    private static void printDiseasePrevalence(ExposureData data, DiseaseData diseases) {
        printHeading("Disease prevalence, state year " + diseases.stateYear
                + " (denominator: " + String.format("%,d", diseases.stateByPerson.size())
                + " tracked persons; states co-occur so percentages need not sum to 100)");
        Map<String, Long> counts = new TreeMap<>();
        for (String state : diseases.stateByPerson.values()) {
            for (String token : tokens(state)) {
                counts.merge(token, 1L, Long::sum);
            }
        }
        long denominator = diseases.stateByPerson.size();
        System.out.printf("%-32s %12s %8s%n", "State", "n", "%");
        counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("%-32s %,12d %7.3f%%%n",
                        e.getKey(), e.getValue(), 100.0 * e.getValue() / denominator));
    }

    private static void printDiseaseByAgeSex(ExposureData data, DiseaseData diseases) {
        // strata: age band x sex
        int nStrata = AGE_BANDS.length * SEXES.length;
        long[] denominators = new long[nStrata];
        Map<String, long[]> counts = new TreeMap<>();
        for (Map.Entry<Integer, String> e : diseases.stateByPerson.entrySet()) {
            Integer idx = data.indexById.get(e.getKey());
            if (idx == null) continue;
            int sex = sexIndex(data.gender[idx]);
            if (sex < 0) continue;
            int stratum = ageBand(data.age[idx]) * SEXES.length + sex;
            denominators[stratum]++;
            for (String token : tokens(e.getValue())) {
                counts.computeIfAbsent(token, k -> new long[nStrata])[stratum]++;
            }
        }
        String[] labels = new String[nStrata];
        for (int b = 0; b < AGE_BANDS.length; b++) {
            for (int s = 0; s < SEXES.length; s++) {
                labels[b * SEXES.length + s] = AGE_BANDS[b] + (s == 0 ? " M" : " F");
            }
        }
        printHeading("Disease prevalence (%) by age band and sex, state year " + diseases.stateYear);
        printStrataTable(labels, denominators, counts);
    }

    private static void printDiseaseByIrsd(ExposureData data, DiseaseData diseases) {
        // strata: IRSD deciles 1..10 plus NA
        int nStrata = 11;
        long[] denominators = new long[nStrata];
        Map<String, long[]> counts = new TreeMap<>();
        for (Map.Entry<Integer, String> e : diseases.stateByPerson.entrySet()) {
            Integer idx = data.indexById.get(e.getKey());
            if (idx == null) continue;
            int stratum = data.irsd[idx] < 1 ? 10 : data.irsd[idx] - 1;
            denominators[stratum]++;
            for (String token : tokens(e.getValue())) {
                counts.computeIfAbsent(token, k -> new long[nStrata])[stratum]++;
            }
        }
        String[] labels = new String[nStrata];
        for (int d = 0; d < 10; d++) labels[d] = String.valueOf(d + 1);
        labels[10] = "NA";
        printHeading("Disease prevalence (%) by IRSD decile (1 = most disadvantaged), state year " + diseases.stateYear);
        printStrataTable(labels, denominators, counts);
    }

    // ------------------------------------------------------------------ helpers

    private static void printStrataTable(String[] labels, long[] denominators, Map<String, long[]> counts) {
        StringBuilder head = new StringBuilder(String.format("%-32s", "State"));
        for (String label : labels) head.append(String.format(" %9s", label));
        System.out.println(head);
        StringBuilder denomRow = new StringBuilder(String.format("%-32s", "(n persons)"));
        for (long d : denominators) denomRow.append(String.format(" %,9d", d));
        System.out.println(denomRow);
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            StringBuilder row = new StringBuilder(String.format("%-32s", e.getKey()));
            for (int s = 0; s < labels.length; s++) {
                row.append(denominators[s] == 0
                        ? String.format(" %9s", "-")
                        : String.format(" %8.3f%%", 100.0 * e.getValue()[s] / denominators[s]));
            }
            System.out.println(row);
        }
    }

    private static void printSummaryRow(String label, float[] values) {
        int n = 0, zeros = 0;
        double sum = 0, sumSq = 0;
        List<Float> valid = new ArrayList<>();
        for (float v : values) {
            if (Float.isNaN(v)) continue;
            n++;
            if (v == 0f) zeros++;
            sum += v;
            sumSq += (double) v * v;
            valid.add(v);
        }
        if (n == 0) {
            System.out.printf("%-32s %10s%n", label, "no data");
            return;
        }
        double mean = sum / n;
        double sd = n > 1 ? Math.sqrt((sumSq - sum * mean) / (n - 1)) : Double.NaN;
        float[] sorted = sortedArray(valid);
        System.out.printf("%-32s %,10d %7.2f%% %9.3f %9.3f %8.2f %8.2f %8.2f %8.2f %9.2f%n",
                label, n, 100.0 * zeros / n, mean, sd,
                sorted[0], quantile(sorted, 0.25), quantile(sorted, 0.5), quantile(sorted, 0.75),
                sorted[sorted.length - 1]);
    }

    private static void printGroupStatsRow(String label1, String label2, List<Float> values) {
        List<Float> valid = new ArrayList<>();
        int zeros = 0;
        double sum = 0;
        for (float v : values) {
            if (Float.isNaN(v)) continue;
            valid.add(v);
            if (v == 0f) zeros++;
            sum += v;
        }
        if (valid.isEmpty()) {
            System.out.printf("%-10s %-8s %10s%n", label1, label2, "no data");
            return;
        }
        float[] sorted = sortedArray(valid);
        System.out.printf("%-10s %-8s %,10d %7.2f%% %9.3f %8.2f %8.2f %8.2f %9.2f%n",
                label1, label2, valid.size(), 100.0 * zeros / valid.size(), sum / valid.size(),
                quantile(sorted, 0.25), quantile(sorted, 0.5), quantile(sorted, 0.75),
                sorted[sorted.length - 1]);
    }

    private static Set<String> tokens(String state) {
        Set<String> tokens = new HashSet<>();
        for (String token : state.split("\\|")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) tokens.add(trimmed);
        }
        return tokens;
    }

    private static int ageBand(int age) {
        if (age < 18) return 0;
        if (age < 20) return 1;
        if (age < 40) return 2;
        if (age < 60) return 3;
        if (age < 80) return 4;
        return 5;
    }

    private static int sexIndex(int genderCode) {
        if (genderCode == 1) return 0;
        if (genderCode == 2) return 1;
        return -1;
    }

    private static float[] sortedArray(List<Float> values) {
        float[] arr = new float[values.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = values.get(i);
        Arrays.sort(arr);
        return arr;
    }

    private static float quantile(float[] sorted, double q) {
        if (sorted.length == 1) return sorted[0];
        double pos = q * (sorted.length - 1);
        int lower = (int) Math.floor(pos);
        int upper = (int) Math.ceil(pos);
        double frac = pos - lower;
        return (float) (sorted[lower] * (1 - frac) + sorted[upper] * frac);
    }

    private static float parseFloatSafe(String s) {
        if (s == null || s.isEmpty() || s.equalsIgnoreCase("NA") || s.equalsIgnoreCase("null")) {
            return Float.NaN;
        }
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static int indexOf(String[] header, String column) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equalsIgnoreCase(column)) return i;
        }
        return -1;
    }

    /** Minimal CSV splitter handling double-quoted fields (e.g. zone system SA2 names). */
    private static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(field.toString().trim());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString().trim());
        return fields.toArray(new String[0]);
    }

    private static void printHeading(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    // ------------------------------------------------------------------ data holders

    private static class ExposureData {
        int n = 0;
        int[] age = new int[1 << 20];
        byte[] gender = new byte[1 << 20];
        byte[] irsd = new byte[1 << 20];
        float[][] values = new float[1 << 20][];
        final Map<Integer, Integer> indexById = new HashMap<>();

        void add(int id, int ageYears, int genderCode, int irsdDecile, float[] summaryValues) {
            if (n == age.length) {
                int newSize = age.length + (age.length >> 1);
                age = Arrays.copyOf(age, newSize);
                gender = Arrays.copyOf(gender, newSize);
                irsd = Arrays.copyOf(irsd, newSize);
                values = Arrays.copyOf(values, newSize);
            }
            age[n] = ageYears;
            gender[n] = (byte) genderCode;
            irsd[n] = (byte) (irsdDecile < 1 || irsdDecile > 10 ? -1 : irsdDecile);
            values[n] = summaryValues;
            indexById.put(id, n);
            n++;
        }

        float[] column(int c) {
            float[] col = new float[n];
            for (int i = 0; i < n; i++) col[i] = values[i][c];
            return col;
        }

        long countIrsdNa() {
            long count = 0;
            for (int i = 0; i < n; i++) {
                if (irsd[i] < 1) count++;
            }
            return count;
        }
    }

    private static class DiseaseData {
        String stateYear = "?";
        final Map<Integer, String> stateByPerson = new LinkedHashMap<>();

        long countUnmatched(Map<Integer, Integer> exposureIndex) {
            long count = 0;
            for (Integer id : stateByPerson.keySet()) {
                if (!exposureIndex.containsKey(id)) count++;
            }
            return count;
        }
    }
}
