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
 * Implements SILO for Greater Melbourne
 *
 * @author Qin Zhang
 * @author Carl Higgs
 **/


public class RunExposureHealthOffline {

    private final static Logger logger = LogManager.getLogger(RunExposureHealthOffline.class);

    public static void main(String[] args) throws IOException {
        SiloUtil.captureLog(Level.INFO, "Started SILO offline health exposure model for Greater Melbourne");
        SiloUtil.captureLog(Level.INFO, "Scenario properties: " + args[0]);
        Properties properties = SiloUtil.siloInitialization(args[0]);

        Resources.initializeResources(properties.transportModel.mitoPropertiesPath);

        Config config = null;
        if (args.length > 1 && args[1] != null) {
            config = ConfigUtils.loadConfig(args[1]);
        }
        int endYear = properties.main.endYear;
        HealthDataContainerImpl dataContainer = DataBuilderHealth.getModelDataForMelbourne(properties, config);
        DataBuilderHealth.read(properties, dataContainer, config);

        // setup
        AirPollutantModel airPollutantModel = null;
        NoiseModel noiseModel = null;
        final String outputDirectory = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName + "/";
        String day = "thursday";
        String airPollutionFileCheckPath = outputDirectory + "linkConcentration_" + day + ".csv";
        String noiseFileCheckPath = outputDirectory + "matsim/" + endYear + "/" + day + "/car/noise-analysis/receiverPoints/receiverPoints.csv";

        // Air pollution and noise outputs are only consumed by the exposure model's event
        // processing. When a base exposure file is supplied (and the end year is not an
        // exposure model year), exposures are read from that file instead, so both models
        // can be skipped regardless of whether their outputs exist on this machine.
        boolean exposureProcessingNeeded =
                (properties.healthData.baseExposureFile == null && endYear == properties.main.startYear)
                || properties.healthData.exposureModelYears.contains(endYear);
        if (!exposureProcessingNeeded) {
            logger.warn("Base exposure file supplied ({}); exposure event processing, air pollution and noise modelling will be skipped.",
                    properties.healthData.baseExposureFile);
        } else if (OutputFileExists(airPollutionFileCheckPath)) {
            logger.warn("Air pollution output exists ({}). Air pollution modelling will be skipped. Please delete existing results to re-run.",airPollutionFileCheckPath);
        } else {
            airPollutantModel = new AirPollutantModel(dataContainer, properties, SiloUtil.provideNewRandom(), config);
        }
        if (!exposureProcessingNeeded) {
            // noise model skipped along with air pollution (see warning above)
        } else if (OutputFileExists(noiseFileCheckPath)) {
            logger.warn("Noise output exists ({}). Noise modelling will be skipped. Please delete existing results to re-run.",noiseFileCheckPath);
        } else {
            noiseModel = new NoiseModel(dataContainer, properties, SiloUtil.provideNewRandom(), config);
        }
        
        AccidentModelMEL accidentModel = new AccidentModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());
        HealthExposureModelMEL exposureModelMEL = new HealthExposureModelMEL(dataContainer, properties, SiloUtil.provideNewRandom(),config);
        SportPAModelMEL sportPAModelMEL = new SportPAModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());
        DiseaseModelMEL diseaseModelMEL = new DiseaseModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());

        // runs (models are only constructed above when they actually need to run)
        if (airPollutantModel != null) {
            airPollutantModel.endYear(endYear);
        }
        if (noiseModel != null) {
            noiseModel.endYear(endYear);
        }
        accidentModel.endYear(endYear);
        exposureModelMEL.setup(); // read-in the exposure file
        exposureModelMEL.endYear(endYear);
        // Sport PA is cheap and independent of the MATSim-based exposure processing, so
        // always recompute it here. Calling endYear() instead would skip it whenever a
        // base exposure file is supplied (same gate as the exposure model), preventing
        // re-runs with updated sportPAmodel coefficients from taking effect.
        sportPAModelMEL.updateSportPA();
        diseaseModelMEL.setup();
        diseaseModelMEL.endYear(endYear);
        dataContainer.endSimulation();

        logger.info("Finished SILO.");
    }
    
    public static boolean OutputFileExists(String path) {
        return Files.exists(Paths.get(path));
    }
}