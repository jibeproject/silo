package de.tum.bgu.msm.health;

import de.tum.bgu.msm.health.airPollutant.AirPollutantModel;
import de.tum.bgu.msm.health.noise.NoiseModel;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.resources.Resources;
import de.tum.bgu.msm.utils.SiloUtil;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implements SILO for the Greater Melbourne
 *
 * @author Qin Zhang*/


public class RunExposureHealthOffline {

    private final static Logger logger = LogManager.getLogger(RunExposureHealthOffline.class);

    public static void main(String[] args) throws IOException {
        logger.info("Started SILO offline health exposure model for Greater Melbourne");
        Properties properties = SiloUtil.siloInitialization(args[0]);
        Resources.initializeResources(properties.transportModel.mitoPropertiesPath);
        Random seed = SiloUtil.provideNewRandom();
        Config config = null;
        if (args.length > 1 && args[1] != null) {
            config = ConfigUtils.loadConfig(args[1]);
        }
        int endYear = properties.main.endYear;
        HealthDataContainerImpl dataContainer = DataBuilderHealth.getModelDataForMelbourne(properties, config);
        DataBuilderHealth.read(properties, dataContainer, config);

        // setup
        AirPollutantModel airPollutantModel = new AirPollutantModel(dataContainer, properties, seed, config);
        NoiseModel noiseModel = new NoiseModel(dataContainer,properties, seed,config);
        SportPAModelMEL sportPAModelMEL = new SportPAModelMEL(dataContainer, properties, seed);
        AccidentModelMEL accidentModel = new AccidentModelMEL(dataContainer, properties, seed);
        HealthExposureModelMEL exposureModelMEL = new HealthExposureModelMEL(dataContainer, properties, seed,config);
        DiseaseModelMEL diseaseModelMEL = new DiseaseModelMEL(dataContainer, properties, seed);

        // runs
        airPollutantModel.endYear(endYear);
        noiseModel.endYear(endYear);
        sportPAModelMEL.endYear(endYear);
        accidentModel.endYear(endYear);
        exposureModelMEL.endYear(endYear);
        diseaseModelMEL.setup();
        diseaseModelMEL.endYear(endYear);
        dataContainer.endSimulation();

        logger.info("Finished SILO.");
    }
}
