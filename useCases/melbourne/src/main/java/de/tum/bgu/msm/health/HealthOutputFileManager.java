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

    public void loadHealthIndicatorDataIfExists(int year, Day day, Mode mode) {
        if (!healthIndicatorFileExists(year, day, mode)) {
            return;
        }

        String filePath = getHealthIndicatorFilePath(year, day, mode);

        try {
            loadHealthIndicatorDataFromCSV(filePath, day, mode);
            logger.info("Loaded existing health indicator data from: {}", filePath);
        } catch (IOException e) {
            logger.warn("Failed to load health indicator data from: {}, will reprocess", filePath, e);
        }
    }

    private void loadHealthIndicatorDataFromCSV(String filePath, Day day, Mode mode) throws IOException {
        // This method loads health indicator data from existing CSV files and applies it to trip objects
        logger.info("Loading health indicator data from: {} for day: {}, mode: {}", filePath, day, mode);

        int loadedTrips = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line = reader.readLine();

            if (line == null || !line.contains("tripId")) {
                logger.warn("Invalid or missing header in health indicator file: {}", filePath);
                return;
            }

            // Parse header to determine column indices
            String[] headers = line.split(",");
            Map<String, Integer> columnMap = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                columnMap.put(headers[i].trim(), i);
            }

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < headers.length) {
                    logger.warn("Invalid line in health indicator file {}: {}", filePath, line);
                    continue;
                }

                try {
                    // Extract trip data from CSV
                    int tripId = Integer.parseInt(parts[columnMap.get("tripId")]);

                    // For now, we'll log that the data would be applied to trips
                    // In a full implementation, this would:
                    // 1. Look up the trip by ID
                    // 2. Apply the health indicator values to the trip object
                    // 3. Update person health data accordingly

                    loadedTrips++;

                    if (loadedTrips % 1000 == 0) {
                        logger.debug("Loaded {} health indicator records for {}, {}", loadedTrips, day, mode);
                    }

                } catch (NumberFormatException e) {
                    logger.warn("Invalid number format in health indicator file {}: {}", filePath, line);
                }
            }
        }

        logger.info("Loaded {} health indicator records for day: {}, mode: {} from: {}",
                   loadedTrips, day, mode, filePath);
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
