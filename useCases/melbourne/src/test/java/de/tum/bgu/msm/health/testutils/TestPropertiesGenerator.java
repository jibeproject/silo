package de.tum.bgu.msm.health.testutils;

import java.util.Properties;

/**
 * Utility class for generating test configuration properties for various testing scenarios.
 * Provides reusable methods to create Properties objects with different configurations.
 */
public class TestPropertiesGenerator {

    /**
     * Creates standard properties for accident model testing.
     */
    public static Properties createStandardAccidentModelProperties(String basePath) {
        Properties properties = new Properties();
        properties.setProperty("accident.model.batch.size", "1000");
        properties.setProperty("accident.model.parallel.processing", "true");
        properties.setProperty("accident.coefficient.path", basePath);
        properties.setProperty("accident.model.scale.factor", "0.1");
        return properties;
    }

    /**
     * Creates performance-optimised properties for benchmark testing.
     */
    public static Properties createPerformanceOptimisedProperties(String basePath) {
        Properties properties = new Properties();
        properties.setProperty("accident.model.batch.size", "500");
        properties.setProperty("accident.model.parallel.processing", "true");
        properties.setProperty("accident.coefficient.path", basePath);
        properties.setProperty("accident.model.scale.factor", "0.1");
        properties.setProperty("accident.model.thread.pool.size", "4");
        properties.setProperty("accident.model.early.exit.enabled", "true");
        return properties;
    }

    /**
     * Creates minimal properties for basic testing.
     */
    public static Properties createMinimalProperties(String basePath) {
        Properties properties = new Properties();
        properties.setProperty("accident.coefficient.path", basePath);
        return properties;
    }

    /**
     * Creates properties with parallel processing disabled.
     */
    public static Properties createSequentialProcessingProperties(String basePath) {
        Properties properties = createStandardAccidentModelProperties(basePath);
        properties.setProperty("accident.model.parallel.processing", "false");
        return properties;
    }

    /**
     * Creates properties for memory-optimised processing.
     */
    public static Properties createMemoryOptimisedProperties(String basePath) {
        Properties properties = new Properties();
        properties.setProperty("accident.model.batch.size", "100");
        properties.setProperty("accident.model.parallel.processing", "true");
        properties.setProperty("accident.coefficient.path", basePath);
        properties.setProperty("accident.model.scale.factor", "0.1");
        properties.setProperty("accident.model.memory.optimised", "true");
        return properties;
    }

    /**
     * Creates properties for testing configuration validation.
     */
    public static Properties createInvalidProperties() {
        Properties properties = new Properties();
        properties.setProperty("accident.model.batch.size", "invalid");
        return properties;
    }
}
