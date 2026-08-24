package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.data.Purpose;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone post-processing summary of Melbourne health and travel model outputs, to
 * support debugging and validation (e.g. jibeproject/silo#190).
 *
 * Reads the person exposure file, the MITO trips file, and optionally the disease tracker,
 * zone system and routed trip exposure files directly, without initialising SILO, and
 * prints to screen:
 *   1. population overview (age band x sex, IRSD decile distribution)
 *   2. physical activity / exposure summary statistics (n, % zero, mean, sd, quantiles)
 *   3. recreational sport mMET hours/week by age band x sex (hurdle model diagnostics)
 *   4. total mMET hours/week by age band x sex, and sport mMET by IRSD decile
 *   5. MITO trip overview and trips by day of week
 *   6. mode choice by purpose
 *   7. trip distance and travel time by purpose (MITO skim and MATSim routed)
 *   8. distance, time and implied speed by mode (data quality check)
 *   9. average weekly trip distance, time and trip count per person, overall and by
 *      age band x sex and IRSD decile
 *  10. disease prevalence overall (n, %), by age band x sex, and by IRSD decile
 *
 * Usage (from the Melbourne scenario data folder, e.g. D:/projects/jibe/melbourne):
 *   HealthOutputSummaryMEL &lt;scenario&gt; [year]
 *
 * e.g.  HealthOutputSummaryMEL base
 *
 * All files are resolved relative to the current working directory following the usual
 * scenOutput/&lt;scenario&gt;/ convention; see {@link Inputs#fromScenario}. If the year is
 * omitted it is taken from the highest pp_exposure_&lt;year&gt;.csv present. Everything but
 * the person exposure file is optional - missing files simply skip their tables.
 *
 * A legacy explicit-path form is still accepted when the first argument ends in ".csv":
 *   HealthOutputSummaryMEL &lt;pp_exposure.csv&gt; [tracker.csv] [zoneSystem.csv] [trips.csv] [healthIndicatorsDir]
 * Pass "-" to skip an optional file.
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
            "mmetHr_walk", "mmetHr_cycle", "mmetHr_otherSport", "mmetHr_total",
            "exposure_normalised_pm25", "exposure_normalised_no2",
            "exposure_normalised_noise_Lden", "exposure_normalised_ndvi"
    };

    private static final int MMET_WALK = 0;
    private static final int MMET_CYCLE = 1;
    private static final int MMET_OTHER_SPORT = 2;
    private static final int MMET_TOTAL = 3;

    // Trip table axes are taken from the MITO enums so they stay in sync if categories are
    // added upstream. Unlike TripReaderHealth, which calls valueOf and throws, unrecognised
    // tokens fall into a trailing "other" bucket - a malformed file should degrade, not crash.
    private static final String[] PURPOSES = withOther(Purpose.values());
    private static final String[] MODES = withOther(Mode.values());
    private static final String[] DAYS = withOther(Day.values());
    private static final Map<String, Integer> PURPOSE_INDEX = indexLabels(PURPOSES);
    private static final Map<String, Integer> MODE_INDEX = indexLabels(MODES);
    private static final Map<String, Integer> DAY_INDEX = indexLabels(DAYS);

    private static final String PT_NOTE =
            "  (pt distance uses t.distance_auto as a proxy; MITO writes no distance_pt)";

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }
        Inputs inputs = args[0].toLowerCase().endsWith(".csv")
                ? Inputs.fromExplicitPaths(args)
                : Inputs.fromScenario(args);
        inputs.print();

        Map<String, Integer> zoneIrsd = inputs.zones == null ? Map.of() : readZoneIrsd(inputs.zones);

        ExposureData data = readExposure(inputs.exposure, zoneIrsd);

        System.out.printf("%nRead %,d persons from %s%n", data.n, inputs.exposure);
        if (inputs.zones != null) {
            System.out.printf("IRSD deciles joined from %s (%,d zones; %,d persons in zones without an IRSD decile)%n",
                    inputs.zones, zoneIrsd.size(), data.countIrsdNa());
        }

        printPopulationOverview(data);
        printExposureSummaries(data);
        printSportByAgeSex(data);
        printTotalByAgeSex(data);
        if (inputs.zones != null) {
            printSportByIrsd(data);
        }

        if (inputs.trips != null) {
            TripData trips = readTrips(inputs.trips, data);
            if (trips == null) {
                System.out.println("\n(Trips file could not be interpreted; MITO trip tables skipped.)");
            } else {
                System.out.printf("%nRead %,d trips from %s%n", trips.n, inputs.trips);
                if (inputs.routedDir != null) {
                    readRoutedTrips(inputs.routedDir, trips);
                    System.out.printf("Routed trip exposure joined from %,d healthIndicators_*.csv files in %s%n",
                            inputs.routedFileCount, inputs.routedDir);
                } else {
                    System.out.println("(No healthIndicators_*.csv files found; routed distance/time columns skipped.)");
                }
                printTripOverview(trips, data);
                printTripsByDay(trips);
                printModeByPurpose(trips);
                printTripDistanceByPurpose(trips);
                printTripTimeByPurpose(trips);
                printTripsByMode(trips);

                WeeklyTravel weekly = aggregateWeekly(trips, data);
                printWeeklyOverall(weekly, data);
                printWeeklyByAgeSex(weekly, data);
                if (inputs.zones != null) {
                    printWeeklyByIrsd(weekly, data);
                }
            }
        } else {
            System.out.println("\n(No trips file supplied; MITO trip tables skipped.)");
        }

        if (inputs.tracker != null) {
            DiseaseData diseases = readTracker(inputs.tracker);
            System.out.printf("%nRead disease states for %,d persons from %s (state year: %s; %,d not matched to exposure file)%n",
                    diseases.stateByPerson.size(), inputs.tracker, diseases.stateYear,
                    diseases.countUnmatched(data.indexById));
            printDiseasePrevalence(data, diseases);
            printDiseaseByAgeSex(data, diseases);
            if (inputs.zones != null) {
                printDiseaseByIrsd(data, diseases);
            }
        } else {
            System.out.println("\n(No disease tracker file supplied; disease prevalence tables skipped.)");
        }
    }

    private static void printUsage() {
        System.err.println("Usage: HealthOutputSummaryMEL <scenario> [year]");
        System.err.println("       run from the scenario data folder, e.g. \"HealthOutputSummaryMEL base\"");
        System.err.println("   or: HealthOutputSummaryMEL <pp_exposure.csv> [tracker.csv] [zoneSystem.csv] [trips.csv] [healthIndicatorsDir]");
        System.err.println("       (explicit-path form; pass \"-\" to skip an optional argument)");
    }

    // ------------------------------------------------------------------ input resolution

    /**
     * Resolves the five input roles, either from a scenario name (the conventional
     * scenOutput/&lt;scenario&gt;/ layout, relative to the working directory) or from
     * explicit paths. Only the person exposure file is mandatory; the rest are set to
     * null when absent so their tables are skipped.
     */
    private static class Inputs {
        String scenario;
        String year;
        String exposure;
        String tracker;
        String zones;
        String trips;
        String routedDir;
        int routedFileCount;

        private final Map<String, String> tried = new LinkedHashMap<>();
        private final Map<String, String> status = new LinkedHashMap<>();

        static Inputs fromExplicitPaths(String[] args) {
            Inputs in = new Inputs();
            in.exposure = args[0];
            in.tracker = optionalArg(args, 1);
            in.zones = optionalArg(args, 2);
            in.trips = optionalArg(args, 3);
            in.routedDir = optionalArg(args, 4);
            if (in.trips != null && in.routedDir == null) {
                // healthIndicators_*.csv sit one level above the microData folder holding trips.csv
                Path parent = Paths.get(in.trips).toAbsolutePath().getParent();
                if (parent != null && parent.getParent() != null) {
                    in.routedDir = parent.getParent().toString();
                }
            }
            in.resolve();
            return in;
        }

        static Inputs fromScenario(String[] args) {
            Inputs in = new Inputs();
            in.scenario = args[0];
            Path microData = Paths.get("scenOutput", in.scenario, "microData");
            in.year = args.length > 1 ? args[1] : detectYear(microData);
            in.exposure = microData.resolve("pp_exposure_" + in.year + ".csv").toString();
            in.tracker = microData.resolve("pp_healthDiseaseTracker_" + in.year + ".csv").toString();
            in.zones = Paths.get("input", "zoneSystem.csv").toString();
            // note the asymmetry: pp_* sit directly under microData/, trips.csv under <year>/microData/
            Path yearDir = Paths.get("scenOutput", in.scenario, in.year);
            in.trips = yearDir.resolve("microData").resolve("trips.csv").toString();
            in.routedDir = yearDir.toString();
            in.resolve();
            return in;
        }

        /** Records what was tried, drops what is missing, and aborts if the exposure file is absent. */
        private void resolve() {
            boolean exposureFound = isFile(exposure);
            record("person exposure", exposure, exposureFound ? "found" : "MISSING (required)");

            record("disease tracker", tracker, isFile(tracker) ? "found" : "missing");
            if (!isFile(tracker)) tracker = null;

            record("zone system (IRSD)", zones, isFile(zones) ? "found" : "missing");
            if (!isFile(zones)) zones = null;

            record("MITO trips", trips, isFile(trips) ? "found" : "missing");
            if (!isFile(trips)) {
                trips = null;
                routedDir = null;
            }

            routedFileCount = countRoutedFiles(routedDir);
            record("routed trip exposure", routedDir,
                    routedFileCount > 0
                            ? String.format("found (%d healthIndicators_*.csv)", routedFileCount)
                            : "missing");
            if (routedFileCount == 0) routedDir = null;

            if (!exposureFound) {
                print();
                System.err.println();
                System.err.println("Required person exposure file not found. Run this from the scenario data folder");
                System.err.println("(e.g. D:/projects/jibe/melbourne), not from the silo repository.");
                System.exit(1);
            }
        }

        private void record(String role, String path, String state) {
            tried.put(role, path == null ? "(not supplied)" : path);
            status.put(role, state);
        }

        void print() {
            printHeading("Resolved inputs" + (scenario == null
                    ? " (explicit paths)"
                    : " (scenario \"" + scenario + "\", year " + year + ")"));
            System.out.println("Working directory: " + Paths.get("").toAbsolutePath());
            for (String role : tried.keySet()) {
                System.out.printf("%-22s %-34s %s%n", role, status.get(role), tried.get(role));
            }
        }
    }

    private static String optionalArg(String[] args, int i) {
        return args.length > i && !"-".equals(args[i]) ? args[i] : null;
    }

    private static boolean isFile(String path) {
        return path != null && new File(path).isFile();
    }

    private static int countRoutedFiles(String dir) {
        File[] files = routedFiles(dir);
        return files == null ? 0 : files.length;
    }

    private static File[] routedFiles(String dir) {
        if (dir == null) return null;
        return new File(dir).listFiles((d, name) ->
                name.startsWith("healthIndicators_") && name.toLowerCase().endsWith(".csv"));
    }

    /**
     * Picks the latest year from pp_exposure_&lt;year&gt;.csv. The anchored pattern matters:
     * the same folder holds variants such as "pp_exposure_2018 - 100hh backup.csv" and
     * pp_exposure_2018.xlsx that must not match.
     */
    private static String detectYear(Path microData) {
        File[] files = microData.toFile().listFiles();
        if (files == null) {
            System.err.println("Directory not found: " + microData.toAbsolutePath());
            System.err.println("Working directory: " + Paths.get("").toAbsolutePath());
            System.exit(1);
        }
        Pattern pattern = Pattern.compile("^pp_exposure_(\\d{4})\\.csv$");
        String best = null;
        for (File f : files) {
            Matcher m = pattern.matcher(f.getName());
            if (m.matches() && (best == null || m.group(1).compareTo(best) > 0)) {
                best = m.group(1);
            }
        }
        if (best == null) {
            System.err.println("No pp_exposure_<year>.csv found in " + microData.toAbsolutePath() + "; it contains:");
            for (File f : files) System.err.println("  " + f.getName());
            System.err.println("Supply the year explicitly: HealthOutputSummaryMEL <scenario> <year>");
            System.exit(1);
        }
        return best;
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
            int posTravelTime = indexOf(header, "totalTravelTime_sec");
            int[] posSummary = new int[SUMMARY_COLUMNS.length];

            for (int c = 0; c < SUMMARY_COLUMNS.length; c++) {
                if (c == MMET_TOTAL) {
                    posSummary[c] = -1; // derived below
                } else {
                    posSummary[c] = indexOf(header, SUMMARY_COLUMNS[c]);

                    if (posSummary[c] < 0) {
                        System.err.println("Column " + SUMMARY_COLUMNS[c]
                                + " not found in " + path + "; reported as NaN.");
                    }
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
                    values[c] = posSummary[c] < 0
                            ? Float.NaN
                            : parseFloatSafe(cols[posSummary[c]]);
                }

                // Derive total mMET hours/week.
                // Return NaN if any component is missing, rather than treating missing data as zero.
                if (!Float.isNaN(values[MMET_WALK])
                        && !Float.isNaN(values[MMET_CYCLE])
                        && !Float.isNaN(values[MMET_OTHER_SPORT])) {

                    values[MMET_TOTAL] =
                            values[MMET_WALK]
                                    + values[MMET_CYCLE]
                                    + values[MMET_OTHER_SPORT];
                } else {
                    values[MMET_TOTAL] = Float.NaN;
                }
                data.add(Integer.parseInt(cols[posId]),
                        Integer.parseInt(cols[posAge]),
                        Integer.parseInt(cols[posGender]),
                        irsd, values,
                        posTravelTime < 0 ? Float.NaN : parseFloatSafe(cols[posTravelTime]));
            }
        }
        return data;
    }

    /**
     * Reads the MITO 7-day trips file. Each row carries all four skim distances (km) and
     * times (minutes); the mode actually chosen selects which pair the trip incurred.
     *
     * Every trip is held in memory (about 30 bytes each, plus the t.id map) so that the
     * quantiles reported below are exact. That is comfortable for the test runs and for
     * full Melbourne at the -Xmx60g the model itself is given.
     */
    private static TripData readTrips(String path, ExposureData persons) throws IOException {
        TripData trips = new TripData();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String[] header = splitCsv(br.readLine());
            int posTripId = indexOf(header, "t.id");
            int posPerson = indexOf(header, "p.ID");
            if (posTripId < 0 || posPerson < 0) {
                System.err.println("Columns t.id and p.ID are required in " + path + ".");
                return null;
            }
            int posPurpose = requireColumn(header, "t.purpose", path);
            int posMode = requireColumn(header, "mode", path);
            int posDay = requireColumn(header, "departure_day", path);
            int posDistWalk = requireColumn(header, "t.distance_walk", path);
            int posDistBike = requireColumn(header, "t.distance_bike", path);
            int posDistAuto = requireColumn(header, "t.distance_auto", path);
            int posTimeAuto = requireColumn(header, "time_auto", path);
            int posTimePt = requireColumn(header, "time_pt", path);
            int posTimeWalk = requireColumn(header, "time_walk", path);
            int posTimeBike = requireColumn(header, "time_bike", path);

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = splitCsv(line);
                int tripId = Integer.parseInt(cols[posTripId]);
                if (trips.indexById.containsKey(tripId)) {
                    trips.duplicateIds++;
                    continue;
                }
                String modeName = posMode < 0 ? "" : cols[posMode];
                float distanceKm;
                float timeMin;
                switch (modeName) {
                    case "walk":
                        distanceKm = value(cols, posDistWalk);
                        timeMin = value(cols, posTimeWalk);
                        break;
                    case "bicycle":
                        distanceKm = value(cols, posDistBike);
                        timeMin = value(cols, posTimeBike);
                        break;
                    case "pt":
                    case "bus":
                    case "train":
                    case "tramOrMetro":
                        distanceKm = value(cols, posDistAuto); // no distance_pt is written
                        timeMin = value(cols, posTimePt);
                        break;
                    case "autoDriver":
                    case "autoPassenger":
                    case "taxi":
                    case "pooledTaxi":
                    case "privateAV":
                    case "sharedAV":
                        distanceKm = value(cols, posDistAuto);
                        timeMin = value(cols, posTimeAuto);
                        break;
                    default:
                        distanceKm = Float.NaN;
                        timeMin = Float.NaN;
                }
                Integer personIdx = persons.indexById.get(Integer.parseInt(cols[posPerson]));
                if (personIdx == null) trips.unmatchedPersons++;

                trips.add(tripId,
                        personIdx == null ? -1 : personIdx,
                        code(PURPOSE_INDEX, PURPOSES, posPurpose < 0 ? null : cols[posPurpose]),
                        code(MODE_INDEX, MODES, modeName),
                        code(DAY_INDEX, DAYS, posDay < 0 ? null : cols[posDay]),
                        distanceKm, timeMin);
            }
        }
        return trips;
    }

    /**
     * Joins the MATSim-routed trip exposure files (one per day x mode) onto the trips by
     * t.id. Routed distance is written in metres and routed time in seconds; both are
     * converted here to the km and minutes used by the MITO skim columns.
     */
    private static void readRoutedTrips(String dir, TripData trips) throws IOException {
        File[] files = routedFiles(dir);
        if (files == null) return;
        Arrays.sort(files);
        for (File file : files) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String headerLine = br.readLine();
                if (headerLine == null) continue;
                String[] header = splitCsv(headerLine);
                int posId = indexOf(header, "t.id");
                int posMode = indexOf(header, "t.mode");
                int posTime = indexOf(header, "t.matsimTravelTime_s");
                int posDist = indexOf(header, "t.matsimTravelDistance_m");
                if (posId < 0 || posTime < 0 || posDist < 0) {
                    System.err.println("Skipping " + file.getName() + ": expected columns not found.");
                    continue;
                }
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] cols = splitCsv(line);
                    Integer idx = trips.indexById.get(Integer.parseInt(cols[posId]));
                    if (idx == null) {
                        trips.routedUnmatched++;
                        continue;
                    }
                    trips.routedDistKm[idx] = value(cols, posDist) / 1000f;
                    trips.routedTimeMin[idx] = value(cols, posTime) / 60f;
                    trips.routedMatched++;
                    if (posMode >= 0 && !MODES[trips.mode[idx]].equals(cols[posMode])) {
                        trips.routedModeMismatch++;
                    }
                }
            }
        }
        trips.hasRouted = trips.routedMatched > 0;
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

    private static void printTotalByAgeSex(ExposureData data) {
        printHeading("Total mMET hours/week (mmetHr_total) by age band and sex");

        System.out.printf("%-10s %-8s %10s %8s %9s %8s %8s %8s %9s%n",
                "Age band", "Sex", "n", "% zero", "mean",
                "p25", "p50", "p75", "max");

        for (int b = 0; b < AGE_BANDS.length; b++) {
            for (int s = 0; s < SEXES.length; s++) {
                List<Float> values = new ArrayList<>();

                for (int i = 0; i < data.n; i++) {
                    if (ageBand(data.age[i]) == b
                            && sexIndex(data.gender[i]) == s) {
                        values.add(data.values[i][MMET_TOTAL]);
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

    // ------------------------------------------------------------------ MITO trip tables

    private static void printTripOverview(TripData trips, ExposureData data) {
        printHeading("MITO trip overview");
        Set<Integer> personsWithTrips = new HashSet<>();
        long noSkim = 0;
        for (int i = 0; i < trips.n; i++) {
            if (trips.personIdx[i] >= 0) personsWithTrips.add(trips.personIdx[i]);
            if (Float.isNaN(trips.distKm[i])) noSkim++;
        }
        System.out.printf("%-52s %,12d%n", "Trips read", trips.n);
        System.out.printf("%-52s %,12d%n", "Trips skipped as duplicate t.id", trips.duplicateIds);
        System.out.printf("%-52s %,12d%n", "Trips whose p.ID is not in the exposure file", trips.unmatchedPersons);
        System.out.printf("%-52s %,12d %7.2f%%%n", "Persons with at least one trip",
                personsWithTrips.size(), 100.0 * personsWithTrips.size() / data.n);
        System.out.printf("%-52s %,12d %7.2f%%%n", "Persons with no trips",
                data.n - personsWithTrips.size(), 100.0 * (data.n - personsWithTrips.size()) / data.n);
        System.out.printf("%-52s %,12d%n", "Trips with no skim distance for the chosen mode", noSkim);
        if (trips.hasRouted) {
            System.out.printf("%-52s %,12d %7.2f%%%n", "Trips matched to a routed record",
                    trips.routedMatched, 100.0 * trips.routedMatched / trips.n);
            System.out.printf("%-52s %,12d%n", "Routed records with no matching t.id", trips.routedUnmatched);
            System.out.printf("%-52s %,12d%n", "Routed records whose mode disagrees with trips.csv",
                    trips.routedModeMismatch);
        }
    }

    private static void printTripsByDay(TripData trips) {
        printHeading("Trips by day of week");
        long[] byDay = new long[DAYS.length];
        for (int i = 0; i < trips.n; i++) byDay[trips.day[i]]++;
        System.out.printf("%-12s %12s %8s%n", "Day", "n", "%");
        for (int d = 0; d < DAYS.length; d++) {
            if (byDay[d] == 0) continue;
            System.out.printf("%-12s %,12d %7.2f%%%n", DAYS[d], byDay[d], 100.0 * byDay[d] / trips.n);
        }
        System.out.printf("%-12s %,12d%n", "Total", (long) trips.n);

        long[][] counts = new long[DAYS.length][MODES.length];
        for (int i = 0; i < trips.n; i++) counts[trips.day[i]][trips.mode[i]]++;
        printHeading("Mode choice by day of week (row % of that day's trips)");
        printCrossTab("Day", DAYS, MODES, counts);
    }

    private static void printModeByPurpose(TripData trips) {
        long[][] counts = new long[PURPOSES.length][MODES.length];
        for (int i = 0; i < trips.n; i++) counts[trips.purpose[i]][trips.mode[i]]++;
        printHeading("Mode choice by trip purpose (row % of that purpose's trips)");
        printCrossTab("Purpose", PURPOSES, MODES, counts);
    }

    private static void printTripDistanceByPurpose(TripData trips) {
        printHeading("Trip distance (km) by purpose - MITO skim for the chosen mode");
        System.out.println(PT_NOTE);
        printByPurpose(trips, trips.distKm);
        if (trips.hasRouted) {
            printHeading("Trip distance (km) by purpose - MATSim routed (t.matsimTravelDistance_m)");
            printByPurpose(trips, trips.routedDistKm);
        }
    }

    private static void printTripTimeByPurpose(TripData trips) {
        printHeading("Trip travel time (minutes) by purpose - MITO skim for the chosen mode");
        printByPurpose(trips, trips.timeMin);
        if (trips.hasRouted) {
            printHeading("Trip travel time (minutes) by purpose - MATSim routed (t.matsimTravelTime_s)");
            printByPurpose(trips, trips.routedTimeMin);
        }
    }

    private static void printByPurpose(TripData trips, float[] values) {
        System.out.printf("%-10s %-8s %10s %8s %9s %8s %8s %8s %9s%n",
                "Purpose", "", "n", "% zero", "mean", "p25", "p50", "p75", "max");
        for (int p = 0; p < PURPOSES.length; p++) {
            List<Float> group = new ArrayList<>();
            for (int i = 0; i < trips.n; i++) {
                if (trips.purpose[i] == p) group.add(values[i]);
            }
            if (group.isEmpty()) continue;
            printGroupStatsRow(PURPOSES[p], "", group);
        }
        List<Float> all = new ArrayList<>();
        for (int i = 0; i < trips.n; i++) all.add(values[i]);
        printGroupStatsRow("All", "", all);
    }

    private static void printTripsByMode(TripData trips) {
        printHeading("Distance, time and implied speed by mode");
        System.out.println(PT_NOTE);
        System.out.println("  Implied speed is mean distance / mean time; a value far outside the plausible range");
        System.out.println("  for a mode points to an inconsistency between the distance and time skims.");
        System.out.printf("%-16s %10s %10s %10s %11s %10s %10s %10s %11s%n",
                "Mode", "n", "skim km", "skim min", "skim km/h",
                "n routed", "routed km", "routed min", "routed km/h");
        for (int m = 0; m < MODES.length; m++) {
            long n = 0;
            long nRouted = 0;
            double sumDist = 0, sumTime = 0, sumRoutedDist = 0, sumRoutedTime = 0;
            for (int i = 0; i < trips.n; i++) {
                if (trips.mode[i] != m) continue;
                n++;
                if (!Float.isNaN(trips.distKm[i])) sumDist += trips.distKm[i];
                if (!Float.isNaN(trips.timeMin[i])) sumTime += trips.timeMin[i];
                if (!Float.isNaN(trips.routedDistKm[i]) && !Float.isNaN(trips.routedTimeMin[i])) {
                    nRouted++;
                    sumRoutedDist += trips.routedDistKm[i];
                    sumRoutedTime += trips.routedTimeMin[i];
                }
            }
            if (n == 0) continue;
            double meanDist = sumDist / n;
            double meanTime = sumTime / n;
            System.out.printf("%-16s %,10d %10.3f %10.2f %11.2f",
                    MODES[m], n, meanDist, meanTime, speed(meanDist, meanTime));
            if (nRouted > 0) {
                double meanRoutedDist = sumRoutedDist / nRouted;
                double meanRoutedTime = sumRoutedTime / nRouted;
                System.out.printf(" %,10d %10.3f %10.2f %11.2f%n",
                        nRouted, meanRoutedDist, meanRoutedTime, speed(meanRoutedDist, meanRoutedTime));
            } else {
                System.out.printf(" %10s %10s %10s %11s%n", "-", "-", "-", "-");
            }
        }
    }

    private static double speed(double km, double minutes) {
        return minutes <= 0 ? Double.NaN : km / (minutes / 60.0);
    }

    // ------------------------------------------------------------------ weekly per-person tables

    /**
     * Weekly per-person totals. trips.csv covers monday to sunday, so a straight sum over
     * a person's trips is already a weekly figure. Routed totals are only meaningful when
     * every one of a person's trips was routed, so partially routed persons carry NaN and
     * are excluded from the routed rows rather than understating them.
     */
    private static WeeklyTravel aggregateWeekly(TripData trips, ExposureData data) {
        WeeklyTravel w = new WeeklyTravel(data.n);
        for (int i = 0; i < trips.n; i++) {
            int p = trips.personIdx[i];
            if (p < 0) continue;
            w.trips[p]++;
            if (!Float.isNaN(trips.distKm[i])) w.distKm[p] += trips.distKm[i];
            if (!Float.isNaN(trips.timeMin[i])) w.timeMin[p] += trips.timeMin[i];
            if (!Float.isNaN(trips.routedDistKm[i]) && !Float.isNaN(trips.routedTimeMin[i])) {
                w.routedTrips[p]++;
                w.routedDistKm[p] += trips.routedDistKm[i];
                w.routedTimeMin[p] += trips.routedTimeMin[i];
            }
        }
        for (int p = 0; p < data.n; p++) {
            if (w.routedTrips[p] != w.trips[p]) {
                w.routedDistKm[p] = Float.NaN;
                w.routedTimeMin[p] = Float.NaN;
                if (w.trips[p] > 0) w.partiallyRouted++;
            }
        }
        w.hasRouted = trips.hasRouted;
        return w;
    }

    private static void printWeeklyOverall(WeeklyTravel w, ExposureData data) {
        printHeading("Weekly travel per person");
        System.out.println("  \"all persons\" covers everyone in the exposure file, with zero-trip persons contributing 0;");
        System.out.println("  \"trip-makers\" is restricted to persons with at least one trip in trips.csv.");
        if (w.hasRouted && w.partiallyRouted > 0) {
            System.out.printf("  %,d trip-making persons had only some of their trips routed and are excluded from the routed rows.%n",
                    w.partiallyRouted);
        }
        boolean[] makers = new boolean[data.n];
        for (int p = 0; p < data.n; p++) makers[p] = w.trips[p] > 0;

        System.out.printf("%-42s %10s %8s %9s %9s %8s %8s %8s %8s %9s%n",
                "Variable", "n", "% zero", "mean", "sd", "min", "p25", "p50", "p75", "max");
        printWeeklyPair("trips/week", toFloat(w.trips), makers);
        printWeeklyPair("distance km/week, skim", w.distKm, makers);
        printWeeklyPair("travel time min/week, skim", w.timeMin, makers);
        if (w.hasRouted) {
            printWeeklyPair("distance km/week, routed", w.routedDistKm, makers);
            printWeeklyPair("travel time min/week, routed", w.routedTimeMin, makers);
        }
        printSummaryRow("travel time min/week, pp_exposure", scale(data.travelTimeSec, data.n, 1f / 60f), 42);
    }

    private static void printWeeklyPair(String label, float[] values, boolean[] makers) {
        printSummaryRow(label + " (all persons)", values, 42);
        printSummaryRow(label + " (trip-makers)", subset(values, makers), 42);
    }

    private static void printWeeklyByAgeSex(WeeklyTravel w, ExposureData data) {
        printWeeklyStrata(w, data, true);
    }

    private static void printWeeklyByIrsd(WeeklyTravel w, ExposureData data) {
        printWeeklyStrata(w, data, false);
    }

    private static void printWeeklyStrata(WeeklyTravel w, ExposureData data, boolean byAgeSex) {
        List<String> titles = new ArrayList<>(List.of(
                "Weekly trips per person",
                "Weekly distance (km) per person, skim",
                "Weekly travel time (minutes) per person, skim"));
        List<float[]> measures = new ArrayList<>(List.of(toFloat(w.trips), w.distKm, w.timeMin));
        if (w.hasRouted) {
            titles.add("Weekly distance (km) per person, routed");
            measures.add(w.routedDistKm);
            titles.add("Weekly travel time (minutes) per person, routed");
            measures.add(w.routedTimeMin);
        }
        for (int m = 0; m < measures.size(); m++) {
            float[] values = measures.get(m);
            if (byAgeSex) {
                printHeading(titles.get(m) + ", by age band and sex (all persons)");
                System.out.printf("%-10s %-8s %10s %8s %9s %8s %8s %8s %9s%n",
                        "Age band", "Sex", "n", "% zero", "mean", "p25", "p50", "p75", "max");
                for (int b = 0; b < AGE_BANDS.length; b++) {
                    for (int s = 0; s < SEXES.length; s++) {
                        List<Float> group = new ArrayList<>();
                        for (int i = 0; i < data.n; i++) {
                            if (ageBand(data.age[i]) == b && sexIndex(data.gender[i]) == s) {
                                group.add(values[i]);
                            }
                        }
                        printGroupStatsRow(AGE_BANDS[b], SEXES[s], group);
                    }
                }
            } else {
                printHeading(titles.get(m) + ", by IRSD decile (1 = most disadvantaged, all persons)");
                System.out.printf("%-10s %-8s %10s %8s %9s %8s %8s %8s %9s%n",
                        "Decile", "", "n", "% zero", "mean", "p25", "p50", "p75", "max");
                for (int d = 1; d <= 10; d++) {
                    List<Float> group = new ArrayList<>();
                    for (int i = 0; i < data.n; i++) {
                        if (data.irsd[i] == d) group.add(values[i]);
                    }
                    printGroupStatsRow(String.valueOf(d), "", group);
                }
                List<Float> naValues = new ArrayList<>();
                for (int i = 0; i < data.n; i++) {
                    if (data.irsd[i] < 1) naValues.add(values[i]);
                }
                printGroupStatsRow("NA", "", naValues);
            }
        }
    }

    // ------------------------------------------------------------------ disease tables

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

    /** Counts with row percentages; all-zero rows and columns are dropped to keep the table readable. */
    private static void printCrossTab(String rowHeader, String[] rowLabels, String[] colLabels, long[][] counts) {
        List<Integer> keptCols = new ArrayList<>();
        for (int c = 0; c < colLabels.length; c++) {
            for (long[] row : counts) {
                if (row[c] > 0) {
                    keptCols.add(c);
                    break;
                }
            }
        }
        StringBuilder head = new StringBuilder(String.format("%-12s", rowHeader));
        for (int c : keptCols) head.append(String.format(" %17s", colLabels[c]));
        head.append(String.format(" %12s", "total"));
        System.out.println(head);

        long[] colTotals = new long[colLabels.length];
        long grandTotal = 0;
        for (int r = 0; r < rowLabels.length; r++) {
            long rowTotal = 0;
            for (int c : keptCols) rowTotal += counts[r][c];
            if (rowTotal == 0) continue;
            StringBuilder row = new StringBuilder(String.format("%-12s", rowLabels[r]));
            for (int c : keptCols) {
                row.append(String.format(" %17s",
                        String.format("%,d (%.1f%%)", counts[r][c], 100.0 * counts[r][c] / rowTotal)));
                colTotals[c] += counts[r][c];
            }
            row.append(String.format(" %,12d", rowTotal));
            System.out.println(row);
            grandTotal += rowTotal;
        }
        StringBuilder totals = new StringBuilder(String.format("%-12s", "Total"));
        for (int c : keptCols) {
            totals.append(String.format(" %17s", String.format("%,d (%.1f%%)",
                    colTotals[c], grandTotal == 0 ? 0.0 : 100.0 * colTotals[c] / grandTotal)));
        }
        totals.append(String.format(" %,12d", grandTotal));
        System.out.println(totals);
    }

    private static void printSummaryRow(String label, float[] values) {
        printSummaryRow(label, values, 32);
    }

    private static void printSummaryRow(String label, float[] values, int labelWidth) {
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
            System.out.printf("%-" + labelWidth + "s %10s%n", label, "no data");
            return;
        }
        double mean = sum / n;
        double sd = n > 1 ? Math.sqrt((sumSq - sum * mean) / (n - 1)) : Double.NaN;
        float[] sorted = sortedArray(valid);
        System.out.printf("%-" + labelWidth + "s %,10d %7.2f%% %9.3f %9.3f %8.2f %8.2f %8.2f %8.2f %9.2f%n",
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

    /** Enum constant names plus a trailing "other" bucket for unrecognised tokens. */
    private static String[] withOther(Enum<?>[] values) {
        String[] labels = new String[values.length + 1];
        for (int i = 0; i < values.length; i++) labels[i] = values[i].name();
        labels[values.length] = "other";
        return labels;
    }

    private static Map<String, Integer> indexLabels(String[] labels) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < labels.length; i++) index.put(labels[i], i);
        return index;
    }

    private static byte code(Map<String, Integer> index, String[] labels, String token) {
        Integer i = token == null ? null : index.get(token);
        return (byte) (i == null ? labels.length - 1 : i); // last slot is the "other" bucket
    }

    private static int requireColumn(String[] header, String column, String path) {
        int pos = indexOf(header, column);
        if (pos < 0) {
            System.err.println("Column " + column + " not found in " + path
                    + "; reported as NaN or \"other\".");
        }
        return pos;
    }

    private static float value(String[] cols, int pos) {
        return pos < 0 || pos >= cols.length ? Float.NaN : parseFloatSafe(cols[pos]);
    }

    private static float[] toFloat(int[] values) {
        float[] out = new float[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i];
        return out;
    }

    private static float[] scale(float[] values, int n, float factor) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = values[i] * factor;
        return out;
    }

    private static float[] subset(float[] values, boolean[] keep) {
        int n = 0;
        for (boolean b : keep) if (b) n++;
        float[] out = new float[n];
        int j = 0;
        for (int i = 0; i < keep.length; i++) {
            if (keep[i]) out[j++] = values[i];
        }
        return out;
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
        float[] travelTimeSec = new float[1 << 20];
        float[][] values = new float[1 << 20][];
        final Map<Integer, Integer> indexById = new HashMap<>();

        void add(int id, int ageYears, int genderCode, int irsdDecile, float[] summaryValues,
                 float totalTravelTimeSec) {
            if (n == age.length) {
                int newSize = age.length + (age.length >> 1);
                age = Arrays.copyOf(age, newSize);
                gender = Arrays.copyOf(gender, newSize);
                irsd = Arrays.copyOf(irsd, newSize);
                travelTimeSec = Arrays.copyOf(travelTimeSec, newSize);
                values = Arrays.copyOf(values, newSize);
            }
            age[n] = ageYears;
            gender[n] = (byte) genderCode;
            irsd[n] = (byte) (irsdDecile < 1 || irsdDecile > 10 ? -1 : irsdDecile);
            travelTimeSec[n] = totalTravelTimeSec;
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

    private static class TripData {
        int n = 0;
        int[] personIdx = new int[1 << 16];      // index into ExposureData, -1 if unmatched
        byte[] purpose = new byte[1 << 16];      // index into PURPOSES; last slot is "other"
        byte[] mode = new byte[1 << 16];
        byte[] day = new byte[1 << 16];
        float[] distKm = new float[1 << 16];     // MITO skim for the chosen mode
        float[] timeMin = new float[1 << 16];
        float[] routedDistKm = new float[1 << 16];
        float[] routedTimeMin = new float[1 << 16];
        final Map<Integer, Integer> indexById = new HashMap<>();

        long duplicateIds = 0;
        long unmatchedPersons = 0;
        long routedMatched = 0;
        long routedUnmatched = 0;
        long routedModeMismatch = 0;
        boolean hasRouted = false;

        void add(int tripId, int person, byte purposeCode, byte modeCode, byte dayCode,
                 float distanceKm, float travelTimeMin) {
            if (n == personIdx.length) {
                int newSize = personIdx.length + (personIdx.length >> 1);
                personIdx = Arrays.copyOf(personIdx, newSize);
                purpose = Arrays.copyOf(purpose, newSize);
                mode = Arrays.copyOf(mode, newSize);
                day = Arrays.copyOf(day, newSize);
                distKm = Arrays.copyOf(distKm, newSize);
                timeMin = Arrays.copyOf(timeMin, newSize);
                routedDistKm = Arrays.copyOf(routedDistKm, newSize);
                routedTimeMin = Arrays.copyOf(routedTimeMin, newSize);
            }
            personIdx[n] = person;
            purpose[n] = purposeCode;
            mode[n] = modeCode;
            day[n] = dayCode;
            distKm[n] = distanceKm;
            timeMin[n] = travelTimeMin;
            routedDistKm[n] = Float.NaN;
            routedTimeMin[n] = Float.NaN;
            indexById.put(tripId, n);
            n++;
        }
    }

    private static class WeeklyTravel {
        final int[] trips;
        final int[] routedTrips;
        final float[] distKm;
        final float[] timeMin;
        final float[] routedDistKm;
        final float[] routedTimeMin;
        long partiallyRouted = 0;
        boolean hasRouted = false;

        WeeklyTravel(int nPersons) {
            trips = new int[nPersons];
            routedTrips = new int[nPersons];
            distKm = new float[nPersons];
            timeMin = new float[nPersons];
            routedDistKm = new float[nPersons];
            routedTimeMin = new float[nPersons];
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
