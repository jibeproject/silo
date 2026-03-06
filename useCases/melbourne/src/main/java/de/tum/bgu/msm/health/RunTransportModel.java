package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.MitoDataConverterMEL;
import de.tum.bgu.msm.matsim.MatsimData;
import de.tum.bgu.msm.matsim.MatsimScenarioAssembler;
import de.tum.bgu.msm.matsim.ZoneConnectorManager;
import de.tum.bgu.msm.models.transportModel.TransportModel;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.resources.Resources;
import de.tum.bgu.msm.utils.SiloUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;

public class RunTransportModel {
    private final static Logger logger = LogManager.getLogger(RunExposureHealthOffline.class);

    public static void main(String[] args) throws IOException {

        Properties properties = SiloUtil.siloInitialization(args[0]);

        // todo: check if that is good practice/ necessary to run the accident model, but need to make sure there are no implications elsewhere
        Resources.initializeResources(properties.transportModel.mitoPropertiesPath);

        Config config = null;
        if (args.length > 1 && args[1] != null) {
            config = ConfigUtils.loadConfig(args[1]);
        }
        logger.info("Started SILO land use model for Greater Melbourne");
        HealthDataContainerImpl dataContainer = DataBuilderHealth.getModelDataForMelbourne(properties, config);
        DataBuilderHealth.read(properties, dataContainer, config);

        // setup
        TransportModel transportModel;
        MatsimData matsimData = null;

        if (config != null) {
            final Scenario scenario = ScenarioUtils.loadScenario(config);
            matsimData = new MatsimData(config, properties, ZoneConnectorManager.ZoneConnectorMethod.RANDOM, dataContainer, scenario.getNetwork());
        }

        MatsimScenarioAssembler delegate = new MitoMatsimScenarioAssemblerMEL(dataContainer, properties, new MitoDataConverterMEL());
        transportModel = new MatsimTransportModelMELHealth(dataContainer, config, properties, delegate, matsimData, SiloUtil.provideNewRandom());

        // run
        transportModel.endYear(2018);

        //dataContainer.endSimulation();
        logger.info("Finished transport model for Greater Melbourne.");
    }
}
