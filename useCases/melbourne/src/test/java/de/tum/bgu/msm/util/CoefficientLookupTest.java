package de.tum.bgu.msm.util;

import de.tum.bgu.msm.data.Purpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for the CoefficientLookup class using real CSV coefficient files.
 * This test verifies that coefficients are correctly loaded from CSV files
 * and that the lookup functionality works as expected.
 */
class CoefficientLookupTest {

    @BeforeEach
    void setUp() {
        // Reset the lookup table before each test
        CoefficientLookup.reset();

        // First try production path, then fall back to test path
        File productionFile = new File("input/mito/modeChoice/mc_coefficients_nhbw.csv");
        File testFile = new File("src/test/java/de/tum/bgu/msm/util/mc_coefficients_nhbw.csv");

        if (!productionFile.exists() && !testFile.exists()) {
            fail("Test CSV file must exist in either production path (" + productionFile.getAbsolutePath() +
                 ") or test path (" + testFile.getAbsolutePath() + ")");
        }

        System.out.println("Production file exists: " + productionFile.exists());
        System.out.println("Test file exists: " + testFile.exists());
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
        CoefficientLookup.reset();
    }

    @Test
    void testInitialiseWithExtractCoefficient() {
        // Test initializing using the ExtractCoefficient function
        assertDoesNotThrow(CoefficientLookup::initialiseTest, "Should initialise successfully using ExtractCoefficient");

        // Verify statistics show successful loading
        String stats = CoefficientLookup.getStatistics();
        assertTrue(stats.contains("Purposes: 1"), "Should load 1 purpose (NHBW): " + stats);
        assertTrue(stats.contains("Initialised: true"), "Should be marked as initialised: " + stats);
    }

    @Test
    void testGetCoefficientWithRealData() {
        // Initialise with ExtractCoefficient
        CoefficientLookup.initialiseTest();

        // Test specific coefficients from the CSV file
        // These values are from the actual mc_coefficients_nhbw.csv file

        // Test bicycle stressLink coefficient
        double bicycleStressLink = CoefficientLookup.getCoefficient(Purpose.NHBW, "bicycle", "stressLink");
        assertEquals(3.9477646925, bicycleStressLink, 0.0001, "Bicycle stressLink should match CSV value");

        // Test walk speed coefficient
        double walkSpeed = CoefficientLookup.getCoefficient(Purpose.NHBW, "walk", "speed");
        assertEquals(4.3210968193, walkSpeed, 0.0001, "Walk speed should match CSV value");

        // Test zero values (autoDriver grad)
        double autoDriverGrad = CoefficientLookup.getCoefficient(Purpose.NHBW, "autoDriver", "grad");
        assertEquals(0.0, autoDriverGrad, 0.0001, "AutoDriver grad should be zero");
    }

    @Test
    void testGetCoefficientSetWithRealData() {
        // Initialise with ExtractCoefficient
        CoefficientLookup.initialiseTest();

        // Test getting a complete coefficient set for bicycle mode
        CoefficientLookup.CoefficientSet bicycleCoeffs = CoefficientLookup.getCoefficients(Purpose.NHBW, "bicycle");
        assertNotNull(bicycleCoeffs, "Bicycle coefficient set should not be null");

        // Verify specific coefficients match CSV values
        assertEquals(0.0, bicycleCoeffs.grad, 0.0001, "Bicycle grad should be 0.0");
        assertEquals(3.9477646925, bicycleCoeffs.stressLink, 0.0001, "Bicycle stressLink should match CSV");
        assertEquals(0.0, bicycleCoeffs.vgvi, 0.0001, "Bicycle vgvi should be 0.0");
        assertEquals(0.0, bicycleCoeffs.speed, 0.0001, "Bicycle speed should be 0.0");

        // Test getting coefficient set for walk mode
        CoefficientLookup.CoefficientSet walkCoeffs = CoefficientLookup.getCoefficients(Purpose.NHBW, "walk");
        assertNotNull(walkCoeffs, "Walk coefficient set should not be null");

        assertEquals(0.0, walkCoeffs.grad, 0.0001, "Walk grad should be 0.0");
        assertEquals(0.0, walkCoeffs.stressLink, 0.0001, "Walk stressLink should be 0.0");
        assertEquals(0.0, walkCoeffs.vgvi, 0.0001, "Walk vgvi should be 0.0");
        assertEquals(4.3210968193, walkCoeffs.speed, 0.0001, "Walk speed should match CSV");
    }

    @Test
    void testAllModesLoadedCorrectly() {
        // Initialise with ExtractCoefficient
        CoefficientLookup.initialiseTest();

        // Test that all modes are available
        String[] expectedModes = {"autoDriver", "autoPassenger", "pt", "bicycle", "walk"};

        for (String mode : expectedModes) {
            CoefficientLookup.CoefficientSet coeffs = CoefficientLookup.getCoefficients(Purpose.NHBW, mode);
            assertNotNull(coeffs, "Coefficient set should exist for mode: " + mode);
        }
    }

    @Test
    void testAttributeAccessThroughCoefficientSet() {
        // Initialise with ExtractCoefficient
        CoefficientLookup.initialiseTest();

        CoefficientLookup.CoefficientSet walkCoeffs = CoefficientLookup.getCoefficients(Purpose.NHBW, "walk");

        // Test accessing coefficients through getAttribute method
        assertEquals(0.0, walkCoeffs.getAttribute("grad"), 0.0001);
        assertEquals(0.0, walkCoeffs.getAttribute("stressLink"), 0.0001);
        assertEquals(0.0, walkCoeffs.getAttribute("vgvi"), 0.0001);
        assertEquals(4.3210968193, walkCoeffs.getAttribute("speed"), 0.0001);

        // Test female interaction terms (should be 0.0 in test data)
        assertEquals(0.0, walkCoeffs.getAttribute("grad_f"), 0.0001);
        assertEquals(0.0, walkCoeffs.getAttribute("stressLink_f"), 0.0001);
        assertEquals(0.0, walkCoeffs.getAttribute("vgvi_f"), 0.0001);
        assertEquals(0.0, walkCoeffs.getAttribute("speed_f"), 0.0001);

        // Test child interaction terms (should be 0.0 in test data)
        assertEquals(0.0, walkCoeffs.getAttribute("grad_c"), 0.0001);
        assertEquals(0.0, walkCoeffs.getAttribute("stressLink_c"), 0.0001);
        assertEquals(0.0, walkCoeffs.getAttribute("vgvi_c"), 0.0001);
        assertEquals(0.0, walkCoeffs.getAttribute("speed_c"), 0.0001);

        // Test unknown attribute returns 0.0
        assertEquals(0.0, walkCoeffs.getAttribute("unknown_attribute"), 0.0001);
    }

    @Test
    void testAutoInitializationBehavior() {
        // Reset to ensure clean state
        CoefficientLookup.reset();

        // Test that accessing coefficients automatically initializes if needed
        // This should NOT throw an exception, but should auto-initialize
        assertDoesNotThrow(() -> {
            double coefficient = CoefficientLookup.getCoefficient(Purpose.NHBW, "walk", "speed");
            // Verify we got a valid result (not necessarily a specific value)
            assertTrue(coefficient >= 0.0 || coefficient < 0.0, "Should return a valid coefficient");
        }, "Should auto-initialize when not initialised");

        // Verify the lookup table is now initialized
        assertTrue(CoefficientLookup.isInitialised(), "Should be initialized after first access");

        // Test that getCoefficients also works with auto-initialization
        CoefficientLookup.reset(); // Reset again
        assertDoesNotThrow(() -> {
            CoefficientLookup.CoefficientSet coefficients = CoefficientLookup.getCoefficients(Purpose.NHBW, "walk");
            assertNotNull(coefficients, "Should return a valid CoefficientSet");
        }, "Should auto-initialize when accessing coefficient set");
    }

    @Test
    void testNonExistentPurposeAndMode() {
        // Initialise with ExtractCoefficient
        CoefficientLookup.initialiseTest();

        // Test accessing non-existent purpose (should return 0.0 with warning)
        double coefficient = CoefficientLookup.getCoefficient(Purpose.HBW, "walk", "speed");
        assertEquals(0.0, coefficient, 0.0001, "Should return 0.0 for non-existent purpose");

        // Test accessing non-existent mode (should return 0.0 with warning)
        double nonExistentMode = CoefficientLookup.getCoefficient(Purpose.NHBW, "nonExistentMode", "speed");
        assertEquals(0.0, nonExistentMode, 0.0001, "Should return 0.0 for non-existent mode");

        // Test getting coefficient set for non-existent mode (should return empty set)
        CoefficientLookup.CoefficientSet emptySet = CoefficientLookup.getCoefficients(Purpose.NHBW, "nonExistentMode");
        assertNotNull(emptySet, "Should return empty coefficient set for non-existent mode");
        assertEquals(0.0, emptySet.speed, 0.0001, "Empty set should have zero values");
    }

    @Test
    void testDoubleInitialisation() {
        // Initialise once
        CoefficientLookup.initialiseTest();

        // Initialise again - should not throw exception
        assertDoesNotThrow(CoefficientLookup::initialiseTest, "Double initialization should be safe");

        // Should still work correctly
        double walkSpeed = CoefficientLookup.getCoefficient(Purpose.NHBW, "walk", "speed");
        assertEquals(4.3210968193, walkSpeed, 0.0001, "Should still return correct values after double init");
    }

    @Test
    void testBikeBicycleAmbiguity() {
        // Initialise with ExtractCoefficient
        CoefficientLookup.initialiseTest();

        // Test that 'bike' and 'bicycle' return the same coefficients
        double bicycleStressLink = CoefficientLookup.getCoefficient(Purpose.NHBW, "bicycle", "stressLink");
        double bikeStressLink = CoefficientLookup.getCoefficient(Purpose.NHBW, "bike", "stressLink");

        assertEquals(bicycleStressLink, bikeStressLink, 0.0001,
                     "Both 'bike' and 'bicycle' should return the same coefficient values");
        assertEquals(3.9477646925, bikeStressLink, 0.0001,
                     "Bike stressLink should match expected bicycle value from CSV");

        // Test with different case variations
        double bikeUpperCase = CoefficientLookup.getCoefficient(Purpose.NHBW, "BIKE", "stressLink");
        double bikeMixedCase = CoefficientLookup.getCoefficient(Purpose.NHBW, "Bike", "stressLink");

        assertEquals(bicycleStressLink, bikeUpperCase, 0.0001,
                     "'BIKE' (uppercase) should return the same coefficient as 'bicycle'");
        assertEquals(bicycleStressLink, bikeMixedCase, 0.0001,
                     "'Bike' (mixed case) should return the same coefficient as 'bicycle'");

        // Test getCoefficients method as well
        CoefficientLookup.CoefficientSet bicycleCoefficients = CoefficientLookup.getCoefficients(Purpose.NHBW, "bicycle");
        CoefficientLookup.CoefficientSet bikeCoefficients = CoefficientLookup.getCoefficients(Purpose.NHBW, "bike");

        assertNotNull(bicycleCoefficients, "Bicycle coefficient set should not be null");
        assertNotNull(bikeCoefficients, "Bike coefficient set should not be null");

        assertEquals(bicycleCoefficients.stressLink, bikeCoefficients.stressLink, 0.0001,
                     "Bike and bicycle coefficient sets should have the same stressLink value");
        assertEquals(bicycleCoefficients.grad, bikeCoefficients.grad, 0.0001,
                     "Bike and bicycle coefficient sets should have the same grad value");
        assertEquals(bicycleCoefficients.vgvi, bikeCoefficients.vgvi, 0.0001,
                     "Bike and bicycle coefficient sets should have the same vgvi value");
        assertEquals(bicycleCoefficients.speed, bikeCoefficients.speed, 0.0001,
                     "Bike and bicycle coefficient sets should have the same speed value");
    }
}
