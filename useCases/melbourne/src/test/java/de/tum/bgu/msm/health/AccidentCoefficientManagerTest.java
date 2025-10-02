package de.tum.bgu.msm.health;

import de.tum.bgu.msm.health.accidentModel.AccidentCoefficientManager;
import de.tum.bgu.msm.health.testutils.AccidentCoefficientTestDataGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AccidentCoefficientManagerTest {

    private AccidentCoefficientManager coefficientManager;
    private String testBasePath;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        testBasePath = temporaryDirectory.toString() + java.io.File.separator;
        coefficientManager = new AccidentCoefficientManager();
    }

    @Test
    void shouldLoadBinaryLogitCoefficients() throws IOException {
        AccidentCoefficientTestDataGenerator.createMinimalBinaryModelFile(testBasePath);

        coefficientManager.loadBinaryLogitCoefficients(testBasePath + "binaryModel.csv");

        assertEquals(2.5, coefficientManager.getBinaryLogitCoefficient("intercept"));
        assertEquals(-0.8, coefficientManager.getBinaryLogitCoefficient("bike_demand"));
        assertEquals(1.2, coefficientManager.getBinaryLogitCoefficient("length"));
    }

    @Test
    void shouldLoadPoissonCoefficients() throws IOException {
        AccidentCoefficientTestDataGenerator.createMinimalPoissonModelFile(testBasePath);

        coefficientManager.loadPoissonCoefficients(testBasePath + "poissonModel.csv");

        assertEquals(1.5, coefficientManager.getPoissonCoefficient("intercept"));
        assertEquals(0.3, coefficientManager.getPoissonCoefficient("car_demand"));
        assertEquals(-0.1, coefficientManager.getPoissonCoefficient("speed_limit"));
    }

    @Test
    void shouldLoadTimeOfDayCoefficients() throws IOException {
        AccidentCoefficientTestDataGenerator.createMinimalTimeOfDayFile(testBasePath);

        coefficientManager.loadTimeOfDayCoefficients(testBasePath + "timeOfDay.csv");

        assertEquals(0.8, coefficientManager.getTimeOfDayCoefficient(8));
        assertEquals(1.2, coefficientManager.getTimeOfDayCoefficient(17));
        assertEquals(0.5, coefficientManager.getTimeOfDayCoefficient(23));
    }

    @Test
    void shouldReturnZeroForMissingBinaryLogitCoefficient() throws IOException {
        AccidentCoefficientTestDataGenerator.createMinimalBinaryModelFile(testBasePath);
        coefficientManager.loadBinaryLogitCoefficients(testBasePath + "binaryModel.csv");

        assertEquals(0.0, coefficientManager.getBinaryLogitCoefficient("non_existent"));
    }

    @Test
    void shouldReturnZeroForMissingPoissonCoefficient() throws IOException {
        AccidentCoefficientTestDataGenerator.createMinimalPoissonModelFile(testBasePath);
        coefficientManager.loadPoissonCoefficients(testBasePath + "poissonModel.csv");

        assertEquals(0.0, coefficientManager.getPoissonCoefficient("non_existent"));
    }

    @Test
    void shouldReturnOneForMissingTimeOfDayCoefficient() throws IOException {
        AccidentCoefficientTestDataGenerator.createMinimalTimeOfDayFile(testBasePath);
        coefficientManager.loadTimeOfDayCoefficients(testBasePath + "timeOfDay.csv");

        assertEquals(1.0, coefficientManager.getTimeOfDayCoefficient(25));
    }

    @Test
    void shouldHandleEmptyBinaryModelFile() throws IOException {
        AccidentCoefficientTestDataGenerator.createEmptyFile(testBasePath, "binaryModel.csv");

        assertDoesNotThrow(() -> coefficientManager.loadBinaryLogitCoefficients(testBasePath + "binaryModel.csv"));
        assertEquals(0.0, coefficientManager.getBinaryLogitCoefficient("any_key"));
    }

    @Test
    void shouldThrowExceptionForMissingBinaryModelFile() {
        assertThrows(IOException.class, () ->
            coefficientManager.loadBinaryLogitCoefficients(testBasePath + "missing.csv"));
    }
}
