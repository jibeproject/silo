package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.data.Day;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for HealthExposureModelMEL file skipping functionality with traffic flow data loading.
 * Tests the integration between file manager and the main workflow methods including memory state preservation.
 * These tests validate the complete workflow optimization including data loading capabilities.
 */
class HealthExposureModelMELFileSkippingIntegrationTest {

    private HealthOutputFileManager fileManager;
    private Network network; // Not needed for file existence and data loading tests
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

    @Test
    void testHealthDataAssemblerSkipsProcessingWhenFileExists() throws IOException {
        // Given: Health indicator file already exists
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.walk);

        // When: healthDataAssembler logic is evaluated
        boolean shouldSkip = fileManager.healthIndicatorFileExists(TEST_YEAR, Day.thursday, Mode.walk);

        // Then: Processing should be skipped
        assertTrue(shouldSkip, "healthDataAssembler should skip processing when file exists");
    }

    @Test
    void testWriteTrafficFlowsSkipsProcessingAndLoadsData() throws IOException {
        // Given: Traffic flow file already exists with real data
        createTrafficFlowFileWithData(TEST_YEAR, Day.saturday, "bicycle");

        // When: writeTrafficFlowsToCSV logic is evaluated
        boolean fileExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.saturday, "bicycle");
        if (fileExists) {
            fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.saturday, "bicycle", trafficFlowsByDayModeLinkHour, network);
        }

        // Then: Processing should be skipped and data loaded into memory
        assertTrue(fileExists, "writeTrafficFlowsToCSV should skip processing when file exists");
        assertTrue(trafficFlowsByDayModeLinkHour.containsKey(Day.saturday), "Saturday data should be loaded");
        assertTrue(trafficFlowsByDayModeLinkHour.get(Day.saturday).containsKey("bicycle"), "Bicycle data should be loaded");
        assertFalse(trafficFlowsByDayModeLinkHour.get(Day.saturday).get("bicycle").isEmpty(), "Bicycle data should not be empty");
    }

    @Test
    void testDetermineModesToProcessFiltersExistingFilesAndLoadsData() throws IOException {
        // Given: Some traffic flow files exist, others don't
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "walk");
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "bike");
        // Note: "car" file does not exist

        // When: Determining which modes to process (simulating the workflow integration)
        List<String> allModes = Arrays.asList("car", "walk", "bike");
        List<String> modesToProcess = filterModesForProcessingWithDataLoading(allModes, TEST_YEAR, Day.thursday);

        // Then: Only modes without existing files should be processed, others should have data loaded
        assertEquals(1, modesToProcess.size(), "Should only process modes without existing files");
        assertTrue(modesToProcess.contains("car"), "Should process car mode (file does not exist)");
        assertFalse(modesToProcess.contains("walk"), "Should not process walk mode (file exists)");
        assertFalse(modesToProcess.contains("bike"), "Should not process bike mode (file exists)");

        // Verify data was loaded for existing files
        assertTrue(trafficFlowsByDayModeLinkHour.get(Day.thursday).containsKey("walk"), "Walk data should be loaded");
        assertTrue(trafficFlowsByDayModeLinkHour.get(Day.thursday).containsKey("bike"), "Bike data should be loaded");
        assertFalse(trafficFlowsByDayModeLinkHour.get(Day.thursday).containsKey("car"), "Car data should not be loaded");
    }

    @Test
    void testWorkflowMemoryStateConsistencyAfterFileSkipping() throws IOException {
        // Given: Complete workflow scenario with mixed existing files
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.walk);
        createHealthIndicatorFile(TEST_YEAR, Day.saturday, Mode.bicycle);
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "walk");
        createTrafficFlowFileWithData(TEST_YEAR, Day.saturday, "bicycle");

        // When: Processing workflow decisions for all combinations
        Day[] days = {Day.thursday, Day.saturday, Day.sunday};
        Mode[] modes = {Mode.walk, Mode.bicycle, Mode.autoDriver};
        String[] trafficModes = {"walk", "bicycle", "car"};

        int skippedHealthProcessing = 0;
        int skippedTrafficProcessing = 0;
        int dataLoadedCount = 0;

        for (Day day : days) {
            for (Mode mode : modes) {
                if (fileManager.healthIndicatorFileExists(TEST_YEAR, day, mode)) {
                    skippedHealthProcessing++;
                }
            }

            for (String mode : trafficModes) {
                if (fileManager.trafficFlowFileExists(TEST_YEAR, day, mode)) {
                    fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, day, mode, trafficFlowsByDayModeLinkHour, network);
                    skippedTrafficProcessing++;
                    dataLoadedCount++;
                }
            }
        }

        // Then: Verify correct processing decisions and memory state
        assertEquals(2, skippedHealthProcessing, "Should skip 2 health indicator processing (thursday/walk, saturday/bicycle)");
        assertEquals(2, skippedTrafficProcessing, "Should skip 2 traffic flow processing (thursday/walk, saturday/bicycle)");
        assertEquals(2, dataLoadedCount, "Should load data for 2 existing traffic flow files");

        // Verify memory state contains loaded data
        assertTrue(trafficFlowsByDayModeLinkHour.containsKey(Day.thursday), "Thursday traffic data should be in memory");
        assertTrue(trafficFlowsByDayModeLinkHour.containsKey(Day.saturday), "Saturday traffic data should be in memory");
        assertFalse(trafficFlowsByDayModeLinkHour.containsKey(Day.sunday), "Sunday traffic data should not be in memory");
    }

    @Test
    void testSelectiveProcessingAfterFileDeletion() throws IOException {
        // Given: All files exist initially
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.walk);
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.bicycle);
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "walk");
        createTrafficFlowFileWithData(TEST_YEAR, Day.thursday, "bicycle");

        // Load initial data to verify baseline
        fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "walk", trafficFlowsByDayModeLinkHour, network);
        fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "bicycle", trafficFlowsByDayModeLinkHour, network);

        // Verify all would be skipped initially
        assertTrue(fileManager.healthIndicatorFileExists(TEST_YEAR, Day.thursday, Mode.walk));
        assertTrue(fileManager.healthIndicatorFileExists(TEST_YEAR, Day.thursday, Mode.bicycle));

        // When: Strategically delete one file (testing scenario)
        deleteTrafficFlowFile(TEST_YEAR, Day.thursday, "walk");

        // Clear memory to simulate fresh workflow start
        trafficFlowsByDayModeLinkHour.clear();

        // Then: Verify selective processing and data loading
        boolean walkExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.thursday, "walk");
        boolean bicycleExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.thursday, "bicycle");

        if (bicycleExists) {
            fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "bicycle", trafficFlowsByDayModeLinkHour, network);
        }

        assertFalse(walkExists, "Should process walk traffic after file deletion");
        assertTrue(bicycleExists, "Should still skip bicycle traffic (file exists)");

        // Verify memory state reflects selective loading
        assertTrue(trafficFlowsByDayModeLinkHour.get(Day.thursday).containsKey("bicycle"), "Bicycle data should be loaded from file");
        assertFalse(trafficFlowsByDayModeLinkHour.get(Day.thursday).containsKey("walk"), "Walk data should not be loaded (file deleted)");
    }

    @Test
    void testDownstreamProcessCompatibilityWithLoadedData() throws IOException {
        // Given: Traffic flow file exists with realistic data structure
        createTrafficFlowFileWithRealisticData(TEST_YEAR, Day.thursday, "walk");

        // When: Data is loaded through file skipping mechanism
        boolean fileExists = fileManager.trafficFlowFileExists(TEST_YEAR, Day.thursday, "walk");
        if (fileExists) {
            fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, Day.thursday, "walk", trafficFlowsByDayModeLinkHour, network);
        }

        // Then: Loaded data should be compatible with downstream processing (e.g., RunLinkToPersonInjuryRisks)
        assertTrue(fileExists, "File should exist and data should be loaded");

        Map<Id<Link>, Map<Integer, Integer>> walkData = trafficFlowsByDayModeLinkHour.get(Day.thursday).get("walk");
        assertNotNull(walkData, "Walk data should be loaded");

        // Verify data structure matches what downstream processes expect
        for (Map.Entry<Id<Link>, Map<Integer, Integer>> linkEntry : walkData.entrySet()) {
            Id<Link> linkId = linkEntry.getKey();
            Map<Integer, Integer> hourlyData = linkEntry.getValue();

            assertNotNull(linkId, "Link ID should not be null");
            assertNotNull(hourlyData, "Hourly data should not be null");
            assertFalse(hourlyData.isEmpty(), "Hourly data should not be empty");

            // Verify hourly data structure
            for (Map.Entry<Integer, Integer> hourEntry : hourlyData.entrySet()) {
                Integer hour = hourEntry.getKey();
                Integer count = hourEntry.getValue();

                assertTrue(hour >= 0 && hour <= 23, "Hour should be valid (0-23)");
                assertTrue(count >= 0, "Count should be non-negative");
            }
        }
    }

    @Test
    void testCompleteWorkflowSkipsAllProcessingWhenAllFilesExist() throws IOException {
        // Given: Complete set of output files exist
        Day[] testDays = {Day.thursday, Day.saturday};
        Mode[] testModes = {Mode.walk, Mode.bicycle, Mode.autoDriver};
        String[] testTrafficModes = {"walk", "bicycle", "car"};

        // Create all health indicator files
        for (Day day : testDays) {
            for (Mode mode : testModes) {
                createHealthIndicatorFile(TEST_YEAR, day, mode);
            }
        }

        // Create all traffic flow files with data
        for (Day day : testDays) {
            for (String mode : testTrafficModes) {
                createTrafficFlowFileWithData(TEST_YEAR, day, mode);
            }
        }

        // When: Checking if any processing is needed
        boolean anyHealthProcessingNeeded = false;
        boolean anyTrafficProcessingNeeded = false;
        int totalDataLoaded = 0;

        for (Day day : testDays) {
            for (Mode mode : testModes) {
                if (!fileManager.healthIndicatorFileExists(TEST_YEAR, day, mode)) {
                    anyHealthProcessingNeeded = true;
                    break;
                }
            }
            for (String mode : testTrafficModes) {
                if (fileManager.trafficFlowFileExists(TEST_YEAR, day, mode)) {
                    fileManager.loadTrafficFlowDataIfExists(TEST_YEAR, day, mode, trafficFlowsByDayModeLinkHour, network);
                    totalDataLoaded++;
                } else {
                    anyTrafficProcessingNeeded = true;
                }
            }
        }

        // Then: No processing should be needed, all data loaded
        assertFalse(anyHealthProcessingNeeded, "Should skip all health processing when all files exist");
        assertFalse(anyTrafficProcessingNeeded, "Should skip all traffic processing when all files exist");
        assertEquals(6, totalDataLoaded, "Should load data for all 6 traffic flow files (2 days × 3 modes)");

        // Verify memory contains all loaded data
        assertEquals(2, trafficFlowsByDayModeLinkHour.size(), "Should have data for 2 days");
        for (Day day : testDays) {
            assertEquals(3, trafficFlowsByDayModeLinkHour.get(day).size(), "Should have data for 3 modes per day");
        }
    }

    // Helper methods

    private List<String> filterModesForProcessingWithDataLoading(List<String> allModes, int year, Day day) {
        // This simulates the updated determineModesToProcess method with data loading
        return allModes.stream()
            .filter(mode -> {
                boolean exists = fileManager.trafficFlowFileExists(year, day, mode);
                if (exists) {
                    fileManager.loadTrafficFlowDataIfExists(year, day, mode, trafficFlowsByDayModeLinkHour, network);
                }
                return !exists;
            })
            .collect(java.util.stream.Collectors.toList());
    }

    private void createHealthIndicatorFile(int year, Day day, Mode mode) throws IOException {
        String filePath = buildHealthIndicatorTestPath(year, day, mode);
        createTestFile(filePath);
    }

    private void createTrafficFlowFileWithData(int year, Day day, String mode) throws IOException {
        String filePath = buildTrafficFlowTestPath(year, day, mode);
        createTestFileWithTrafficData(filePath, generateSampleTrafficData());
    }

    private void createTrafficFlowFileWithRealisticData(int year, Day day, String mode) throws IOException {
        String filePath = buildTrafficFlowTestPath(year, day, mode);
        createTestFileWithTrafficData(filePath, generateRealisticTrafficData());
    }

    private void deleteTrafficFlowFile(int year, Day day, String mode) {
        String filePath = buildTrafficFlowTestPath(year, day, mode);
        File file = new File(filePath);
        if (file.exists()) {
            file.delete();
        }
    }

    private String generateSampleTrafficData() {
        return "linkId,hour,count\n" +
               "link1,8,15\n" +
               "link1,9,20\n" +
               "link2,8,10\n" +
               "link2,9,25\n" +
               "link2,10,5\n";
    }

    private String generateRealisticTrafficData() {
        return "linkId,hour,count\n" +
               "link_main_st,7,45\n" +
               "link_main_st,8,120\n" +
               "link_main_st,9,85\n" +
               "link_main_st,17,95\n" +
               "link_main_st,18,140\n" +
               "link_side_rd,8,25\n" +
               "link_side_rd,9,30\n" +
               "link_side_rd,17,35\n" +
               "link_residential,12,5\n" +
               "link_residential,13,8\n";
    }

    private void createTestFileWithTrafficData(String filePath, String data) throws IOException {
        File file = new File(filePath);
        file.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(data);
        }

        assertTrue(file.exists(), "Test file should be created successfully: " + filePath);
    }

    private String buildHealthIndicatorTestPath(int year, Day day, Mode mode) {
        return testBaseDirectory + "scenOutput/" + TEST_SCENARIO_NAME + "/" + year + "/healthIndicators_" + day + "_" + mode + ".csv";
    }

    private String buildTrafficFlowTestPath(int year, Day day, String mode) {
        return testBaseDirectory + "scenOutput/" + TEST_SCENARIO_NAME + "/" + year + "/traffic_flows_" + day + "_" + mode + ".csv";
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
