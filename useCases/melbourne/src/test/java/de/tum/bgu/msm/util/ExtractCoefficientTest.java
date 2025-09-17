package de.tum.bgu.msm.util;

import de.tum.bgu.msm.data.Purpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for the ExtractCoefficient class.
 * This test uses the CSV file in the test directory.
 */
class ExtractCoefficientTest {

    private static final String CSV_FILENAME = "mc_coefficients_nhbw.csv";
    private Path csvPath;

    @BeforeEach
    void setUp() throws IOException {
        // Look for the CSV file in the same directory as the test class
        File testClassDir = new File("src/test/java/de/tum/bgu/msm/util");
        File csvFile = new File(testClassDir, CSV_FILENAME);

        if (!csvFile.exists()) {
            fail("Test CSV file not found at: " + csvFile.getAbsolutePath());
        }

        csvPath = csvFile.toPath();
        System.out.println("Using CSV file: " + csvPath.toAbsolutePath());

        // Enable test mode in ExtractCoefficient and clear any existing cache
        ExtractCoefficient.setTestMode(true);
        ExtractCoefficient.clearCache();
    }

    @AfterEach
    void tearDown() {
        // Disable test mode after tests
        ExtractCoefficient.setTestMode(false);
    }

    @Test
    void testExtractCoefficient() {
        // Test extracting specific coefficients and compare with expected values from the data
        // autoPassenger ASC value
        double asc = ExtractCoefficient.extractCoefficient(Purpose.NHBW, "autoPassenger", "asc");
        System.out.println("Extracted asc for autoPassenger: " + asc);
        assertEquals(-2.8202621784, asc, 0.0001, "ASC value for autoPassenger should be -2.8202621784");

        // walk cost value
        double cost = ExtractCoefficient.extractCoefficient(Purpose.NHBW, "walk", "cost");
        System.out.println("Extracted cost for walk: " + cost);
        assertEquals(-0.0563746988, cost, 0.0001, "Cost value for walk should be -0.0563746988");

        // bicycle stressLink value
        double stressLink = ExtractCoefficient.extractCoefficient(Purpose.NHBW, "bicycle", "stressLink");
        System.out.println("Extracted stressLink for bicycle: " + stressLink);
        assertEquals(3.9477646925, stressLink, 0.0001, "StressLink value for bicycle should be 3.9477646925");

        // walk speed value
        double speed = ExtractCoefficient.extractCoefficient(Purpose.NHBW, "walk", "speed");
        System.out.println("Extracted speed for walk: " + speed);
        assertEquals(4.3210968193, speed, 0.0001, "Speed value for walk should be 4.3210968193");

        // pt time value
        double time = ExtractCoefficient.extractCoefficient(Purpose.NHBW, "pt", "age_16_24");
        System.out.println("Extracted age_16_24 for pt: " + time);
        assertEquals(0.5287058034, time, 0.0001, "age_16_24 value for pt should be 0.5287058034");

        // female value for autoPassenger
        double female = ExtractCoefficient.extractCoefficient(Purpose.NHBW, "autoPassenger", "female");
        System.out.println("Extracted female for autoPassenger: " + female);
        assertEquals(0.4090684171, female, 0.0001, "Female value for autoPassenger should be 0.4090684171");

        // Test non-existent row - should return 0
        double nonExistentRow = ExtractCoefficient.extractCoefficient(Purpose.NHBW, "autoDriver", "nonExistent");
        assertEquals(0.0, nonExistentRow, "Should return 0 for non-existent row");

        // Test non-existent column - should return 0
        double nonExistentColumn = ExtractCoefficient.extractCoefficient(Purpose.NHBW, "nonExistent", "cost");
        assertEquals(0.0, nonExistentColumn, "Should return 0 for non-existent column");
    }

    @Test
    void testNullInputs() {
        // Test with null inputs
        assertEquals(0.0, ExtractCoefficient.extractCoefficient(null, "autoDriver", "asc"),
                "Should return 0 for null purpose");
        assertEquals(0.0, ExtractCoefficient.extractCoefficient(Purpose.NHBW, null, "asc"),
                "Should return 0 for null mode");
        assertEquals(0.0, ExtractCoefficient.extractCoefficient(Purpose.NHBW, "autoDriver", null),
                "Should return 0 for null variable");
    }
}
