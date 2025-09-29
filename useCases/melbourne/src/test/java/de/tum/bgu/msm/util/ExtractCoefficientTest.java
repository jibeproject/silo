package de.tum.bgu.msm.util;

import de.tum.bgu.msm.data.Purpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static de.tum.bgu.msm.util.ExtractCoefficient.extractCoefficient;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for the ExtractCoefficient class.
 * This test uses the CSV file in the test directory and a helper method to avoid Mockito complications.
 */
class ExtractCoefficientTest {
    private Purpose purpose;

    @BeforeEach
    void setUp() {
        purpose = Purpose.NHBW;
    }

    @Test
    void testExtractCoefficient() {
        // Test extracting specific coefficients and compare with expected values from the existing CSV

        // autoPassenger ASC value
        Double asc = extractCoefficient(purpose, "autoPassenger", "asc");
        assertNotNull(asc, "ASC value should not be null");
        assertEquals(-2.8202621784, asc, 0.0001, "ASC value for autoPassenger should be -2.8202621784");

        // walk cost value
        Double cost = extractCoefficient(purpose, "walk", "cost");
        assertNotNull(cost, "Cost value should not be null");
        assertEquals(-0.0563746988, cost, 0.0001, "Cost value for walk should be -0.0563746988");

        // bicycle stressLink value
        Double stressLink = extractCoefficient(purpose, "bicycle", "stressLink");
        assertNotNull(stressLink, "StressLink value should not be null");
        assertEquals(3.9477646925, stressLink, 0.0001, "StressLink value for bicycle should be 3.9477646925");

        // walk speed value
        Double speed = extractCoefficient(purpose, "walk", "speed");
        assertNotNull(speed, "Speed value should not be null");
        assertEquals(4.3210968193, speed, 0.0001, "Speed value for walk should be 4.3210968193");

        // pt age_16_24 value
        Double age1624 = extractCoefficient(purpose, "pt", "age_16_24");
        assertNotNull(age1624, "age_16_24 value should not be null");
        assertEquals(0.5287058034, age1624, 0.0001, "age_16_24 value for pt should be 0.5287058034");

        // female value for autoPassenger
        Double female = extractCoefficient(purpose, "autoPassenger", "female");
        assertNotNull(female, "Female value should not be null");
        assertEquals(0.4090684171, female, 0.0001, "Female value for autoPassenger should be 0.4090684171");

        // autoDriver cost value
        Double autoDriverCost = extractCoefficient(purpose, "autoDriver", "cost");
        assertNotNull(autoDriverCost, "AutoDriver cost value should not be null");
        assertEquals(-0.216096594, autoDriverCost, 0.0001, "Cost value for autoDriver should be -0.216096594");

        // Test some zero values
        Double autoDriverAsc = extractCoefficient(purpose, "autoDriver", "asc");
        assertNotNull(autoDriverAsc, "AutoDriver ASC value should not be null");
        assertEquals(0.0, autoDriverAsc, 0.0001, "ASC value for autoDriver should be 0.0");

        // Test bicycle ASC value
        Double bicycleAsc = extractCoefficient(purpose, "bicycle", "asc");
        assertNotNull(bicycleAsc, "Bicycle ASC value should not be null");
        assertEquals(-1.6979856258, bicycleAsc, 0.0001, "ASC value for bicycle should be -1.6979856258");

        // Test walk ASC value
        Double walkAsc = extractCoefficient(purpose, "walk", "asc");
        assertNotNull(walkAsc, "Walk ASC value should not be null");
        assertEquals(3.3903457217, walkAsc, 0.0001, "ASC value for walk should be 3.3903457217");

        System.out.println("✅ All coefficient extraction tests passed!");
    }

    @Test
    void testNonExistentValues() {
        // Test non-existent row - should return null
        Double nonExistentRow = extractCoefficient(purpose, "autoDriver", "nonExistentAttribute");
        assertNull(nonExistentRow, "Should return null for non-existent row");

        // Test non-existent column - should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> extractCoefficient(purpose, "nonExistentMode", "cost"), "Should throw IllegalArgumentException for non-existent column");

        System.out.println("✅ Non-existent values tests passed!");
    }

    @Test
    void testNullInputs() {
        // Test with null inputs - should return null
        assertNull(extractCoefficient(null, "autoDriver", "asc"),
                "Should return null for null file path");
        assertNull(extractCoefficient(purpose, null, "asc"),
                "Should return null for null column");
        assertNull(extractCoefficient(purpose, "autoDriver", null),
                "Should return null for null row");

        System.out.println("✅ Null inputs tests passed!");
    }

    @Test
    void testInvalidFilePath() {
        // Should return null when file doesn't exist
        Double result = extractCoefficient(Purpose.AIRPORT, "autoDriver", "asc");
        assertNull(result, "Should return null when CSV file doesn't exist");

        System.out.println("✅ Invalid file path test passed!");
    }

    @Test
    void testNullPurposeFailsGracefully() {
        assertDoesNotThrow(() -> {
            Double result = extractCoefficient(null, "autoDriver", "asc");
            assertNull(result, "Should return null for null purpose");
        }, "ExtractCoefficient should handle null inputs gracefully");

        assertDoesNotThrow(() -> {
            Double result = extractCoefficient(Purpose.NHBW, null, "asc");
            assertNull(result, "Should return null for null column");
        }, "ExtractCoefficient should handle null inputs gracefully");

        assertDoesNotThrow(() -> {
            Double result = extractCoefficient(Purpose.NHBW, "autoDriver", null);
            assertNull(result, "Should return null for null row");
        }, "ExtractCoefficient should handle null inputs gracefully");

        System.out.println("✅ Original method null handling tests passed!");
    }
}
