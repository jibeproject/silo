package de.tum.bgu.msm.health.disease;

import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.health.data.DataContainerHealth;
import de.tum.bgu.msm.health.data.PersonHealth;

import java.util.EnumMap;

// Dose-response functions for health exposures (simple for now but will become more complex)
public class RelativeRisksDisease {

    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(RelativeRisksDisease.class);
    public static EnumMap<Diseases, Float> calculateForPA(PersonHealth personHealth, DataContainerHealth dataContainer) {
        double total_mmet = Math.min(17.5, personHealth.getWeeklyMarginalMetHours(Mode.walk) +
                personHealth.getWeeklyMarginalMetHours(Mode.bicycle) +
                personHealth.getWeeklyMarginalMetHoursSport());

        return calculateRelativeRisk(
                personHealth,
                HealthExposures.PHYSICAL_ACTIVITY,
                total_mmet,
                "Physical Activity",
                dataContainer);
    }

    public static EnumMap<Diseases, Float> calculateForPM25(PersonHealth personHealth, DataContainerHealth dataContainer) {
        double total_pm25 = personHealth.getWeeklyExposureByPollutantNormalised("pm2.5");
        return calculateRelativeRisk(
                personHealth,
                HealthExposures.AIR_POLLUTION_PM25,
                total_pm25,
                "PM2.5",
                dataContainer);
    }

    public static EnumMap<Diseases, Float> calculateForNO2(PersonHealth personHealth, DataContainerHealth dataContainer) {
        double total_no2 = personHealth.getWeeklyExposureByPollutantNormalised("no2");
        return calculateRelativeRisk(
                personHealth,
                HealthExposures.AIR_POLLUTION_NO2,
                total_no2,
                "NO2",
                dataContainer);
    }

    public static EnumMap<Diseases, Float> calculateForNoise(PersonHealth personHealth, DataContainerHealth dataContainer) {
        double total_noiseLevel = personHealth.getWeeklyNoiseExposuresNormalised();
        return calculateRelativeRisk(
                personHealth,
                HealthExposures.NOISE,
                total_noiseLevel,
                "Noise",
                dataContainer);
    }

    public static EnumMap<Diseases, Float> calculateForNDVI(PersonHealth personHealth, DataContainerHealth dataContainer) {
        double total_ndvi = personHealth.getWeeklyGreenExposuresNormalised();
        return calculateRelativeRisk(
                personHealth,
                HealthExposures.NDVI,
                total_ndvi,
                "NDVI",
                dataContainer);
    }

    /**
     * Helper method to handle common dose-response interpolation logic across different exposure types
     *
     * @param personHealth The person whose health is being evaluated
     * @param exposureType The type of exposure (PM2.5, NO2, Noise, etc.)
     * @param exposureValue The value of the exposure to interpolate
     * @param dataContainer The data container with dose-response information
     * @return Map of diseases to relative risk values
     */
    private static EnumMap<Diseases, Float> calculateRelativeRisk(
            PersonHealth personHealth,
            HealthExposures exposureType,
            double exposureValue,
            String exposureTypeName,
            DataContainerHealth dataContainer) {

        EnumMap<Diseases, Float> relativeRisksByDisease = new EnumMap<>(Diseases.class);

        // Check if the exposure value is valid
        if (Double.isNaN(exposureValue)) {
            logger.warn("NaN {} value detected for person ID: {}, Weekly {} exposure: {}",
                    exposureTypeName, personHealth.getId(), exposureTypeName, exposureValue);
            return relativeRisksByDisease; // Return empty map rather than continuing with invalid data
        }

        for(Diseases disease : dataContainer.getDoseResponseData().get(exposureType).keySet()) {
            try {
                // Get the data arrays for potential debugging
                double[] doses = dataContainer.getDoseResponseData()
                    .get(exposureType)
                    .get(disease)
                    .getColumnAsDouble("dose");

                double[] responses = dataContainer.getDoseResponseData()
                    .get(exposureType)
                    .get(disease)
                    .getColumnAsDouble("RR");

                // Check if interpolation input is in range
                if (doses.length > 0 && (exposureValue < doses[0] || exposureValue > doses[doses.length - 1])) {
                    logger.warn("{} value {} is outside the dose range [{}, {}] for disease {}. Doses: {}, Responses: {}",
                        exposureTypeName, exposureValue, doses[0], doses[doses.length - 1], disease.name(),
                        java.util.Arrays.toString(doses), java.util.Arrays.toString(responses));
                }

                // Proceed with interpolation if all values are valid
                double rr = LinearInterpolation.interpolate(doses, responses, exposureValue);
                relativeRisksByDisease.put(disease, (float) rr);
            } catch (Exception e) {
                logger.error("Error calculating RR for disease {} and person ID {} ({}): {}",
                        disease, personHealth.getId(), exposureTypeName, e.getMessage(), e);
                // Continue with next disease rather than failing completely
            }
        }

        return relativeRisksByDisease;
    }

}
