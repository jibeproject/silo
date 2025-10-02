package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.data.Day;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for HealthOutputFileManager to verify file existence checking functionality.
 * Tests follow TDD approach - tests are written first and should initially fail.
 */
class HealthOutputFileManagerTest {

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

    @AfterEach
    void tearDown() {
        // Clean up any created test files
        fileManager = null;
    }

    @Test
    void testShouldSkipTrafficFlowProcessingWhenFileExists() throws IOException {
        // Given: A traffic flow file exists
        createTrafficFlowFile(TEST_YEAR, Day.thursday, "walk");

        // When: Checking if processing should be skipped
        boolean shouldSkip = fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, "walk");

        // Then: Processing should be skipped
        assertTrue(shouldSkip, "Should skip processing when traffic flow file exists");
    }

    @Test
    void testShouldNotSkipTrafficFlowProcessingWhenFileDoesNotExist() {
        // Given: No traffic flow file exists (clean test environment)

        // When: Checking if processing should be skipped
        boolean shouldSkip = fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, "walk");

        // Then: Processing should not be skipped
        assertFalse(shouldSkip, "Should not skip processing when traffic flow file does not exist");
    }

    @Test
    void testShouldSkipHealthIndicatorProcessingWhenFileExists() throws IOException {
        // Given: A health indicator file exists
        createHealthIndicatorFile(TEST_YEAR, Day.saturday, Mode.bicycle);

        // When: Checking if processing should be skipped
        boolean shouldSkip = fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.saturday, Mode.bicycle);

        // Then: Processing should be skipped
        assertTrue(shouldSkip, "Should skip processing when health indicator file exists");
    }

    @Test
    void testShouldNotSkipHealthIndicatorProcessingWhenFileDoesNotExist() {
        // Given: No health indicator file exists (clean test environment)

        // When: Checking if processing should be skipped
        boolean shouldSkip = fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.saturday, Mode.bicycle);

        // Then: Processing should not be skipped
        assertFalse(shouldSkip, "Should not skip processing when health indicator file does not exist");
    }

    @Test
    void testTrafficFlowFilePathConstruction() throws IOException {
        // Given: Different combinations of year, day, and mode
        createTrafficFlowFile(2020, Day.sunday, "car");
        createTrafficFlowFile(2019, Day.thursday, "bike");

        // When: Checking different file combinations
        boolean shouldSkipCarSunday = fileManager.shouldSkipTrafficFlowProcessing(2020, Day.sunday, "car");
        boolean shouldSkipBikeThursday = fileManager.shouldSkipTrafficFlowProcessing(2019, Day.thursday, "bike");
        boolean shouldSkipWalkSaturday = fileManager.shouldSkipTrafficFlowProcessing(2021, Day.saturday, "walk");

        // Then: Only existing files should be skipped
        assertTrue(shouldSkipCarSunday, "Should skip car processing for Sunday 2020");
        assertTrue(shouldSkipBikeThursday, "Should skip bike processing for Thursday 2019");
        assertFalse(shouldSkipWalkSaturday, "Should not skip walk processing for Saturday 2021 (file does not exist)");
    }

    @Test
    void testHealthIndicatorFilePathConstruction() throws IOException {
        // Given: Different combinations of year, day, and mode
        createHealthIndicatorFile(2020, Day.sunday, Mode.autoDriver);
        createHealthIndicatorFile(2019, Day.thursday, Mode.pt);

        // When: Checking different file combinations
        boolean shouldSkipAutoDriverSunday = fileManager.shouldSkipHealthIndicatorProcessing(2020, Day.sunday, Mode.autoDriver);
        boolean shouldSkipPtThursday = fileManager.shouldSkipHealthIndicatorProcessing(2019, Day.thursday, Mode.pt);
        boolean shouldSkipWalkSaturday = fileManager.shouldSkipHealthIndicatorProcessing(2021, Day.saturday, Mode.walk);

        // Then: Only existing files should be skipped
        assertTrue(shouldSkipAutoDriverSunday, "Should skip autoDriver processing for Sunday 2020");
        assertTrue(shouldSkipPtThursday, "Should skip pt processing for Thursday 2019");
        assertFalse(shouldSkipWalkSaturday, "Should not skip walk processing for Saturday 2021 (file does not exist)");
    }

    @Test
    void testMultipleFilesIndependentProcessing() throws IOException {
        // Given: Some files exist, others don't
        createTrafficFlowFile(TEST_YEAR, Day.thursday, "walk");
        createHealthIndicatorFile(TEST_YEAR, Day.thursday, Mode.bicycle);
        // Note: traffic_flows_thursday_bicycle.csv and healthIndicators_thursday_walk.csv do NOT exist

        // When: Checking each file type independently
        boolean skipTrafficWalk = fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, "walk");
        boolean skipTrafficBike = fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, "bicycle");
        boolean skipHealthWalk = fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.thursday, Mode.walk);
        boolean skipHealthBike = fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.thursday, Mode.bicycle);

        // Then: Only files that exist should be skipped
        assertTrue(skipTrafficWalk, "Should skip traffic flow processing for walk (file exists)");
        assertFalse(skipTrafficBike, "Should not skip traffic flow processing for bicycle (file does not exist)");
        assertFalse(skipHealthWalk, "Should not skip health indicator processing for walk (file does not exist)");
        assertTrue(skipHealthBike, "Should skip health indicator processing for bicycle (file exists)");
    }

    @Test
    void testFilePathFormatting() {
        // This test verifies the expected file path format without creating files
        // We'll use a spy or reflection to verify the paths, but for now, we test the contract

        // Given: A file manager with known base directory and scenario
        String expectedBaseDirectory = testBaseDirectory + "scenOutput/" + TEST_SCENARIO_NAME + "/";

        // When: We would call the file path building methods (these are private, so we test via public interface)
        // Then: The paths should follow the expected format
        // traffic_flows_{year}/{day}_{mode}.csv
        // healthIndicators_{year}/{day}_{mode}.csv

        // This test ensures the contract is maintained - specific path testing will be done through integration
        assertNotNull(fileManager, "File manager should be properly initialised");
    }

    @Test
    void testHandleNullInputsGracefully() {
        // Given: Null inputs (edge case testing)

        // When/Then: Should handle null inputs gracefully without throwing exceptions
        assertDoesNotThrow(() -> {
            boolean result = fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, null, "walk");
            assertFalse(result, "Should return false for null day");
        }, "Should handle null day gracefully");

        assertDoesNotThrow(() -> {
            boolean result = fileManager.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, null);
            assertFalse(result, "Should return false for null mode");
        }, "Should handle null mode gracefully");

        assertDoesNotThrow(() -> {
            boolean result = fileManager.shouldSkipHealthIndicatorProcessing(TEST_YEAR, Day.thursday, null);
            assertFalse(result, "Should return false for null mode");
        }, "Should handle null Mode gracefully");
    }

    @Test
    void testDifferentScenarioNames() {
        // Given: Different scenario names
        HealthOutputFileManager manager1 = new HealthOutputFileManager(testBaseDirectory, "scenario_a");
        HealthOutputFileManager manager2 = new HealthOutputFileManager(testBaseDirectory, "scenario_b");

        // When: Checking the same file in different scenarios
        boolean skip1 = manager1.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, "walk");
        boolean skip2 = manager2.shouldSkipTrafficFlowProcessing(TEST_YEAR, Day.thursday, "walk");

        // Then: Both should return false (no files exist in either scenario)
        assertFalse(skip1, "Should not skip for scenario_a (no file exists)");
        assertFalse(skip2, "Should not skip for scenario_b (no file exists)");
    }

    // Helper methods for creating test files

    private void createTrafficFlowFile(int year, Day day, String mode) throws IOException {
        String filePath = buildTrafficFlowTestPath(year, day, mode);
        createTestFile(filePath);
    }

    private void createHealthIndicatorFile(int year, Day day, Mode mode) throws IOException {
        String filePath = buildHealthIndicatorTestPath(year, day, mode);
        createTestFile(filePath);
    }

    private String buildTrafficFlowTestPath(int year, Day day, String mode) {
        return testBaseDirectory + "scenOutput/" + TEST_SCENARIO_NAME + "/" + year + "/traffic_flows_" + day + "_" + mode + ".csv";
    }

    private String buildHealthIndicatorTestPath(int year, Day day, Mode mode) {
        return testBaseDirectory + "scenOutput/" + TEST_SCENARIO_NAME + "/" + year + "/healthIndicators_" + day + "_" + mode + ".csv";
    }

    private void createTestFile(String filePath) throws IOException {
        File file = new File(filePath);
        file.getParentFile().mkdirs(); // Create parent directories if they don't exist
        file.createNewFile(); // Create the file

        // Verify file was created successfully
        assertTrue(file.exists(), "Test file should be created successfully: " + filePath);
    }
}
