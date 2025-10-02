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
 * Manages health output file existence checks to avoid unnecessary reprocessing.
 * Checks for existing traffic flow and health indicator files before processing.
 * Loads existing traffic flow data into memory to preserve downstream dependencies.
 */
public class HealthOutputFileManager {
    private static final Logger logger = LogManager.getLogger(HealthOutputFileManager.class);

    private final String baseOutputDirectory;
    private final String scenarioName;

    public HealthOutputFileManager(String baseDirectory, String scenarioName) {
        this.baseOutputDirectory = buildBaseOutputDirectory(baseDirectory, scenarioName);
        this.scenarioName = scenarioName;
        logger.info("HealthOutputFileManager created for scenario: {}, output directory: {}",
                   scenarioName, this.baseOutputDirectory);
    }

    public boolean shouldSkipTrafficFlowProcessing(int year, Day day, String mode,
                                                   Map<Day, Map<String, Map<Id<Link>, Map<Integer, Integer>>>> trafficFlowsByDayModeLinkHour,
                                                   Network network) {
        if (hasNullInputs(day, mode)) {
            return false;
        }

        String filePath = buildTrafficFlowFilePath(year, day, mode);

        if (checkFileExistsAndLog(filePath, "traffic flow", day, mode)) {
            loadTrafficFlowsFromCSV(filePath, day, mode, trafficFlowsByDayModeLinkHour);
            return true;
        }
        return false;
    }

    public boolean shouldSkipHealthIndicatorProcessing(int year, Day day, Mode mode) {
        if (hasNullInputs(day, mode)) {
            return false;
        }

        String filePath = buildHealthIndicatorFilePath(year, day, mode);
        return checkFileExistsAndLog(filePath, "health indicator", day, mode);
    }

    private void loadTrafficFlowsFromCSV(String filePath, Day day, String mode,
                                        Map<Day, Map<String, Map<Id<Link>, Map<Integer, Integer>>>> trafficFlowsByDayModeLinkHour) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line = reader.readLine(); // Skip header line

            Map<Id<Link>, Map<Integer, Integer>> modeFlows = trafficFlowsByDayModeLinkHour
                .computeIfAbsent(day, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mode, k -> new ConcurrentHashMap<>());

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue; // Skip empty lines
                }

                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    try {
                        Id<Link> linkId = Id.createLinkId(parts[0].trim());
                        int hour = Integer.parseInt(parts[1].trim());
                        int count = Integer.parseInt(parts[2].trim());

                        modeFlows.computeIfAbsent(linkId, k -> new HashMap<>())
                                .put(hour, count);
                    } catch (NumberFormatException e) {
                        logger.warn("Skipping invalid line in traffic flow file {}: {}", filePath, line);
                    }
                } else {
                    logger.warn("Skipping malformed line in traffic flow file {}: {}", filePath, line);
                }
            }

            logger.info("Successfully loaded traffic flows from existing file: {} (loaded {} links)",
                       filePath, modeFlows.size());

        } catch (IOException e) {
            logger.warn("Failed to load traffic flows from {}, downstream processes may be affected: {}",
                       filePath, e.getMessage());
        }
    }

    private String buildBaseOutputDirectory(String baseDirectory, String scenarioName) {
        return baseDirectory + "scenOutput/" + scenarioName + "/";
    }

    private String buildTrafficFlowFilePath(int year, Day day, String mode) {
        return baseOutputDirectory + year + "/traffic_flows_" + day + "_" + mode + ".csv";
    }

    private String buildHealthIndicatorFilePath(int year, Day day, Mode mode) {
        return baseOutputDirectory + year + "/healthIndicators_" + day + "_" + mode + ".csv";
    }

    private boolean checkFileExistsAndLog(String filePath, String fileType, Object day, Object mode) {
        File file = new File(filePath);
        if (file.exists()) {
            logger.info("Existing {} file found for day: {}, mode: {}, skipping processing: {}",
                       fileType, day, mode, filePath);
            return true;
        }
        return false;
    }

    private boolean hasNullInputs(Object day, Object mode) {
        return day == null || mode == null;
    }
}
