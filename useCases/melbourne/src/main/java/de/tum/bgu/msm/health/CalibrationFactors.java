package de.tum.bgu.msm.health;

import java.util.HashMap;
import java.util.Map;

public class CalibrationFactors {
    private static final Map<String, Map<String, Double>> calibrationFactors = new HashMap<>();

    static {
        // Initialize scenarios
        String[] scenarios = {"base", "cycling"};
        String[] modes = {"Car", "Bike", "Walk"};

        // Populate the map
        for (String scenario : scenarios) {
            Map<String, Double> modeFactors = new HashMap<>();
            for (String mode : modes) {
                // Set base scenario values
                if (scenario.equals("base")) {
                    switch (mode) {
                        case "Bike":
                            modeFactors.put(mode, 2.301601915);
                            break;
                        case "Car":
                            modeFactors.put(mode, 1.265471499);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 0.741037452);
                            break;
                    }
                } else if(scenario.equals("cycling")){
                    switch (mode) {
                        case "Bike":
                            modeFactors.put(mode, 1.0);
                            break;
                        case "Car":
                            modeFactors.put(mode, 1.0);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 1.0);
                            break;
                    }
                } else {
                    // Set other scenarios to 0
                    modeFactors.put(mode, 1.0);
                }
            }
            calibrationFactors.put(scenario, modeFactors);
        }
    }

    // Method to get calibration factor by scenario and mode
    public double getCalibrationFactor(String scenario, String mode) {
        return calibrationFactors.getOrDefault(scenario, new HashMap<>()).getOrDefault(mode, 0.0);
    }
}