package de.tum.bgu.msm.health;

import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.utils.SiloUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

import java.io.IOException;

/**
 * Implements SILO for the Greater Melbourne
 *
 * @author Qin Zhang
 * @author Carl Higgs
 **/


public class RunHomeBasedExposureOffline {

    private final static Logger logger = LogManager.getLogger(RunHomeBasedExposureOffline.class);

    public static void main(String[] args) throws IOException {
        logger.info("Started SILO home-based exposure offline model for Greater Melbourne");
        logger.info("Scenario properties: " + args[0]);
        Properties properties = SiloUtil.siloInitialization(args[0]);

        Config config = null;
        if (args.length > 1 && args[1] != null) {
            config = ConfigUtils.loadConfig(args[1]);
        }
        int endYear = properties.main.endYear;
        HealthDataContainerImpl dataContainer = DataBuilderHealth.getModelDataForMelbourne(properties, config);
        DataBuilderHealth.read(properties, dataContainer, config);

        HealthExposureModelMEL exposureModelMEL = new HealthExposureModelMEL(dataContainer, properties, SiloUtil.provideNewRandom(),config);


        exposureModelMEL.calculateHomeBasedExposureOnly(endYear);
        dataContainer.writePersonHomeBasedExposureData(endYear);

        logger.info("Finished SILO.");
    }
}
