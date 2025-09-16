package de.tum.bgu.msm.util;

import de.tum.bgu.msm.data.Purpose;
import de.tum.bgu.msm.resources.Resources;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ExtractCoefficient {
    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(ExtractCoefficient.class);

    // Cache: Purpose -> Map<Row, Map<Column, Value>>
    private static final Map<Purpose, Map<String, Map<String, Double>>> coefficientCache = new ConcurrentHashMap<>();

    // Flag to enable test mode (looks in test directories first)
    private static boolean testMode = false;

    /**
     * Set the test mode flag. When test mode is enabled, the class will first look for coefficient files
     * in the test directories before falling back to the regular paths.
     *
     * @param isTestMode true to enable test mode, false to disable
     */
    public static void setTestMode(boolean isTestMode) {
        testMode = isTestMode;
        if (isTestMode) {
            // Clear the cache when entering test mode to ensure fresh data
            coefficientCache.clear();
        }
    }

    /**
     * Clear the coefficient cache to ensure fresh data is loaded from files.
     * Useful for testing when data changes.
     */
    public static void clearCache() {
        coefficientCache.clear();
    }

    public static Double extractCoefficient(Purpose purpose, String targetColumn, String targetRow) {
        if (purpose == null || targetColumn == null || targetRow == null) {
            logger.warn("Invalid input: purpose={}, targetColumn={}, targetRow={};  (returning 0.0)", purpose, targetColumn, targetRow);
            return 0.0;
        }

        // Check cache first
        Map<String, Map<String, Double>> table = coefficientCache.get(purpose);
        if (table == null) {
            // Not cached: load and parse CSV
            Path csvFilePath = getCoefficientsFilePath(purpose);

            if (csvFilePath == null) {
                logger.error("CSV file path could not be determined for the given purpose: {}", purpose);
                return 0.0;
            }

            table = loadCsvData(csvFilePath);
            if (table != null && !table.isEmpty()) {
                coefficientCache.put(purpose, table);
            } else {
                return 0.0; // Failed to load data
            }
        }

        // Lookup value in cache
        Map<String, Double> row = table.get(targetRow);
        if (row != null) {
            Double value = row.get(targetColumn);
            if (value != null) {
                return value;
            } else {
                // Don't log errors for columns that don't exist, just return 0
                logger.debug("Value for targetColumn '{}' not found in row '{}'", targetColumn, targetRow);
            }
        } else {
            // Don't log errors for rows that don't exist, just return 0
            logger.debug("Row for targetRow '{}' not found", targetRow);
        }

        return 0.0; // Return 0 if no match is found
    }

    /**
     * Determine the path to the coefficients file based on various fallback strategies
     */
    private static Path getCoefficientsFilePath(Purpose purpose) {
        Path csvFilePath = null;

        if (testMode) {
            String testPath = "src/test/java/de/tum/bgu/msm/util/mc_coefficients_" + purpose.toString().toLowerCase() + ".csv";
            File testFile = new File(testPath);
            if (testFile.exists()) {
                csvFilePath = testFile.toPath();
            }
        }

        if (Resources.instance != null) {
            csvFilePath = Resources.instance.getModeChoiceCoefficients(purpose);
        }

        return csvFilePath;
    }

    private static Map<String, Map<String, Double>> loadCsvData(Path csvFilePath) {
        if (csvFilePath == null || !new File(csvFilePath.toString()).exists()) {
            logger.error("CSV file does not exist: {}", csvFilePath);
            return null;
        }

        Map<String, Map<String, Double>> table = new ConcurrentHashMap<>();

        try (CSVParser parser = new CSVParser(
                new FileReader(String.valueOf(csvFilePath)),
                CSVFormat.Builder.create()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .build()
        )) {
            for (CSVRecord record : parser) {
                String rowKey = record.get(0);
                if (rowKey == null) continue;
                Map<String, Double> row = new ConcurrentHashMap<>();

                // Get all header names except the first one (which is the row key column)
                for (String colName : parser.getHeaderNames()) {
                    if (colName.equals(parser.getHeaderNames().getFirst())) continue;

                    String value = record.get(colName);
                    if (value != null && !value.isEmpty()) {
                        try {
                            row.put(colName, Double.valueOf(value));
                        } catch (NumberFormatException e) {
                            logger.warn("Invalid number format for value '{}' in column '{}': {}",
                                    value, colName, e.getMessage());
                        }
                    }
                }
                table.put(rowKey, row);
            }
            return table;
        } catch (IOException e) {
            logger.error("Error reading CSV file {}: {}", csvFilePath, e.getMessage());
            return null;
        }
    }
}
