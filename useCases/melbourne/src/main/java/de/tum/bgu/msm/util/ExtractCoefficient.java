package de.tum.bgu.msm.util;

import de.tum.bgu.msm.data.Purpose;
import de.tum.bgu.msm.resources.Resources;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;

public class ExtractCoefficient {
    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(ExtractCoefficient.class);

    // Test mode file path for NHBW purpose
    private static final String TEST_MODE_NHBW_FILE = "src/test/java/de/tum/bgu/msm/util/mc_coefficients_nhbw.csv";

    /**
     * Extracts a coefficient from a CSV file based on purpose, column, and row.
     * @param purpose The purpose for which to get the coefficient file
     * @param targetColumn The target column name
     * @param targetRow The target row name
     * @return The coefficient value, or null if not found or error occurred
     */
    public static Double extractCoefficient(Purpose purpose, String targetColumn, String targetRow) {
        if (purpose == null || targetColumn == null || targetRow == null) {
            logger.error("Invalid input: purpose, targetColumn, or targetRow is null.");
            return null;
        }

        Path csvFilePath;

        // Check if Resources.instance is available
        if (Resources.instance == null) {
            // Try to access resources directly without immediate test mode warning
            String purposeFileName = "mc_coefficients_" + purpose.toString().toLowerCase() + ".csv";
            Path productionPath = java.nio.file.Paths.get("input/mito/modeChoice/" + purposeFileName);
            if (java.nio.file.Files.exists(productionPath)) {
                csvFilePath = productionPath;
                logger.debug("{}: {}",  purpose, csvFilePath);
            } else if (purpose == Purpose.NHBW) {
                    Path testPath = java.nio.file.Paths.get(TEST_MODE_NHBW_FILE);
                    if (java.nio.file.Files.exists(testPath)) {
                        csvFilePath = testPath;
                        logger.debug("Test mode NHBW: {}", csvFilePath);
                    } else {
                        logger.error("No coefficient file found for purpose {} (Resources.instance unavailable and production file not found)", purpose);
                        return null;
                    }
            } else {
                logger.error("No coefficient file found for purpose {} (Resources.instance unavailable and production file not found)", purpose);
                return null;
            }
        } else {
            // Normal mode - use Resources.instance
            csvFilePath = Resources.instance.getModeChoiceCoefficients(purpose);
        }

        if (csvFilePath == null) {
            logger.error("CSV file path is null for the given purpose: {}", purpose);
            return null;
        }

        try (CSVParser parser = new CSVParser(
                new FileReader(csvFilePath.toString()),
                CSVFormat.Builder.create()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .build()
        )) {
            for (CSVRecord record : parser) {
                if (record.get(0) != null && record.get(0).equalsIgnoreCase(targetRow)) {
                    if (targetColumn == "bike") {
                        targetColumn = "bicycle";
                    }
                    String value = record.get(targetColumn);
                    if (value != null && !value.trim().isEmpty()) {
                        try {
                            return Double.valueOf(value.trim());
                        } catch (NumberFormatException e) {
                            logger.error("Error parsing value '{}' to Double for row '{}' and column '{}': {}",
                                    value, targetRow, targetColumn, e.getMessage());
                            return null;
                        }
                    } else {
                        logger.warn("Value for targetColumn '{}' in row '{}' is null or empty.", targetColumn, targetRow);
                        return null;
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error reading CSV file '{}': {}", csvFilePath, e.getMessage());
            return null;
        }

        // No matching record found
        logger.debug("No matching record found for row '{}' in CSV file for purpose '{}'", targetRow, purpose);
        return null;
    }
}