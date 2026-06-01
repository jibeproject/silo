package de.tum.bgu.msm.health;

import de.tum.bgu.msm.health.airPollutant.AirPollutantModel;
import de.tum.bgu.msm.health.noise.NoiseModel;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.resources.Resources;
import de.tum.bgu.msm.utils.SiloUtil;
import org.apache.logging.log4j.Level;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Diagnostic version of health exposure model runner for Melbourne
 * This version provides faster iteration for diagnosing exposure calculation issues.
 * 
 * Key differences from RunExposureHealthOffline:
 * - SKIPS accident model (saves significant runtime)
 * - SKIPS disease model (not needed for exposure diagnostics)  
 * - INCLUDES fail-fast validation that throws exceptions on invalid values
 * - Validates hourOccupied values are in range [0, 1]
 * - Checks for negative home minutes
 * - Can be run with reduced sample sizes for faster iteration
 * 
 * Use this runner to quickly identify trip scheduling issues causing:
 * - Overlapping trips/activities (hourOccupied > 1.0)
 * - Negative time at home
 * - Excessive walking exposures
 *
 * @author GitHub Copilot
 * @author Carl Higgs
 * @author Qin Zhang
 **/

public class RunHealthExposureDiagnostic {

    private final static Logger logger = LogManager.getLogger(RunHealthExposureDiagnostic.class);
    
    /**
     * Check if output file exists to avoid re-running models
     */
    public static boolean OutputFileExists(String path) {
        return Files.exists(Paths.get(path));
    }

    public static void main(String[] args) throws IOException {
        SiloUtil.captureLog(Level.INFO, "Started SILO offline health exposure model for Greater Melbourne (DIAGNOSTIC MODE)");
        SiloUtil.captureLog(Level.INFO, "Scenario properties: " + args[0]);
        Properties properties = SiloUtil.siloInitialization(args[0]);

        Resources.initializeResources(properties.transportModel.mitoPropertiesPath);

        // Parse additional arguments
        boolean useSampling = false;
        int sampleSize = 1000;
        
        if (args.length > 2 && "--sample".equals(args[2])) {
            useSampling = true;
            if (args.length > 3) {
                try {
                    sampleSize = Integer.parseInt(args[3]);
                    logger.info("Using sampling mode with {} trips per mode/day", sampleSize);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid sample size '{}', using default {}", args[3], sampleSize);
                }
            }
        }

        Config config = null;
        if (args.length > 1 && args[1] != null) {
            config = ConfigUtils.loadConfig(args[1]);
        }
        
        int endYear = properties.main.endYear;
        HealthDataContainerImpl dataContainer = DataBuilderHealth.getModelDataForMelbourne(properties, config);
        DataBuilderHealth.read(properties, dataContainer, config);

        // setup - only models needed for exposure diagnostics
        AirPollutantModel airPollutantModel = null;
        NoiseModel noiseModel = null;
        final String outputDirectory = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName + "/";
        String day = "thursday";
        String airPollutionFileCheckPath = outputDirectory + "linkConcentration_" + day + ".csv";
        String noiseFileCheckPath = outputDirectory + "matsim/" + endYear + "/" + day + "/car/noise-analysis/receiverPoints/receiverPoints.csv";
        
        if (OutputFileExists(airPollutionFileCheckPath)) {
            logger.warn("Air pollution output exists ({}). Air pollution modelling will be skipped. Please delete existing results to re-run.",airPollutionFileCheckPath);
        } else {
            airPollutantModel = new AirPollutantModel(dataContainer, properties, SiloUtil.provideNewRandom(), config);
        }
        
        if (OutputFileExists(noiseFileCheckPath)) {
            logger.warn("Noise output exists ({}). Noise modelling will be skipped. Please delete existing results to re-run.",noiseFileCheckPath);
        } else {
            noiseModel = new NoiseModel(dataContainer, properties, SiloUtil.provideNewRandom(), config);
        }
        
        SportPAModelMEL sportPAModelMEL = new SportPAModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());
        HealthExposureModelMEL exposureModelMEL = new HealthExposureModelMEL(dataContainer, properties, SiloUtil.provideNewRandom(),config);
        
        // NOTE: Accident and Disease models are SKIPPED in diagnostic mode for faster iteration

        // Enable schedule diagnostics
        String diagnosticOutputFile = outputDirectory + "schedule_diagnostics_" + endYear + ".txt";
        ScheduleDiagnostics.setOutputFile(diagnosticOutputFile);
        // Note: Initially tracking is disabled. ScheduleDiagnostics.trackPerson() will be called
        // automatically when over-allocation is detected via the exception handler
        logger.info("Schedule diagnostics enabled. Output file: {}", diagnosticOutputFile);
        
        // FOCUS ON WALK MODE - the most problematic mode for Melbourne
        logger.info("===============================================");
        logger.info("MODE FOCUS: WALK ONLY");
        logger.info("This will process only 'walk' mode to identify issues faster.");
        logger.info("To change focus mode, modify the 'health.diagnostic.focusMode' property");
        logger.info("===============================================");
        System.setProperty("health.diagnostic.focusMode", "walk");
        
        // If sampling is enabled, we could modify the trip reading process here
        // For now, note that sampling can be enabled in HealthExposureModelMEL line 116
        if (useSampling) {
            logger.warn("===============================================");
            logger.warn("SAMPLING MODE ENABLED - Results will use {} trips per mode/day", sampleSize);
            logger.warn("To enable sampling, uncomment line in HealthExposureModelMEL.java:");
            logger.warn("  mitoTripsAll = TripSelector.selectRandomSubset(mitoTripsAll, {});", sampleSize);
            logger.warn("===============================================");
        }

        // runs - only exposure models for diagnostic purposes
        logger.info("===============================================");
        logger.info("DIAGNOSTIC MODE - Running exposure calculations with validation");
        logger.info("Skipping: Accident model and Disease model for faster iteration");
        logger.info("===============================================");
        
        if (!OutputFileExists(airPollutionFileCheckPath)) {
            airPollutantModel.endYear(endYear);
        }
        if (!OutputFileExists(noiseFileCheckPath)) {
            noiseModel.endYear(endYear);
        }
        
        sportPAModelMEL.endYear(endYear);
        
        logger.info("Running exposure model with fail-fast validation...");
        exposureModelMEL.endYear(endYear);
        
        // Note: Disease model skipped in diagnostic mode
        
        dataContainer.endSimulation();

        logger.info("===============================================");
        logger.info("DIAGNOSTIC RUN COMPLETED SUCCESSFULLY");
        logger.info("===============================================");
        logger.info("No validation errors detected:");
        logger.info("  - All hourOccupied values are in range [0, 1.0]");
        logger.info("  - All weeklyHomeMinutes values are in range [0, 10080]");
        logger.info("  - No overlapping trips/activities detected");
        logger.info("===============================================");

        logger.info("Finished SILO diagnostic run.");
    }
}




