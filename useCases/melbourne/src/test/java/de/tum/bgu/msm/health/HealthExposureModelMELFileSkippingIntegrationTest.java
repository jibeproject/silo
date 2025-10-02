package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.data.Day;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for HealthExposureModelMEL file skipping functionality.
 * Tests the integration between file manager and the main workflow methods.
 * These tests should initially fail until the workflow integration is implemented.
 */
class HealthExposureModelMELFileSkippingIntegrationTest {

    private HealthOutputFileManager fileManager;
    private String testBaseDirectory;
    private static final String TEST_SCENARIO_NAME = "test_scenario";
    private static final int TEST_YEAR = 2018;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        testBaseDirectory = temporaryDirectory.toString() + File.separator;
        fileManager = new HealthOutputFileManager(testBaseDirectory, TEST_SCENARIO_NAME);
    }

    @Test
    void testHealthDataAssemblerSkipsProcessingWhenFileExists() throws IOException {
        // Given: Health indicator file already exists
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.walk);

        // When: healthDataAssembler is called
        // This test verifies the contract - the actual implementation will make this pass
        boolean shouldSkip = fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.thursday, Mode.walk);

        // Then: Processing should be skipped
        assertTrue(shouldSkip, "healthDataAssembler should skip processing when file exists");

        // Verify that the actual processing logic would not be called
        // (This will be implemented in the actual healthDataAssembler method)
    }

    @Test
    void testWriteTrafficFlowsSkipsProcessingWhenFileExists() throws IOException {
        // Given: Traffic flow file already exists
        createTrafficFlowFile(TEST_YEAR, Day.saturday, "bicycle");

        // When: writeTrafficFlowsToCSV logic is evaluated
        boolean shouldSkip = fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.saturday, "bicycle");

        // Then: Processing should be skipped
        assertTrue(shouldSkip, "writeTrafficFlowsToCSV should skip processing when file exists");
    }

    @Test
    void testDetermineModesToProcessFiltersExistingFiles() throws IOException {
        // Given: Some traffic flow files exist, others don't
        createTrafficFlowFile(TEST_YEAR, Day.thursday, "walk");
        createTrafficFlowFile(TEST_YEAR, Day.thursday, "bike");
        // Note: "car" file does not exist

        // When: Determining which modes to process
        List<String> allModes = Arrays.asList("car", "walk", "bike");
        List<String> modesToProcess = filterModesForProcessing(allModes, TEST_YEAR, Day.thursday);

        // Then: Only modes without existing files should be processed
        assertEquals(1, modesToProcess.size(), "Should only process modes without existing files");
        assertTrue(modesToProcess.contains("car"), "Should process car mode (file does not exist)");
        assertFalse(modesToProcess.contains("walk"), "Should not process walk mode (file exists)");
        assertFalse(modesToProcess.contains("bike"), "Should not process bike mode (file exists)");
    }

    @Test
    void testEndYearProcessingSkipsCompletedDayModeCominations() throws IOException {
        // Given: Mixed scenario - some files exist, others don't
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.walk);
        createHealthIndicatorFile(TEST_YEAR, Day.saturday, Mode.bicycle);
        createTrafficFlowFile(TEST_YEAR, Day.thursday, "walk");

        // When: Processing all day/mode combinations
        Day[] days = {Day.thursday, Day.saturday, Day.sunday};
        Mode[] modes = {Mode.walk, Mode.bicycle, Mode.autoDriver};

        int skippedHealthProcessing = 0;
        int skippedTrafficProcessing = 0;

        for (Day day : days) {
            for (Mode mode : modes) {
                if (fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, day, mode)) {
                    skippedHealthProcessing++;
                }
            }

            for (String modeStr : Arrays.asList("walk", "bicycle", "autoDriver")) {
                if (fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, day, modeStr)) {
                    skippedTrafficProcessing++;
                }
            }
        }

        // Then: Verify correct number of skips
        assertEquals(2, skippedHealthProcessing, "Should skip 2 health indicator processing (thursday/walk, saturday/bicycle)");
        assertEquals(1, skippedTrafficProcessing, "Should skip 1 traffic flow processing (thursday/walk)");
    }

    @Test
    void testSelectiveProcessingAfterFileDelection() throws IOException {
        // Given: All files exist initially
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.walk);
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.bicycle);
        createTrafficFlowFile(TEST_YEAR, Day.thursday, "walk");
        createTrafficFlowFile(TEST_YEAR, Day.thursday, "bicycle");

        // Verify all would be skipped
        assertTrue(fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.thursday, Mode.walk));
        assertTrue(fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.thursday, Mode.bicycle));
        assertTrue(fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, "walk"));
        assertTrue(fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, "bicycle"));

        // When: Strategically delete one file (as per your testing strategy)
        deleteTrafficFlowFile(TEST_YEAR, Day.thursday, "walk");

        // Then: Only the deleted file should be processed
        assertFalse(fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, "walk"),
                   "Should process walk traffic after file deletion");
        assertTrue(fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, "bicycle"),
                  "Should still skip bicycle traffic (file exists)");
        assertTrue(fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.thursday, Mode.walk),
                  "Should still skip walk health processing (file exists)");
        assertTrue(fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.thursday, Mode.bicycle),
                  "Should still skip bicycle health processing (file exists)");
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

        // Create all traffic flow files
        for (Day day : testDays) {
            for (String mode : testTrafficModes) {
                createTrafficFlowFile(TEST_YEAR, day, mode);
            }
        }

        // When: Checking if any processing is needed
        boolean anyHealthProcessingNeeded = false;
        boolean anyTrafficProcessingNeeded = false;

        for (Day day : testDays) {
            for (Mode mode : testModes) {
                if (!fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, day, mode)) {
                    anyHealthProcessingNeeded = true;
                    break;
                }
            }
            for (String mode : testTrafficModes) {
                if (!fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, day, mode)) {
                    anyTrafficProcessingNeeded = true;
                    break;
                }
            }
        }

        // Then: No processing should be needed
        assertFalse(anyHealthProcessingNeeded, "Should skip all health processing when all files exist");
        assertFalse(anyTrafficProcessingNeeded, "Should skip all traffic processing when all files exist");
    }

    @Test
    void testLoggingBehaviourForSkippedFiles() throws IOException {
        // Given: Files exist
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.walk);
        createTrafficFlowFile(TEST_YEAR, Day.saturday, "bicycle");

        // When: Checking files (this will test logging in the actual implementation)
        fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.thursday, Mode.walk);
        fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.saturday, "bicycle");

        // Then: Appropriate log messages should be generated
        // (Logging verification will be implemented with the actual class)
        // For now, we verify the basic functionality works
        assertTrue(true, "Logging test placeholder - will verify log messages in implementation");
    }

    // Helper methods

    private List<String> filterModesForProcessing(List<String> allModes, int year, Day day) {
        // This simulates the determineModesToProcess method that will be implemented
        return allModes.stream()
            .filter(mode -> !fileManager.shouldSkipTrafficFlowProcessing(year, day, mode))
            .collect(java.util.stream.Collectors.toList());
    }

    private void createHealthIndicatorFile(int year, Day day, Mode mode) throws IOException {
        String filePath = buildHealthIndicatorTestPath(year, day, mode);
        createTestFile(filePath);
    }

    private void createTrafficFlowFile(int year, Day day, String mode) throws IOException {
        String filePath = buildTrafficFlowTestPath(year, day, mode);
        createTestFile(filePath);
    }

    private void deleteTrafficFlowFile(int year, Day day, String mode) {
        String filePath = buildTrafficFlowTestPath(year, day, mode);
        File file = new File(filePath);
        if (file.exists()) {
            file.delete();
        }
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
        file.createNewFile();
        assertTrue(file.exists(), "Test file should be created: " + filePath);
    }
}
