package de.tum.bgu.msm.util;

import de.tum.bgu.msm.data.Purpose;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Pre-computed lookup table for mode choice coefficients using CoefficientSets to optimize memory usage.
 * This class initialises once at startup and provides fast, thread-safe access to coefficients.
 * Uses the existing ExtractCoefficient function to retrieve and cache coefficients.
 */
public class CoefficientLookup {
    private static final Logger logger = LogManager.getLogger(CoefficientLookup.class);
    private static final Properties properties = MelbourneImplementationConfig.getMitoBaseProperties();

    // Optimized lookup table: Purpose -> Mode -> CoefficientSet (pre-computed)
    private static final Map<Purpose, Map<String, CoefficientSet>> COEFFICIENT_SETS = new ConcurrentHashMap<>();

    // Track initialization status
    private static volatile boolean initialised = false;

    // Standard modes expected in coefficient files
    private static final String[] MODES = {"autoDriver", "autoPassenger", "pt", "bicycle", "walk"};

    // Standard attributes expected in coefficient files
    private static final String[] ATTRIBUTES = {
        "grad", "stressLink", "vgvi", "speed",
        "grad_f", "stressLink_f", "vgvi_f", "speed_f",
        "grad_c", "stressLink_c", "vgvi_c", "speed_c"
    };

    // Purposes to load (for testing, only NHBW is available)
    private static Purpose[] PURPOSES = getPurposesFromProperties();

    private static Purpose[] getPurposesFromProperties() {
        String purposeString = properties.getProperty("trip.purposes");
        if (purposeString == null || purposeString.trim().isEmpty()) {
            logger.warn("No trip.purposes property found, reverting to test case NHBW only");
            return new Purpose[]{Purpose.NHBW};
        }

        String[] purposeNames = purposeString.split(",");
        java.util.List<Purpose> validPurposes = new java.util.ArrayList<>();

        for (String purposeName : purposeNames) {
            try {
                Purpose purpose = Purpose.valueOf(purposeName.trim());
                validPurposes.add(purpose);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid purpose name '{}' in trip.purposes property", purposeName.trim());
            }
        }

        if (validPurposes.isEmpty()) {
            logger.warn("No valid purposes found in trip.purposes property, using all purposes");
            return Purpose.values();
        }

        logger.info("Loaded {} purposes from trip.purposes property: {}", validPurposes.size(), validPurposes);
        return validPurposes.toArray(new Purpose[0]);
    }

    /**
     * Normalize mode names to handle common variations.
     * Specifically handles 'bike' -> 'bicycle' mapping.
     */
    private static String cleanMode(String mode) {
        if (mode == null) {
            return null;
        }
        // Handle bike/bicycle ambiguity
        if ("bike".equalsIgnoreCase(mode)) {
            return "bicycle";
        }
        return mode;
    }

    /**
     * Initialise the lookup table with coefficients using the existing ExtractCoefficient function
     */
    public static synchronized void initialise() {
        if (initialised) {
            return;
        }

        try {
            initialiseFromExtractCoefficient();
            initialised = true;
            logger.info("Coefficient lookup table initialised using ExtractCoefficient");
            logger.info("  - Purposes: {}", COEFFICIENT_SETS.keySet());
            logger.info("  - Modes: {}", java.util.Arrays.toString(MODES));
        } catch (Exception e) {
            logger.error("Failed to initialise coefficient lookup table", e);
            throw new RuntimeException("Failed to initialise coefficient lookup table", e);
        }
    }

    public static synchronized void initialiseTest() {
        PURPOSES = new Purpose[]{Purpose.NHBW};
        initialise();
    }

    /**
     * Initialise from ExtractCoefficient function
     */
    private static void initialiseFromExtractCoefficient() {
        logger.info("Initializing coefficient lookup table using ExtractCoefficient...");

        for (Purpose purpose : PURPOSES) {
            Map<String, CoefficientSet> modeMap = new ConcurrentHashMap<>();

            for (String mode : MODES) {
                // Extract all coefficients for this purpose/mode combination using the local ExtractCoefficient class
                Double grad = ExtractCoefficient.extractCoefficient(purpose, mode, "grad");
                Double stressLink = ExtractCoefficient.extractCoefficient(purpose, mode, "stressLink");
                Double vgvi = ExtractCoefficient.extractCoefficient(purpose, mode, "vgvi");
                Double speed = ExtractCoefficient.extractCoefficient(purpose, mode, "speed");
                Double grad_f = ExtractCoefficient.extractCoefficient(purpose, mode, "grad_f");
                Double stressLink_f = ExtractCoefficient.extractCoefficient(purpose, mode, "stressLink_f");
                Double vgvi_f = ExtractCoefficient.extractCoefficient(purpose, mode, "vgvi_f");
                Double speed_f = ExtractCoefficient.extractCoefficient(purpose, mode, "speed_f");
                Double grad_c = ExtractCoefficient.extractCoefficient(purpose, mode, "grad_c");
                Double stressLink_c = ExtractCoefficient.extractCoefficient(purpose, mode, "stressLink_c");
                Double vgvi_c = ExtractCoefficient.extractCoefficient(purpose, mode, "vgvi_c");
                Double speed_c = ExtractCoefficient.extractCoefficient(purpose, mode, "speed_c");

                // Create CoefficientSet for this mode
                CoefficientSet coefficientSet = new CoefficientSet(
                    grad, stressLink, vgvi, speed,
                    grad_f, stressLink_f, vgvi_f, speed_f,
                    grad_c, stressLink_c, vgvi_c, speed_c
                );

                modeMap.put(mode, coefficientSet);
            }

            COEFFICIENT_SETS.put(purpose, modeMap);
            logger.debug("Loaded coefficients for purpose {} with {} modes", purpose, MODES.length);
        }

        if (COEFFICIENT_SETS.isEmpty()) {
            throw new RuntimeException("No valid coefficient data loaded");
        }
    }

    /**
     * Fast, thread-safe coefficient lookup for individual attributes (backward compatibility)
     */
    public static double getCoefficient(Purpose purpose, String mode, String attribute) {
        if (!initialised) {
            throw new IllegalStateException("CoefficientLookup not initialised. Call initialise() first.");
        }

        // clean mode name to handle bike/bicycle ambiguity
        String cleandMode = cleanMode(mode);

        CoefficientSet coefficients = COEFFICIENT_SETS
                .getOrDefault(purpose, Map.of())
                .get(cleandMode);

        if (coefficients == null) {
            logger.warn("No coefficients found for purpose={}, mode={}", purpose, cleandMode);
            return 0.0;
        }

        return coefficients.getAttribute(attribute);
    }

    /**
     * Get all coefficients for a mode/purpose combination at once.
     * This is the most efficient method for the calculateActiveModeWeights function.
     */
    public static CoefficientSet getCoefficients(Purpose purpose, String mode) {
        if (!initialised) {
            throw new IllegalStateException("CoefficientLookup not initialised. Call initialise() first.");
        }

        // clean mode name to handle bike/bicycle ambiguity
        String cleandMode = cleanMode(mode);

        CoefficientSet coefficients = COEFFICIENT_SETS
                .getOrDefault(purpose, Map.of())
                .get(cleandMode);

        if (coefficients == null) {
            logger.warn("No coefficients found for purpose={}, mode={}", purpose, cleandMode);
            // Return empty coefficient set with all zeros
            return new CoefficientSet(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        return coefficients;
    }

    /**
     * Get lookup table statistics for debugging/monitoring
     */
    public static String getStatistics() {
        if (!initialised) {
            return "Coefficient lookup table not initialised";
        }

        int totalCoefficients = COEFFICIENT_SETS.values().stream()
                .mapToInt(modeMap -> modeMap.size() * 12) // 12 coefficients per set
                .sum();

        return String.format("Purposes: %d, Total coefficient sets: %d, Total coefficients: %d, Initialised: %s",
                COEFFICIENT_SETS.size(),
                COEFFICIENT_SETS.values().stream().mapToInt(Map::size).sum(),
                totalCoefficients,
                initialised);
    }

    /**
     * Reset for testing
     */
    public static synchronized void reset() {
        COEFFICIENT_SETS.clear();
        initialised = false;
    }

    /**
     * Immutable data class to hold all coefficients for a purpose/mode combination.
     * This eliminates the need for multiple map lookups and reduces memory overhead.
     */
    public static class CoefficientSet {
        public final double grad, stressLink, vgvi, speed;
        public final double grad_f, stressLink_f, vgvi_f, speed_f;  // female interaction terms
        public final double grad_c, stressLink_c, vgvi_c, speed_c;  // child interaction terms

        public CoefficientSet(Double grad, Double stressLink, Double vgvi, Double speed,
                             Double grad_f, Double stressLink_f, Double vgvi_f, Double speed_f,
                             Double grad_c, Double stressLink_c, Double vgvi_c, Double speed_c) {
            this.grad = grad != null ? grad : 0.0;
            this.stressLink = stressLink != null ? stressLink : 0.0;
            this.vgvi = vgvi != null ? vgvi : 0.0;
            this.speed = speed != null ? speed : 0.0;
            this.grad_f = grad_f != null ? grad_f : 0.0;
            this.stressLink_f = stressLink_f != null ? stressLink_f : 0.0;
            this.vgvi_f = vgvi_f != null ? vgvi_f : 0.0;
            this.speed_f = speed_f != null ? speed_f : 0.0;
            this.grad_c = grad_c != null ? grad_c : 0.0;
            this.stressLink_c = stressLink_c != null ? stressLink_c : 0.0;
            this.vgvi_c = vgvi_c != null ? vgvi_c : 0.0;
            this.speed_c = speed_c != null ? speed_c : 0.0;
        }

        // Constructor for test defaults with primitive doubles
        public CoefficientSet(double grad, double stressLink, double vgvi, double speed,
                             double grad_f, double stressLink_f, double vgvi_f, double speed_f,
                             double grad_c, double stressLink_c, double vgvi_c, double speed_c) {
            this.grad = grad;
            this.stressLink = stressLink;
            this.vgvi = vgvi;
            this.speed = speed;
            this.grad_f = grad_f;
            this.stressLink_f = stressLink_f;
            this.vgvi_f = vgvi_f;
            this.speed_f = speed_f;
            this.grad_c = grad_c;
            this.stressLink_c = stressLink_c;
            this.vgvi_c = vgvi_c;
            this.speed_c = speed_c;
        }

        /**
         * Get coefficient by attribute name for backward compatibility
         */
        public double getAttribute(String attribute) {
            return switch (attribute) {
                case "grad" -> grad;
                case "stressLink" -> stressLink;
                case "vgvi" -> vgvi;
                case "speed" -> speed;
                case "grad_f" -> grad_f;
                case "stressLink_f" -> stressLink_f;
                case "vgvi_f" -> vgvi_f;
                case "speed_f" -> speed_f;
                case "grad_c" -> grad_c;
                case "stressLink_c" -> stressLink_c;
                case "vgvi_c" -> vgvi_c;
                case "speed_c" -> speed_c;
                default -> 0.0;
            };
        }

        @Override
        public String toString() {
            return String.format("CoefficientSet{grad=%.6f, stressLink=%.6f, vgvi=%.6f, speed=%.6f, " +
                    "grad_f=%.6f, stressLink_f=%.6f, vgvi_f=%.6f, speed_f=%.6f, " +
                    "grad_c=%.6f, stressLink_c=%.6f, vgvi_c=%.6f, speed_c=%.6f}",
                    grad, stressLink, vgvi, speed,
                    grad_f, stressLink_f, vgvi_f, speed_f,
                    grad_c, stressLink_c, vgvi_c, speed_c);
        }
    }
}
