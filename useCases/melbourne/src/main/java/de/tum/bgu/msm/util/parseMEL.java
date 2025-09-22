package de.tum.bgu.msm.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class parseMEL {

    private static final Logger logger = LogManager.getLogger(parseMEL.class);

    public static int zoneParse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null");
        }
        input = input.trim();
        if (input.startsWith("\"") && input.endsWith("\"")) {
            input = input.substring(1, input.length() - 1);
        }

        if (input.isEmpty()) {
            throw new IllegalArgumentException("Input cannot be an empty string");
        }
        int length = input.length();

        if (length == 11) {
            // Extract first digit and last 6 digits
            String truncated = input.charAt(0) + input.substring(5);
            return Integer.parseInt(truncated);
        } else if (length == 7) {
            // Return the 7-digit number as an integer
            return Integer.parseInt(input);
        } else {
            // Log raw input and its length
            logger.info("Raw input: [" + input + "], Length: " + input.length());

            // Log character codes
            StringBuilder charCodes = new StringBuilder();
            for (char c : input.toCharArray()) {
                charCodes.append((int) c).append(" ");
            }
            logger.info("Character codes: [" + charCodes.toString().trim() + "]");

            // Raise an exception for invalid lengths
            throw new IllegalArgumentException("Input must be either 7 or 11 digits long: " + input);
        }
    }

    public static int intParse(String input) {
        try {
            if (input == null) {
                throw new IllegalArgumentException("Input cannot be null");
            }

            String cleaned = input.trim();
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }

            // Allow numeric values with optional decimals or scientific notation
            if (!cleaned.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
                throw new NumberFormatException("Invalid numeric value: " + cleaned);
            }

            double value = Double.parseDouble(cleaned); // Parse as double to handle scientific notation
            return (int) Math.round(value); // Round and cast to int
        } catch (IllegalArgumentException e) {
            logger.error("Failed to parse input: " + input, e);
            throw e;
        }
    }

    public static int[] intParse(String[] input) {
        if (input == null) {
            throw new IllegalArgumentException("Input array cannot be null");
        }
        int[] parsedArray = new int[input.length];
        for (int i = 0; i < input.length; i++) {
            parsedArray[i] = intParse(input[i]);
        }
        return parsedArray; // Return the cleaned integer array
    }

    public static String stringParse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null");
        }
        input = input.trim();
        if (input.startsWith("\"") && input.endsWith("\"")) {
            input = input.substring(1, input.length() - 1);
        }
        return input; // Return the cleaned string
    }

    public static String[] stringParse(String[] input) {
        if (input == null) {
            throw new IllegalArgumentException("Input array cannot be null");
        }
        String[] parsedArray = new String[input.length];
        for (int i = 0; i < input.length; i++) {
            parsedArray[i] = stringParse(input[i]);
        }
        return parsedArray; // Return the cleaned string array
    }

    public static int getHoursAsSeconds(int hours) {
        if (hours < 0) {
            throw new IllegalArgumentException("Hours cannot be negative: " + hours);
        }
        return hours * 3600; // Convert hours to seconds
    }

    public static int findPositionInArray(String target, String[] array) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(target)) {
                return i;
            }
        }
        return -1; // Return -1 if the target is not found
    }

    // Efficient microBuilding ID mapping system
    private static final ConcurrentHashMap<String, Integer> stringToIntMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> intToStringMap = new ConcurrentHashMap<>();
    private static final AtomicInteger idCounter = new AtomicInteger(1); // Start from 1, reserve 0 for "no building"

    /**
     * Efficiently converts a microBuilding string ID to a sequential integer ID.
     * @param microbuildingId The original string ID
     * @return A unique sequential integer ID (1-based)
     */
    public static int stringToIntId(String microbuildingId) {
        if (microbuildingId == null || microbuildingId.trim().isEmpty()) {
            return 0; // Reserved for "no building" or missing ID
        }

        String cleanId = stringParse(microbuildingId);

        // Use computeIfAbsent for thread-safe lazy initialization
        return stringToIntMap.computeIfAbsent(cleanId, id -> {
            int newId = idCounter.getAndIncrement();
            intToStringMap.put(newId, cleanId);
            if (newId % 10000 == 0) {
                logger.debug("Assigned microBuilding ID {}: {} (total unique buildings: {})",
                        newId, cleanId, newId);
            }
            return newId;
        });
    }

    /**
     * Converts an integer microBuilding ID back to its original string form.
     * Enables perfect reversibility of the mapping.
     *
     * @param intId The integer ID
     * @return The original string ID, or null if not found
     */
    public static String intIdToString(int intId) {
        if (intId == 0) {
            return null; // Reserved for "no building"
        }
        return intToStringMap.get(intId);
    }

    /**
     * Gets the current count of unique microBuilding IDs that have been mapped.
     * Useful for monitoring and validation.
     */
    public static int getUniqueMicroBuildingCount() {
        return stringToIntMap.size();
    }

    /**
     * Clears the microBuilding ID mapping (useful for testing or reinitialization).
     * Warning: This will invalidate all previously assigned integer IDs.
     */
    public static void clearMicroBuildingMapping() {
        stringToIntMap.clear();
        intToStringMap.clear();
        idCounter.set(1);
        logger.info("Cleared microBuilding ID mapping");
    }

    /**
     * Gets memory usage statistics for the mapping system.
     */
    public static String getMappingStats() {
        int uniqueCount = stringToIntMap.size();
        int nextId = idCounter.get();
        long estimatedMemoryBytes = uniqueCount * (36 + 32); // rough estimate: int + overhead + avg string
        return String.format("MicroBuilding ID Mapping Stats: %d unique IDs, next ID: %d, estimated memory: %.2f MB",
                uniqueCount, nextId, estimatedMemoryBytes / 1024.0 / 1024.0);
    }

    /**
     * @deprecated Use stringToIntId() instead for better efficiency and collision-free mapping
     */
    @Deprecated
    public static long stringToLongId(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(str.getBytes(StandardCharsets.UTF_8));
            // Use the first 8 bytes to form a long
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xff);
            }
            return value;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}