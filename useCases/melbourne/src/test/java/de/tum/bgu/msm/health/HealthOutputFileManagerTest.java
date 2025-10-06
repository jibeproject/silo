package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.data.Day;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for HealthOutputFileManager to verify file existence checking functionality
 * and traffic flow data loading for memory state preservation.
 * Tests follow TDD approach and validate the complete workflow including data loading.
 */
class HealthOutputFileManagerTest {

    private HealthOutputFileManager fileManager;
    private Network network; // We don't need to mock this for our tests
    private String testBaseDirectory;
    private static final String TEST_SCENARIO_NAME = "test_scenario";
    private static final int TEST_YEAR = 2018;
    private Map<Day, Map<String, Map<Id<Link>, Map<Integer, Integer>>>> trafficFlowsByDayModeLinkHour;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        testBaseDirectory = temporaryDirectory.toString() + File.separator;
        fileManager = new HealthOutputFileManager(testBaseDirectory, TEST_SCENARIO_NAME);
        network = null; // Not needed for file existence and data loading tests
        trafficFlowsByDayModeLinkHour = new ConcurrentHashMap<>();
    }

    @AfterEach
    void tearDown() {
        // Clean up any created test files
        fileManager = null;
        network = null;
        trafficFlowsByDayModeLinkHour = null;
    }

    @Test
    void testShouldSkipTrafficFlowProcessingWhenFileExists() throws IOException {
        // Given: A traffic flow file exists with sample data
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "walk");

        // When: Checking if file exists and loading data
        boolean fileExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.thursday, "walk");
        if (fileExists) {
            fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "walk", trafficFlowsByDayModeLinkHour, network);
        }

        // Then: File should exist and data should be loaded
        assertTrue(fileExists, "Traffic flow file should exist");
        assertTrue(trafficFlowsByDayModeLinkHour.containsKey(Day.thursday), "Thursday data should be loaded");
    }

    @Test
    void testShouldNotSkipTrafficFlowProcessingWhenFileDoesNotExist() {
        // Given: No traffic flow file exists (clean test environment)

        // When: Checking if file exists
        boolean fileExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.thursday, "walk");

        // Then: File should not exist
        assertFalse(fileExists, "Traffic flow file should not exist");
    }

    @Test
    void testTrafficFlowDataLoadedIntoMemoryWhenFileExists() throws IOException {
        // Given: A traffic flow file exists with specific data
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "walk");

        // When: Loading data from existing file
        fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "walk", trafficFlowsByDayModeLinkHour, network);

        // Then: Data should be loaded into memory
        assertTrue(trafficFlowsByDayModeLinkHour.containsKey(Day.thursday), "Thursday data should be loaded");
        assertTrue(trafficFlowsByDayModeLinkHour.get(Day.thursday).containsKey("walk"), "Walk mode data should be loaded");

        Map<Id<Link>, Map<Integer, Integer>> walkData = trafficFlowsByDayModeLinkHour.get(Day.thursday).get("walk");
        assertFalse(walkData.isEmpty(), "Walk data should not be empty");

        // Verify specific data was loaded correctly
        Id<Link> linkId1 = Id.createLinkId("link1");
        Id<Link> linkId2 = Id.createLinkId("link2");
        assertTrue(walkData.containsKey(linkId1), "Link1 data should be loaded");
        assertTrue(walkData.containsKey(linkId2), "Link2 data should be loaded");

        assertEquals(Integer.valueOf(15), walkData.get(linkId1).get(8), "Link1 hour 8 count should be 15");
        assertEquals(Integer.valueOf(25), walkData.get(linkId2).get(9), "Link2 hour 9 count should be 25");
    }

    @Test
    void testShouldSkipHealthIndicatorProcessingWhenFileExists() throws IOException {
        // Given: A health indicator file exists
        createHealthIndicatorFile(TEST_YEAR, Day.saturday, Mode.bicycle);

        // When: Checking if file exists
        boolean fileExists = fileManager.healthIndicatorFileExists(TEST_YEAR, Day.saturday, Mode.bicycle);

        // Then: File should exist
        assertTrue(fileExists, "Health indicator file should exist");
    }

    @Test
    void testShouldNotSkipHealthIndicatorProcessingWhenFileDoesNotExist() {
        // Given: No health indicator file exists (clean test environment)

        // When: Checking if file exists
        boolean fileExists = fileManager.healthIndicatorFileExists(TEST_YEAR, Day.saturday, Mode.bicycle);

        // Then: File should not exist
        assertFalse(fileExists, "Health indicator file should not exist");
    }

    @Test
    void testMultipleTrafficFlowFilesLoadedIndependently() throws IOException {
        // Given: Multiple traffic flow files exist for different modes
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "walk");
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "bicycle");
        // Note: "car" file does not exist

        // When: Checking each mode independently and loading data
        boolean walkExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.thursday, "walk");
        boolean bicycleExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.thursday, "bicycle");
        boolean carExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.thursday, "car");

        if (walkExists) {
            fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "walk", trafficFlowsByDayModeLinkHour, network);
        }
        if (bicycleExists) {
            fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "bicycle", trafficFlowsByDayModeLinkHour, network);
        }
        if (carExists) {
            fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "car", trafficFlowsByDayModeLinkHour, network);
        }

        // Then: Only existing files should be found and data loaded
        assertTrue(walkExists, "Walk file should exist");
        assertTrue(bicycleExists, "Bicycle file should exist");
        assertFalse(carExists, "Car file should not exist");

        // Verify data loaded for both existing modes
        Map<String, Map<Id<Link>, Map<Integer, Integer>>> thursdayData = trafficFlowsByDayModeLinkHour.get(Day.thursday);
        assertTrue(thursdayData.containsKey("walk"), "Walk data should be loaded");
        assertTrue(thursdayData.containsKey("bicycle"), "Bicycle data should be loaded");
        assertFalse(thursdayData.containsKey("car"), "Car data should not be loaded (file doesn't exist)");
    }

    @Test
    void testSelectiveFileProcessingAfterDeletion() throws IOException {
        // Given: All files exist initially and data is loaded
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "walk");
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "bicycle");
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.walk);
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.bicycle);

        // Load data for both modes
        fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "walk", trafficFlowsByDayModeLinkHour, network);
        fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "bicycle", trafficFlowsByDayModeLinkHour, network);

        // Verify all files exist initially
        assertTrue(fileManager.healthIndicatorFileExists(TEST_YEAR, Day.thursday, Mode.walk));
        assertTrue(fileManager.healthIndicatorFileExists(TEST_YEAR, Day.thursday, Mode.bicycle));

        // When: Strategically delete one traffic flow file
        deleteTrafficFlowFile(TEST_YEAR, Day.thursday, "walk");

        // Clear memory to simulate fresh start
        trafficFlowsByDayModeLinkHour.clear();

        // Then: Only the deleted file should need processing, others should load from existing files
        boolean walkExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.thursday, "walk");
        boolean bicycleExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.thursday, "bicycle");

        assertFalse(walkExists, "Walk file should not exist after deletion");
        assertTrue(bicycleExists, "Bicycle file should still exist");

        // Load available data
        fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "bicycle", trafficFlowsByDayModeLinkHour, network);

        // Verify bicycle data was loaded but walk data is not present
        assertTrue(trafficFlowsByDayModeLinkHour.get(Day.thursday).containsKey("bicycle"), "Bicycle data should be loaded from file");
        assertFalse(trafficFlowsByDayModeLinkHour.get(Day.thursday).containsKey("walk"), "Walk data should not be loaded (file deleted)");
    }

    @Test
    void testTrafficFlowDataStructurePreservation() throws IOException {
        // Given: Traffic flow file with comprehensive data structure
        createTrafficFlowFileWithComprehensiveData(TEST_YEAR, Day.saturday, "bicycle");

        // When: Loading data from file
        fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.saturday, "bicycle", trafficFlowsByDayModeLinkHour, network);

        // Then: Data structure should be preserved exactly as expected by downstream processes
        Map<Id<Link>, Map<Integer, Integer>> bicycleData = trafficFlowsByDayModeLinkHour.get(Day.saturday).get("bicycle");

        // Verify data structure integrity
        assertNotNull(bicycleData, "Bicycle data should exist");
        assertEquals(3, bicycleData.size(), "Should have 3 links loaded");

        // Verify each link has hourly data
        Id<Link> link1 = Id.createLinkId("link_a");
        Id<Link> link2 = Id.createLinkId("link_b");
        Id<Link> link3 = Id.createLinkId("link_c");

        assertTrue(bicycleData.containsKey(link1), "Link A should exist");
        assertTrue(bicycleData.containsKey(link2), "Link B should exist");
        assertTrue(bicycleData.containsKey(link3), "Link C should exist");

        // Verify hourly data for each link
        assertEquals(Integer.valueOf(10), bicycleData.get(link1).get(7), "Link A hour 7 should be 10");
        assertEquals(Integer.valueOf(0), bicycleData.get(link2).get(12), "Link B hour 12 should be 0");
        assertEquals(Integer.valueOf(30), bicycleData.get(link3).get(18), "Link C hour 18 should be 30");
    }

    @Test
    void testCorruptedFileHandling() throws IOException {
        // Given: A corrupted traffic flow file
        createCorruptedTrafficFlowFile(TEST_YEAR, Day.sunday, "walk");

        // When: Attempting to load data from corrupted file
        boolean fileExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.sunday, "walk");

        // Then: File should exist but data loading should handle corruption gracefully
        assertTrue(fileExists, "Corrupted file should still be detected as existing");

        // Loading should complete without throwing exceptions (graceful degradation)
        assertDoesNotThrow(() -> {
            fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.sunday, "walk", trafficFlowsByDayModeLinkHour, network);
        }, "Corruption handling should not throw exceptions");
    }

    @Test
    void testMemoryStateConsistencyAcrossDays() throws IOException {
        // Given: Traffic flow files for multiple days
        createTrafficFlowFileWithData(2020, Day.thursday, "walk");
        createTrafficFlowFileWithData(2020, Day.saturday, "walk");
        createTrafficFlowFileWithData(2020, Day.sunday, "walk");

        // When: Loading data for different days
        fileManager.loadTrafficFlowDataIfExists(2020, Day.thursday, "walk", trafficFlowsByDayModeLinkHour, network);
        fileManager.loadTrafficFlowDataIfExists(2020, Day.saturday, "walk", trafficFlowsByDayModeLinkHour, network);
        fileManager.loadTrafficFlowDataIfExists(2020, Day.sunday, "walk", trafficFlowsByDayModeLinkHour, network);

        // Then: Each day should maintain separate data
        assertTrue(trafficFlowsByDayModeLinkHour.containsKey(Day.thursday), "Thursday data should exist");
        assertTrue(trafficFlowsByDayModeLinkHour.containsKey(Day.saturday), "Saturday data should exist");
        assertTrue(trafficFlowsByDayModeLinkHour.containsKey(Day.sunday), "Sunday data should exist");

        // Verify data independence
        assertNotSame(trafficFlowsByDayModeLinkHour.get(Day.thursday),
                     trafficFlowsByDayModeLinkHour.get(Day.saturday),
                     "Thursday and Saturday data should be independent");
    }

    // Helper methods for creating test files with actual data

    private void createTrafficFlowFileWithData(int year, Day day, String mode) throws IOException {
        String filePath = buildTrafficFlowTestPath(year, day, mode);
        createTestFileWithTrafficData(filePath, generateSampleTrafficData());
    }

    private void createTrafficFlowFileWithComprehensiveData(int year, Day day, String mode) throws IOException {
        String filePath = buildTrafficFlowTestPath(year, day, mode);
        createTestFileWithTrafficData(filePath, generateComprehensiveTrafficData());
    }

    private void createCorruptedTrafficFlowFile(int year, Day day, String mode) throws IOException {
        String filePath = buildTrafficFlowTestPath(year, day, mode);
        createTestFileWithTrafficData(filePath, generateCorruptedTrafficData());
    }

    private String generateSampleTrafficData() {
        return "linkId,hour,count\n" +
               "link1,8,15\n" +
               "link1,9,20\n" +
               "link2,8,10\n" +
               "link2,9,25\n" +
               "link2,10,5\n";
    }

    private String generateComprehensiveTrafficData() {
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

    private String generateCorruptedTrafficData() {
        return "linkId,hour,count\n" +
               "link1,8,15\n" +
               "link1,invalid_hour,20\n" +  // Corrupted line
               "link2,8\n" +               // Missing count
               "link2,9,25\n" +
               "invalid_line_format\n";    // Completely invalid
    }

    private void createTestFileWithTrafficData(String filePath, String data) throws IOException {
        File file = new File(filePath);
        file.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(data);
        }

        assertTrue(file.exists(), "Test file should be created successfully: " + filePath);
    }

    private void createHealthIndicatorFile(int year, Day day, Mode mode) throws IOException {
        String filePath = buildHealthIndicatorTestPath(year, day, mode);
        createTestFile(filePath);
    }

    private void deleteTrafficFlowFile(int year, Day day, String mode) {
        String filePath = buildTrafficFlowTestPath(year, day, mode);
        File file = new File(filePath);
        if (file.exists()) {
            file.delete();
        }
    }

    private String buildTrafficFlowTestPath(int year, Day day, String mode) {
        return testBaseDirectory + "scenOutput/" + TEST_SCENARIO_NAME + "/" + year + "/traffic_flows_" + day + "_" + mode + ".csv";
    }

    private String buildHealthIndicatorTestPath(int year, Day day, Mode mode) {
        return testBaseDirectory + "scenOutput/" + TEST_SCENARIO_NAME + "/" + year + "/healthIndicators_" + day + "_" + mode + ".csv";
    }

    private void createTestFile(String filePath) throws IOException {
        File file = new File(filePath);
        file.getParentFile().mkdirs();
        
        // Create file with some content so it's not empty (length > 0)
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("test,data\n");
            writer.write("sample,content\n");
        }
        
        assertTrue(file.exists(), "Test file should be created: " + filePath);
        assertTrue(file.length() > 0, "Test file should have content: " + filePath);
    }
}
