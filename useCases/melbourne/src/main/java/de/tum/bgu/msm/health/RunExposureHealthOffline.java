package de.tum.bgu.msm.health;

import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.resources.Resources;
import de.tum.bgu.msm.utils.SiloUtil;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Implements SILO for the Greater Melbourne
 *
 * @author Qin Zhang*/


public class RunExposureHealthOffline {

    private final static Logger logger = LogManager.getLogger(RunExposureHealthOffline.class);

    public static void main(String[] args) throws IOException {

        Properties properties = SiloUtil.siloInitialization(args[0]);

        Resources.initializeResources(properties.transportModel.mitoPropertiesPath);

        Config config = null;
        if (args.length > 1 && args[1] != null) {
            config = ConfigUtils.loadConfig(args[1]);
        }
        logger.info("Started SILO land use model for Greater Melbourne");
        HealthDataContainerImpl dataContainer = DataBuilderHealth.getModelDataForMelbourne(properties, config);
        DataBuilderHealth.read(properties, dataContainer, config);

        // setup
        SportPAModelMEL sportPAModelMEL = new SportPAModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());
        AccidentModelMEL accidentModel = new AccidentModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());
        HealthExposureModelMEL exposureModelMEL = new HealthExposureModelMEL(dataContainer, properties, SiloUtil.provideNewRandom(),config);
        DiseaseModelMEL diseaseModelMEL = new DiseaseModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());

        // runs
        sportPAModelMEL.endYear(2018);
        accidentModel.endYear(2018);
        exposureModelMEL.endYear(2018);
        diseaseModelMEL.setup();
        diseaseModelMEL.endYear(2018);
        dataContainer.endSimulation();

        logger.info("Finished SILO.");
    }
}
