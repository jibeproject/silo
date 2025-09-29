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
    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(uk.cam.mrc.phm.util.ExtractCoefficient.class);

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

        Path csvFilePath = Resources.instance.getModeChoiceCoefficients(purpose);
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
        logger.warn("No matching record found for row '{}' in CSV file for purpose '{}'", targetRow, purpose);
        return null;
    }
}