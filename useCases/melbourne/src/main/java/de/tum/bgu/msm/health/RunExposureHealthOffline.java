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
        AccidentModelMEL accidentModel = new AccidentModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());
        HealthExposureModelMEL exposureModelMEL = new HealthExposureModelMEL(dataContainer, properties, SiloUtil.provideNewRandom(),config);
        SportPAModelMEL sportPAModelMEL = new SportPAModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());
        DiseaseModelMEL diseaseModelMEL = new DiseaseModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());

        // disease model only
        // exposureModelMCR.setup();

        // runs
        accidentModel.endYear(2018);
        //exposureModelMCR.setup(); // read-in the exposure file
        exposureModelMEL.endYear(2018);
        sportPAModelMEL.endYear(2018);
        diseaseModelMEL.setup();
        diseaseModelMEL.endYear(2018);
        dataContainer.endSimulation();

        logger.info("Finished SILO.");
    }
}