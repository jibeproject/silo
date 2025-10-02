package de.tum.bgu.msm.health;

import de.tum.bgu.msm.health.accidentModel.AccidentCoefficientManager;
import de.tum.bgu.msm.health.accidentModel.LinkDemandCalculator;
import de.tum.bgu.msm.health.testutils.AccidentCoefficientTestDataGenerator;
import de.tum.bgu.msm.health.testutils.NetworkTestDataGenerator;
import de.tum.bgu.msm.health.testutils.TestPropertiesGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.network.Network;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AccidentModelPlaceholderValidationTest {

    private String testBasePath;
    private AccidentCoefficientManager coefficientManager;
    private LinkDemandCalculator demandCalculator;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() throws IOException {
        testBasePath = temporaryDirectory.toString() + java.io.File.separator;
        AccidentCoefficientTestDataGenerator.createAllStandardFiles(testBasePath);

        coefficientManager = new AccidentCoefficientManager();
        demandCalculator = new LinkDemandCalculator();
    }

    @Test
    void shouldValidateAccidentCoefficientManagerPlaceholder() throws IOException {
        coefficientManager.loadBinaryLogitCoefficients(testBasePath + "binaryModel.csv");

        assertNotEquals(0.0, coefficientManager.getBinaryLogitCoefficient("intercept"));
        assertEquals(0.0, coefficientManager.getBinaryLogitCoefficient("non_existent"));
    }

    @Test
    void shouldValidateLinkDemandCalculatorPlaceholder() {
        Network testNetwork = NetworkTestDataGenerator.createSimpleTestNetwork(5);

        testNetwork.getLinks().values().forEach(link -> {
            double bikeDemand = demandCalculator.calculateBikeDemand(link);
            double carDemand = demandCalculator.calculateCarDemand(link);
            boolean hasWalkDemand = demandCalculator.hasWalkDemand(link);

            assertTrue(bikeDemand >= 0.0);
            assertTrue(carDemand >= 0.0);
            assertTrue(hasWalkDemand);
        });
    }

    @Test
    void shouldValidateTestUtilityClassesAreReusable() throws IOException {
        AccidentCoefficientTestDataGenerator.createAllMinimalFiles(testBasePath);

        Network smallNetwork = NetworkTestDataGenerator.createSimpleTestNetwork(3);
        Network largeNetwork = NetworkTestDataGenerator.createBenchmarkNetwork(100);

        Properties standardProps = TestPropertiesGenerator.createStandardAccidentModelProperties(testBasePath);
        Properties minimalProps = TestPropertiesGenerator.createMinimalProperties(testBasePath);

        assertNotNull(smallNetwork);
        assertNotNull(largeNetwork);
        assertNotNull(standardProps);
        assertNotNull(minimalProps);

        assertEquals(3, smallNetwork.getLinks().size());
        assertEquals(100, largeNetwork.getLinks().size());
        assertTrue(standardProps.containsKey("accident.model.parallel.processing"));
        assertTrue(minimalProps.containsKey("accident.coefficient.path"));
    }

    @Test
    void shouldValidateUtilityClassesAreInCorrectLocation() {
        String expectedPackage = "de.tum.bgu.msm.health.testutils";

        assertEquals(expectedPackage, AccidentCoefficientTestDataGenerator.class.getPackage().getName());
        assertEquals(expectedPackage, NetworkTestDataGenerator.class.getPackage().getName());
        assertEquals(expectedPackage, TestPropertiesGenerator.class.getPackage().getName());
    }

    @Test
    void shouldValidateNetworkGeneratorCreatesValidStructures() {
        Network simpleNetwork = NetworkTestDataGenerator.createSimpleTestNetwork(10);
        Network benchmarkNetwork = NetworkTestDataGenerator.createBenchmarkNetwork(50);
        Network mixedDemandNetwork = NetworkTestDataGenerator.createNetworkWithMixedDemand(20);

        assertEquals(10, simpleNetwork.getLinks().size());
        assertEquals(50, benchmarkNetwork.getLinks().size());
        assertEquals(20, mixedDemandNetwork.getLinks().size());

        simpleNetwork.getLinks().values().forEach(link -> {
            assertTrue(link.getLength() > 0);
            assertNotNull(link.getAttributes());
        });
    }

    @Test
    void shouldValidatePropertiesGeneratorCreatesValidConfigurations() {
        Properties standard = TestPropertiesGenerator.createStandardAccidentModelProperties(testBasePath);
        Properties performance = TestPropertiesGenerator.createPerformanceOptimisedProperties(testBasePath);
        Properties sequential = TestPropertiesGenerator.createSequentialProcessingProperties(testBasePath);

        assertEquals("true", standard.getProperty("accident.model.parallel.processing"));
        assertEquals("true", performance.getProperty("accident.model.early.exit.enabled"));
        assertEquals("false", sequential.getProperty("accident.model.parallel.processing"));

        assertTrue(standard.containsKey("accident.coefficient.path"));
        assertTrue(performance.containsKey("accident.model.thread.pool.size"));
        assertTrue(sequential.containsKey("accident.model.scale.factor"));
    }
}
