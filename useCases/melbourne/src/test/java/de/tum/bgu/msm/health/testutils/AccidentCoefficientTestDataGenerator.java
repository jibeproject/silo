package de.tum.bgu.msm.health.testutils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Utility class for generating accident model coefficient files for testing purposes.
 * Provides reusable methods to create various types of coefficient CSV files.
 */
public class AccidentCoefficientTestDataGenerator {

    /**
     * Creates a standard binary logit model coefficient file with typical parameters.
     */
    public static void createStandardBinaryModelFile(String basePath) throws IOException {
        String content = "coefficient,value\n" +
                        "intercept,2.5\n" +
                        "bike_demand,-0.3\n" +
                        "car_demand,0.6\n" +
                        "length,0.8\n" +
                        "speed_limit,-0.02\n" +
                        "bike_stress,0.5\n";
        writeToFile(basePath, "binaryModel.csv", content);
    }

    /**
     * Creates a minimal binary model file for basic testing.
     */
    public static void createMinimalBinaryModelFile(String basePath) throws IOException {
        String content = "coefficient,value\n" +
                        "intercept,2.5\n" +
                        "bike_demand,-0.8\n" +
                        "length,1.2\n";
        writeToFile(basePath, "binaryModel.csv", content);
    }

    /**
     * Creates a standard Poisson model coefficient file.
     */
    public static void createStandardPoissonModelFile(String basePath) throws IOException {
        String content = "coefficient,value\n" +
                        "intercept,1.5\n" +
                        "bike_demand,0.2\n" +
                        "car_demand,0.1\n" +
                        "length,0.3\n";
        writeToFile(basePath, "poissonModel.csv", content);
    }

    /**
     * Creates a minimal Poisson model file for basic testing.
     */
    public static void createMinimalPoissonModelFile(String basePath) throws IOException {
        String content = "coefficient,value\n" +
                        "intercept,1.5\n" +
                        "car_demand,0.3\n" +
                        "speed_limit,-0.1\n";
        writeToFile(basePath, "poissonModel.csv", content);
    }

    /**
     * Creates a standard time-of-day coefficient file.
     */
    public static void createStandardTimeOfDayFile(String basePath) throws IOException {
        String content = "hour,coefficient\n" +
                        "8,1.2\n" +
                        "9,1.1\n" +
                        "17,1.5\n" +
                        "18,1.3\n" +
                        "23,0.7\n";
        writeToFile(basePath, "timeOfDay.csv", content);
    }

    /**
     * Creates a minimal time-of-day file for basic testing.
     */
    public static void createMinimalTimeOfDayFile(String basePath) throws IOException {
        String content = "hour,coefficient\n" +
                        "8,0.8\n" +
                        "17,1.2\n" +
                        "23,0.5\n";
        writeToFile(basePath, "timeOfDay.csv", content);
    }

    /**
     * Creates coefficient files with special characters for edge case testing.
     */
    public static void createSpecialCharacterCoefficientsFile(String basePath, String filename) throws IOException {
        String content = "coefficient,value\n" +
                        "bike_lane_width,1.5\n" +
                        "junction_type,-2.0\n" +
                        "signal_density,0.8\n";
        writeToFile(basePath, filename, content);
    }

    /**
     * Creates an empty file for error testing.
     */
    public static void createEmptyFile(String basePath, String filename) throws IOException {
        writeToFile(basePath, filename, "");
    }

    /**
     * Creates all standard coefficient files needed for comprehensive testing.
     */
    public static void createAllStandardFiles(String basePath) throws IOException {
        createStandardBinaryModelFile(basePath);
        createStandardPoissonModelFile(basePath);
        createStandardTimeOfDayFile(basePath);
    }

    /**
     * Creates all minimal coefficient files needed for basic testing.
     */
    public static void createAllMinimalFiles(String basePath) throws IOException {
        createMinimalBinaryModelFile(basePath);
        createMinimalPoissonModelFile(basePath);
        createMinimalTimeOfDayFile(basePath);
    }

    private static void writeToFile(String basePath, String filename, String content) throws IOException {
        File file = new File(basePath + filename);
        file.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }
}
