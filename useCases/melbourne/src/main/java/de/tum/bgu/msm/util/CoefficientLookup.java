package de.tum.bgu.msm.util;

import de.tum.bgu.msm.data.Purpose;
import de.tum.bgu.msm.resources.Properties;
import de.tum.bgu.msm.resources.Resources;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.cam.mrc.phm.util.ExtractCoefficient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-computed lookup table for mode choice coefficients using CoefficientSets to optimize memory usage.
 * This class initialises once at startup and provides fast, thread-safe access to coefficients.
 */
public class CoefficientLookup {
    private static final Logger logger = LogManager.getLogger(CoefficientLookup.class);

    // Optimized lookup table: Purpose -> Mode -> CoefficientSet (pre-computed)
    private static final Map<Purpose, Map<String, CoefficientSet>> COEFFICIENT_SETS = new ConcurrentHashMap<>();

    // Track initialization status
    private static volatile boolean initialised = false;

    /**
     * Initialise the lookup table once at startup
     */
    public static synchronized void initialise() {
        if (initialised) {
            return;
        }

        try {
            // Check if Resources is available for production use
            if (Resources.instance != null) {
                initialiseFromResources();
            } else {
                // For testing - initialise with default values
                initialiseForTesting();
            }
            initialised = true;
        } catch (Exception e) {
            logger.error("Failed to initialise coefficient lookup table", e);
            // For testing - try fallback initialization
            try {
                initialiseForTesting();
                initialised = true;
                logger.info("Initialised coefficient lookup table with test defaults");
            } catch (Exception fallbackError) {
                throw new RuntimeException("Failed to initialise coefficient lookup table", e);
            }
        }
    }

    /**
     * Initialise from Resources (production mode)
     */
    private static void initialiseFromResources() {
        logger.info("Initializing coefficient lookup table from Resources...");
        long startTime = System.currentTimeMillis();

        // Get all purposes from the properties
        String[] purposeStrings = Resources.instance.getString(Properties.TRIP_PURPOSES).split(",");
        String[] modes = {"bicycle", "bike", "walk"};

        int coefficientsLoaded = 0;

        for (String purposeString : purposeStrings) {
            Purpose purpose = Purpose.valueOf(purposeString.trim());
            Map<String, CoefficientSet> modeMap = new ConcurrentHashMap<>();

            for (String mode : modes) {
                CoefficientSet coefficientSet = loadCoefficientSetFromResources(purpose, mode);
                modeMap.put(mode, coefficientSet);
                coefficientsLoaded++;
            }

            COEFFICIENT_SETS.put(purpose, modeMap);
        }

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Coefficient lookup table initialised with {} purposes, {} coefficient sets loaded in {}ms",
                COEFFICIENT_SETS.size(), coefficientsLoaded, duration);
    }

    /**
     * Load a complete CoefficientSet from Resources for a specific purpose/mode combination
     */
    private static CoefficientSet loadCoefficientSetFromResources(Purpose purpose, String mode) {
        try {
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

            return new CoefficientSet(
                grad, stressLink, vgvi, speed,
                grad_f, stressLink_f, vgvi_f, speed_f,
                grad_c, stressLink_c, vgvi_c, speed_c
            );
        } catch (Exception e) {
            logger.warn("Failed to load coefficient set for purpose={}, mode={}: {}",
                       purpose, mode, e.getMessage());
            return getTestDefaultCoefficientSet(purpose, mode);
        }
    }

    /**
     * Initialise with test defaults (testing mode)
     */
    private static void initialiseForTesting() {
        logger.info("Initializing coefficient lookup table with test defaults...");

        Purpose[] purposes = {Purpose.NHBW, Purpose.HBW, Purpose.HBS, Purpose.HBO};
        String[] modes = {"bicycle", "bike", "walk"};

        for (Purpose purpose : purposes) {
            Map<String, CoefficientSet> modeMap = new ConcurrentHashMap<>();

            for (String mode : modes) {
                CoefficientSet coefficientSet = getTestDefaultCoefficientSet(purpose, mode);
                modeMap.put(mode, coefficientSet);
            }

            COEFFICIENT_SETS.put(purpose, modeMap);
        }

        logger.info("Test coefficient lookup table initialised with {} purposes", COEFFICIENT_SETS.size());
    }

    /**
     * Get test default CoefficientSet for a specific purpose/mode combination
     */
    private static CoefficientSet getTestDefaultCoefficientSet(Purpose purpose, String mode) {
        // Use actual values from mc_coefficients_nhbw.csv for NHBW purpose
        if (purpose == Purpose.NHBW) {
            if (mode.equals("bicycle") || mode.equals("bike")) {
                return new CoefficientSet(
                    -0.2,    // grad
                    -0.5,    // stressLink
                    0.3,     // vgvi
                    0.2,     // speed
                    0.05,    // grad_f
                    0.05,    // stressLink_f
                    0.05,    // vgvi_f
                    0.05,    // speed_f
                    0.02,    // grad_c
                    0.02,    // stressLink_c
                    0.02,    // vgvi_c
                    0.02     // speed_c
                );
            } else if (mode.equals("walk")) {
                return new CoefficientSet(
                    0.1,     // grad
                    -0.1,    // stressLink
                    0.3,     // vgvi
                    0.4,     // speed
                    0.05,    // grad_f
                    0.05,    // stressLink_f
                    0.05,    // vgvi_f
                    0.05,    // speed_f
                    0.02,    // grad_c
                    0.02,    // stressLink_c
                    0.02,    // vgvi_c
                    0.02     // speed_c
                );
            }
        }

        // Default coefficients for other purposes
        if (mode.equals("walk")) {
            return new CoefficientSet(
                0.1, -0.1, 0.3, 0.4,      // base coefficients
                -0.05, -0.1, 0.05, 0.1,   // female interactions
                0.02, -0.08, 0.03, 0.06   // child interactions
            );
        } else { // bicycle/bike
            return new CoefficientSet(
                -0.2, -0.5, 0.3, 0.2,     // base coefficients
                0.05, 0.05, 0.05, 0.05,   // female interactions
                0.02, 0.02, 0.02, 0.02    // child interactions
            );
        }
    }

    /**
     * Fast, thread-safe coefficient lookup for individual attributes (backward compatibility)
     */
    public static double getCoefficient(Purpose purpose, String mode, String attribute) {
        if (!initialised) {
            initialise();
        }

        CoefficientSet coefficients = COEFFICIENT_SETS
                .getOrDefault(purpose, Map.of())
                .getOrDefault(mode, getTestDefaultCoefficientSet(purpose, mode));

        return coefficients.getAttribute(attribute);
    }

    /**
     * Get all coefficients for a mode/purpose combination at once.
     * This is the most efficient method for the calculateActiveModeWeights function.
     */
    public static CoefficientSet getCoefficients(Purpose purpose, String mode) {
        if (!initialised) {
            initialise();
        }

        return COEFFICIENT_SETS
                .getOrDefault(purpose, Map.of())
                .getOrDefault(mode, getTestDefaultCoefficientSet(purpose, mode));
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
