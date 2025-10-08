package de.tum.bgu.msm.health.io;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.function.BiConsumer;

/**
 * Utility class for reading noise data from files.
 * Provides common functionality for both activity location and link noise data reading.
 */
public class NoiseDataReader {

    private static final Logger logger = LogManager.getLogger(NoiseDataReader.class);
    private static final int HOURLY_BINS = 24;
    private static final int SECONDS_PER_HOUR = 3600;

    /**
     * Reads noise data files for 24-hour period and processes each file.
     *
     * @param basePath The base path where noise files are located
     * @param filePrefix Prefix for noise files (e.g., "immission" or "emission")
     * @param fileProcessor Function to process each noise file
     * @return Total number of records read across all files
     */
    public static int readNoiseFilesForDay(String basePath, String filePrefix, FileProcessor fileProcessor) {
        int totalRecordsRead = 0;

        for (int hourBin = 1; hourBin <= HOURLY_BINS; hourBin++) {
            double timeInSeconds = hourBin * SECONDS_PER_HOUR;
            String fileName = basePath + filePrefix + "_" + timeInSeconds + ".csv";
            int recordsReadInFile = fileProcessor.processFile(fileName, hourBin - 1);
            totalRecordsRead += recordsReadInFile;
        }

        logger.info("Completed reading noise data from " + HOURLY_BINS +
                    " files with a total of " + totalRecordsRead + " records.");

        return totalRecordsRead;
    }

    /**
     * Interface for processing individual noise data files
     */
    public interface FileProcessor {
        /**
         * Process a single noise data file
         *
         * @param filePath Path to the noise file
         * @param hourBinZeroBased The hour bin (0-23)
         * @return Number of records processed
         */
        int processFile(String filePath, int hourBinZeroBased);
    }

    /**
     * Utility method to read a noise file with the common CSV format
     *
     * @param filePath Path to the noise file
     * @param idColumnPosition Position of ID column in CSV
     * @param valueColumnPosition Position of value column in CSV
     * @param dataProcessor Function to process each data row
     * @return Number of records processed
     */
    public static int readNoiseFile(String filePath, int idColumnPosition, int valueColumnPosition,
                                   BiConsumer<String, Float> dataProcessor) {
        String recString = "";
        int recCount = 0;

        try {
            BufferedReader in = new BufferedReader(new FileReader(filePath));
            recString = in.readLine(); // Skip header

            while ((recString = in.readLine()) != null) {
                recCount++;
                String[] lineElements = recString.split(";");

                if (lineElements.length <= Math.max(idColumnPosition, valueColumnPosition)) {
                    logger.warn("Invalid line format: " + recString);
                    continue;
                }

                String id = lineElements[idColumnPosition];
                float value = Float.parseFloat(lineElements[valueColumnPosition]);

                dataProcessor.accept(id, value);
            }
        } catch (IOException e) {
            logger.error("Failed to read noise data file: " + filePath, e);
            return 0;
        } catch (NumberFormatException e) {
            logger.error("Error parsing value in file: " + filePath + " at record: " + recCount, e);
            logger.error("Record string: " + recString);
            return recCount;
        }

        return recCount;
    }
}
