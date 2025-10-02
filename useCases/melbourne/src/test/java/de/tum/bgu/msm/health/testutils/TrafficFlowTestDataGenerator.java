package de.tum.bgu.msm.health.testutils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Utility class for generating traffic flow CSV files for testing purposes.
 * Provides reusable methods to create various types of traffic flow data files.
 */
public class TrafficFlowTestDataGenerator {

    /**
     * Generates standard traffic flow data for basic testing.
     */
    public static String generateStandardTrafficFlowData() {
        return "linkId,hour,count\n" +
               "link1,8,15\n" +
               "link1,9,20\n" +
               "link2,8,10\n" +
               "link2,9,25\n" +
               "link2,10,5\n";
    }

    /**
     * Generates comprehensive traffic flow data with multiple links and hours.
     */
    public static String generateComprehensiveTrafficFlowData() {
        return "linkId,hour,count\n" +
               "link_a,7,10\n" +
               "link_a,8,15\n" +
               "link_a,9,20\n" +
               "link_b,10,5\n" +
               "link_b,11,8\n" +
               "link_b,12,0\n" +
               "link_c,16,25\n" +
               "link_c,17,35\n" +
               "link_c,18,30\n";
    }

    /**
     * Generates corrupted traffic flow data for error handling testing.
     */
    public static String generateCorruptedTrafficFlowData() {
        return "linkId,hour,count\n" +
               "link1,8,15\n" +
               "link1,invalid_hour,20\n" +
               "link2,8\n" +
               "link2,9,25\n" +
               "invalid_line_format\n";
    }

    /**
     * Creates a traffic flow file with specified data at given path.
     */
    public static void createTrafficFlowFile(String basePath, String filename, String data) throws IOException {
        File file = new File(basePath + filename);
        file.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(data);
        }
    }

    /**
     * Creates a standard traffic flow file for testing.
     */
    public static void createStandardTrafficFlowFile(String basePath, String filename) throws IOException {
        createTrafficFlowFile(basePath, filename, generateStandardTrafficFlowData());
    }
}
