package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.data.Mode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages health-related output files to optimise workflow execution by:
 * - Checking for existing output files to skip unnecessary processing
 * - Loading existing traffic flow data into memory for downstream processes
 * - Following single responsibility principle with separate methods for checking vs loading
 */
public class HealthOutputFileManager {
    private static final Logger logger = LogManager.getLogger(HealthOutputFileManager.class);

    private final String baseDirectory;
    private final String scenarioName;

    public HealthOutputFileManager(String baseDirectory, String scenarioName) {
        this.baseDirectory = baseDirectory;
        this.scenarioName = scenarioName;
    }

    public boolean healthIndicatorFileExists(int year, Day day, Mode mode) {
        String filePath = getHealthIndicatorFilePath(year, day, mode);
        File file = new File(filePath);
        boolean exists = file.exists() && file.length() > 0;

        if (exists) {
            logger.debug("Health indicator file exists: {}", filePath);
        }

        return exists;
    }

    public boolean trafficFlowFileExists(int year, Day day, String mode) {
        String filePath = getTrafficFlowFilePath(year, day, mode);
        File file = new File(filePath);
        boolean exists = file.exists() && file.length() > 0;

        if (exists) {
            logger.debug("Traffic flow file exists: {}", filePath);
        }

        return exists;
    }

    public void loadTrafficFlowDataIfExists(int year, Day day, String mode,
            Map<Day, Map<String, Map<Id<Link>, Map<Integer, Integer>>>> trafficFlowsByDayModeLinkHour,
            Network network) {

        if (!trafficFlowFileExists(year, day, mode)) {
            return;
        }

        String filePath = getTrafficFlowFilePath(year, day, mode);

        try {
            loadTrafficFlowDataFromCSV(filePath, day, mode, trafficFlowsByDayModeLinkHour, network);
            logger.info("Loaded existing traffic flow data from: {}", filePath);
        } catch (IOException e) {
            logger.warn("Failed to load traffic flow data from: {}, will reprocess", filePath, e);
        }
    }

    private void loadTrafficFlowDataFromCSV(String filePath, Day day, String mode,
            Map<Day, Map<String, Map<Id<Link>, Map<Integer, Integer>>>> trafficFlowsByDayModeLinkHour,
            Network network) throws IOException {

        int loadedRecords = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line = reader.readLine();

            if (line == null || !line.contains("linkId")) {
                logger.warn("Invalid or missing header in traffic flow file: {}", filePath);
                return;
            }

            trafficFlowsByDayModeLinkHour
                .computeIfAbsent(day, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mode, k -> new ConcurrentHashMap<>());

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length != 3) {
                    logger.warn("Invalid line in traffic flow file {}: {}", filePath, line);
                    continue;
                }

                try {
                    Id<Link> linkId = Id.createLinkId(parts[0]);
                    int hour = Integer.parseInt(parts[1]);
                    int count = Integer.parseInt(parts[2]);

                    // Only validate against network if network is provided (not null)
                    boolean shouldLoadLink = (network == null) || network.getLinks().containsKey(linkId);

                    if (shouldLoadLink) {
                        trafficFlowsByDayModeLinkHour.get(day).get(mode)
                            .computeIfAbsent(linkId, k -> new ConcurrentHashMap<>())
                            .put(hour, count);
                        loadedRecords++;
                    } else {
                        logger.debug("Link {} not found in network, skipping", linkId);
                    }

                } catch (NumberFormatException e) {
                    logger.warn("Invalid number format in traffic flow file {}: {}", filePath, line);
                }
            }
        }

        logger.info("Loaded {} traffic flow records for day: {}, mode: {} from: {}",
                   loadedRecords, day, mode, filePath);
    }

    public void loadHealthIndicatorDataIfExists(int year, Day day, Mode mode, Object dataContainer) {
        if (!healthIndicatorFileExists(year, day, mode)) {
            return;
        }

        String filePath = getHealthIndicatorFilePath(year, day, mode);

        try {
            // Load trip-to-person mapping first
            Map<Integer, Integer> tripToPersonMap = loadTripToPersonMapping(year);

            loadHealthIndicatorDataFromCSV(filePath, day, mode, dataContainer, tripToPersonMap);
            logger.info("Loaded existing health indicator data from: {}", filePath);
        } catch (IOException e) {
            logger.warn("Failed to load health indicator data from: {}, will reprocess", filePath, e);
        }
    }

    public void loadHealthIndicatorDataIfExists(int year, Day day, Mode mode, Object dataContainer, Map<Integer, ?> mitoTripsAll) {
        if (!healthIndicatorFileExists(year, day, mode)) {
            return;
        }

        String filePath = getHealthIndicatorFilePath(year, day, mode);

        try {
            // Create trip-to-person mapping from existing trip data
            Map<Integer, Integer> tripToPersonMap = createTripToPersonMapping(mitoTripsAll);

            loadHealthIndicatorDataFromCSV(filePath, day, mode, dataContainer, tripToPersonMap);
            logger.info("Loaded existing health indicator data from: {}", filePath);
        } catch (IOException e) {
            logger.warn("Failed to load health indicator data from: {}, will reprocess", filePath, e);
        }
    }

    /**
     * Creates the mapping from trip ID to person ID using the existing trip data
     */
    private Map<Integer, Integer> createTripToPersonMapping(Map<Integer, ?> mitoTripsAll) {
        Map<Integer, Integer> tripToPersonMap = new HashMap<>();

        logger.debug("Creating trip-to-person mapping from {} existing trips", mitoTripsAll.size());

        for (Map.Entry<Integer, ?> entry : mitoTripsAll.entrySet()) {
            try {
                Integer tripId = entry.getKey();
                Object trip = entry.getValue();

                // Use reflection to get the person ID from the trip object
                // Trip objects should have a getPerson() method
                Object personIdObj = trip.getClass().getMethod("getPerson").invoke(trip);
                if (personIdObj instanceof Integer) {
                    Integer personId = (Integer) personIdObj;
                    tripToPersonMap.put(tripId, personId);
                }
            } catch (Exception e) {
                logger.debug("Could not extract person ID from trip {}: {}", entry.getKey(), e.getMessage());
            }
        }

        logger.info("Created trip-to-person mapping with {} entries from existing trip data", tripToPersonMap.size());
        return tripToPersonMap;
    }

    /**
     * Loads the mapping from trip ID to person ID from the trips.csv file
     */
    private Map<Integer, Integer> loadTripToPersonMapping(int year) throws IOException {
        String tripsFilePath = baseDirectory + "scenOutput/" + scenarioName + "/" + year + "/microData/trips.csv";
        Map<Integer, Integer> tripToPersonMap = new HashMap<>();

        logger.debug("Loading trip-to-person mapping from: {}", tripsFilePath);

        try (BufferedReader reader = new BufferedReader(new FileReader(tripsFilePath))) {
            String line = reader.readLine();

            if (line == null || !line.contains("t.id")) {
                logger.warn("Invalid or missing header in trips file: {}", tripsFilePath);
                return tripToPersonMap;
            }

            // Parse header to find column indices
            String[] headers = line.split("\t"); // trips.csv uses tab delimiter
            Map<String, Integer> columnMap = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                columnMap.put(headers[i].trim(), i);
            }

            Integer tripIdCol = columnMap.get("t.id");
            Integer personIdCol = columnMap.get("p.ID");

            if (tripIdCol == null || personIdCol == null) {
                logger.warn("Required columns (t.id, p.ID) not found in trips file: {}", tripsFilePath);
                return tripToPersonMap;
            }

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length > Math.max(tripIdCol, personIdCol)) {
                    try {
                        int tripId = Integer.parseInt(parts[tripIdCol].trim());
                        int personId = Integer.parseInt(parts[personIdCol].trim());
                        tripToPersonMap.put(tripId, personId);
                    } catch (NumberFormatException e) {
                        logger.debug("Invalid number format in trips file line: {}", line);
                    }
                }
            }
        }

        logger.info("Loaded {} trip-to-person mappings from: {}", tripToPersonMap.size(), tripsFilePath);
        return tripToPersonMap;
    }

    private void loadHealthIndicatorDataFromCSV(String filePath, Day day, Mode mode, Object dataContainer, Map<Integer, Integer> tripToPersonMap) throws IOException {
        // This method loads health indicator data from existing CSV files and applies it to trip objects
        logger.info("Loading health indicator data from: {} for day: {}, mode: {}", filePath, day, mode);

        int loadedTrips = 0;
        int updatedPersons = 0;

        // Use synchronized block to prevent race conditions when multiple threads access the same file
        synchronized (this) {
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line = reader.readLine();

                if (line == null) {
                    logger.warn("Empty health indicator file: {}", filePath);
                    return;
                }

                // Check if this is a header line (contains non-numeric first field) or data line
                boolean hasHeader = false;
                String[] firstLineParts = line.split(",");
                if (firstLineParts.length > 0) {
                    try {
                        // Try to parse the first field as an integer (tripId)
                        Integer.parseInt(firstLineParts[0].trim());
                        // If successful, this is a data line, not a header
                    } catch (NumberFormatException e) {
                        // If parsing fails, this might be a header - check for common header patterns
                        hasHeader = line.contains("t.id") || line.contains("tripId") || line.contains("id");
                    }
                }

                // Define expected column order for data-only files (no header)
                // Based on the data structure: t.id,t.mode,t.matsimTravelTime_s,...
                Map<String, Integer> columnMap = new HashMap<>();

                if (hasHeader) {
                    // Parse header to determine column indices
                    String[] headers = line.split(",");
                    for (int i = 0; i < headers.length; i++) {
                        String header = headers[i].trim();
                        columnMap.put(header, i);
                        // Also map common aliases
                        if (header.equals("t.id")) {
                            columnMap.put("tripId", i);
                        }
                        if (header.equals("t.mode")) {
                            columnMap.put("mode", i);
                        }
                    }
                    // Read the first data line
                    line = reader.readLine();
                } else {
                    // No header - use default column mapping based on expected structure
                    columnMap.put("tripId", 0);
                    columnMap.put("mode", 1);
                    columnMap.put("distance", 2);
                    columnMap.put("duration", 3);
                    // Add more mappings as needed based on your data structure

                    // The current line is already the first data line, so don't read another
                }

                // Process data lines and update person health data
                while (line != null) {
                    String[] parts = line.split(",");
                    if (parts.length < 4) { // Minimum required columns
                        logger.warn("Invalid line in health indicator file {}: {}", filePath, line);
                        line = reader.readLine();
                        continue;
                    }

                    try {
                        // Extract trip data from CSV
                        Integer tripId = Integer.parseInt(parts[columnMap.get("tripId")]);

                        // Extract exposure data based on actual CSV structure
                        // Based on header: t.id,t.mode,t.matsimTravelTime_s,t.matsimTravelDistance_m,t.activityDuration_min,t.mmetHours,t.severeFatalInjuryRisk,t.links,t.exposurePm25,t.exposureNo2,t.activityExposurePm25,t.activityExposureNo2,t.exposureNoise,t.activityExposureNoise,t.exposureNdvi,t.activityExposureNdvi

                        if (dataContainer != null && tryUpdatePersonHealthFromLoadedData(dataContainer, tripId, parts, columnMap, mode, tripToPersonMap)) {
                            updatedPersons++;
                        }

                        loadedTrips++;

                        if (loadedTrips % 1000 == 0) {
                            logger.debug("Loaded {} health indicator records for {}, {}", loadedTrips, day, mode);
                        }

                    } catch (NumberFormatException e) {
                        logger.warn("Invalid number format in health indicator file {}: {}", filePath, line);
                    } catch (Exception e) {
                        logger.warn("Error processing health indicator line {}: {}", filePath, line, e);
                    }

                    line = reader.readLine();
                }
            }
        }

        logger.info("Loaded {} health indicator records for day: {}, mode: {} from: {} (updated {} persons)",
                   loadedTrips, day, mode, filePath, updatedPersons);
    }

    /**
     * Attempts to update person health data from loaded trip health indicators
     * This ensures that loaded exposure data is properly accumulated into person weekly totals
     */
    private boolean tryUpdatePersonHealthFromLoadedData(Object dataContainer, Integer tripId, String[] parts,
                                                       Map<String, Integer> columnMap, Mode mode, Map<Integer, Integer> tripToPersonMap) {
        try {
            // Get person ID from trip mapping
            Integer personId = tripToPersonMap.get(tripId);
            if (personId == null) {
                return false; // Can't update without person ID
            }

            // Access the household data manager to get the person
            Object householdDataManager = dataContainer.getClass().getMethod("getHouseholdDataManager").invoke(dataContainer);
            Object person = householdDataManager.getClass().getMethod("getPersonFromId", int.class).invoke(householdDataManager, personId.intValue());

            if (person == null) {
                logger.debug("Person {} not found for trip {}", personId, tripId);
                return false;
            }

            // Cast to PersonHealth to access the updateWeekly... methods
            if (!(person instanceof de.tum.bgu.msm.health.data.PersonHealth)) {
                logger.debug("Person {} is not a PersonHealth instance", personId);
                return false;
            }

            de.tum.bgu.msm.health.data.PersonHealth personHealth = (de.tum.bgu.msm.health.data.PersonHealth) person;

            // Extract exposure data from CSV
            Float travelTime = getFloatFromColumn(parts, columnMap, "t.matsimTravelTime_s");
            Float mmetHours = getFloatFromColumn(parts, columnMap, "t.mmetHours");
            Float activityDuration = getFloatFromColumn(parts, columnMap, "t.activityDuration_min");
            Float exposurePm25 = getFloatFromColumn(parts, columnMap, "t.exposurePm25");
            Float exposureNo2 = getFloatFromColumn(parts, columnMap, "t.exposureNo2");
            Float activityExposurePm25 = getFloatFromColumn(parts, columnMap, "t.activityExposurePm25");
            Float activityExposureNo2 = getFloatFromColumn(parts, columnMap, "t.activityExposureNo2");
            Float exposureNoise = getFloatFromColumn(parts, columnMap, "t.exposureNoise");
            Float activityExposureNoise = getFloatFromColumn(parts, columnMap, "t.activityExposureNoise");
            Float exposureNdvi = getFloatFromColumn(parts, columnMap, "t.exposureNdvi");
            Float activityExposureNdvi = getFloatFromColumn(parts, columnMap, "t.activityExposureNdvi");
            Float severeFatalInjuryRisk = getFloatFromColumn(parts, columnMap, "t.severeFatalInjuryRisk");

            // Update person weekly exposure totals - this is the crucial missing piece!

            // 1. Update travel time
            if (travelTime != null) {
                personHealth.updateWeeklyTravelSeconds(travelTime);
            }

            // 2. Update physical activity (MET hours)
            if (mmetHours != null) {
                personHealth.updateWeeklyMarginalMetHours(mode, mmetHours);
            }

            // 3. Update activity duration
            if (activityDuration != null) {
                personHealth.updateWeeklyActivityMinutes(activityDuration);
            }

            // 4. Update pollution exposure (travel)
            if (exposurePm25 != null || exposureNo2 != null) {
                Map<String, Float> travelExposures = new HashMap<>();
                if (exposurePm25 != null) {
                    travelExposures.put("pm2.5", exposurePm25);
                }
                if (exposureNo2 != null) {
                    travelExposures.put("no2", exposureNo2);
                }
                if (!travelExposures.isEmpty()) {
                    personHealth.updateWeeklyPollutionExposures(travelExposures);
                }
            }

            // 5. Update pollution exposure by hour (if we had hourly data, we'd need to reconstruct it)
            // For now, we'll approximate by spreading the exposure over 1 hour
            if (exposurePm25 != null || exposureNo2 != null) {
                Map<String, float[]> hourlyExposures = new HashMap<>();
                if (exposurePm25 != null) {
                    float[] pm25ByHour = new float[24*7]; // 24 hours * 7 days
                    pm25ByHour[0] = exposurePm25; // Simple approximation - put all exposure in first hour
                    hourlyExposures.put("pm2.5", pm25ByHour);
                }
                if (exposureNo2 != null) {
                    float[] no2ByHour = new float[24*7];
                    no2ByHour[0] = exposureNo2;
                    hourlyExposures.put("no2", no2ByHour);
                }
                if (!hourlyExposures.isEmpty()) {
                    personHealth.updateWeeklyPollutionExposuresByHour(hourlyExposures);
                }
            }

            // 6. Update noise exposure
            if (exposureNoise != null) {
                float[] noiseByHour = new float[24*7];
                noiseByHour[0] = exposureNoise; // Simple approximation
                personHealth.updateWeeklyNoiseExposuresByHour(noiseByHour);
            }

            // 7. Update green space exposure (NDVI)
            if (exposureNdvi != null) {
                personHealth.updateWeeklyGreenExposures(exposureNdvi);
            }

            // 8. Update accident/injury risk
            if (severeFatalInjuryRisk != null) {
                String riskType = getRiskTypeForMode(mode);
                if (riskType != null) {
                    Map<String, Double> risks = new HashMap<>();
                    risks.put(riskType, severeFatalInjuryRisk.doubleValue());
                    personHealth.updateWeeklyAccidentRisks(risks);
                }
            }

            // 9. Update travel activity hour occupied (simplified - we'd need more data for full implementation)
            float[] hourOccupied = new float[24*7];
            if (travelTime != null) {
                hourOccupied[0] = travelTime / 3600.0f; // Convert seconds to hours
                personHealth.updateWeeklyTravelActivityHourOccupied(hourOccupied);
            }

            return true;

        } catch (Exception e) {
            logger.debug("Could not update person health from loaded data for trip {}: {}", tripId, e.getMessage());
            return false;
        }
    }

    /**
     * Get the appropriate risk type name based on the travel mode
     */
    private String getRiskTypeForMode(Mode mode) {
        switch (mode) {
            case autoDriver:
            case autoPassenger:
                return "severeFatalInjuryCar";
            case bicycle:
                return "severeFatalInjuryBike";
            case walk:
                return "severeFatalInjuryWalk";
            default:
                return null;
        }
    }

    private Float getFloatFromColumn(String[] parts, Map<String, Integer> columnMap, String columnName) {
        Integer index = columnMap.get(columnName);
        if (index != null && index < parts.length) {
            try {
                String value = parts[index].trim();
                if ("null".equals(value) || value.isEmpty()) {
                    return null;
                }
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String getHealthIndicatorFilePath(int year, Day day, Mode mode) {
        return baseDirectory + "scenOutput/" + scenarioName + "/" + year + "/" +
               "healthIndicators_" + day + "_" + mode + ".csv";
    }

    private String getTrafficFlowFilePath(int year, Day day, String mode) {
        return baseDirectory + "scenOutput/" + scenarioName + "/" + year + "/" +
               "traffic_flows_" + day + "_" + mode + ".csv";
    }
}
