package de.tum.bgu.msm.health;

import cern.colt.map.tfloat.OpenIntFloatHashMap;
import de.tum.bgu.msm.data.Zone;
import de.tum.bgu.msm.health.airPollutant.AirPollutantModel;
import de.tum.bgu.msm.health.data.DataContainerHealth;
import de.tum.bgu.msm.health.data.LinkInfo;
import de.tum.bgu.msm.health.data.ReceiverPointInfo;
import de.tum.bgu.msm.health.noise.NoiseModel;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.utils.SiloUtil;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.emissions.Pollutant;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

import static de.tum.bgu.msm.utils.SiloUtil.*;

/**
 * Implements SILO for the Greater Manchester
 *
 * @author Qin Zhang*/


public class RunExposureHealthOffline {

    private final static Logger logger = LogManager.getLogger(RunExposureHealthOffline.class);

    public static void main(String[] args) {

        Properties properties = SiloUtil.siloInitialization(args[0]);

        Config config = null;
        if (args.length > 1 && args[1] != null) {
            config = ConfigUtils.loadConfig(args[1]);
        }
        logger.info("Started SILO land use model for the Greater Manchester");
        HealthDataContainerImpl dataContainer = DataBuilderHealth.getModelDataForManchester(properties, config);
        DataBuilderHealth.read(properties, dataContainer);

        AirPollutantModel airPollutantModel = new AirPollutantModel(dataContainer, properties, SiloUtil.provideNewRandom(),config);
        NoiseModel noiseModel = new NoiseModel(dataContainer,properties, SiloUtil.provideNewRandom(),config);
        SportPAModelMCR sportPAModelMCR = new SportPAModelMCR(dataContainer, properties, SiloUtil.provideNewRandom());
        HealthExposureModelMCR exposureModelMCR = new HealthExposureModelMCR(dataContainer, properties, SiloUtil.provideNewRandom(),config);
        DiseaseModelMCR diseaseModelMCR = new DiseaseModelMCR(dataContainer, properties, SiloUtil.provideNewRandom());

        Scenario scenario = ScenarioUtils.createMutableScenario(config);
        //need to use full network (include all car, active mode links) for dispersion
        String networkFile = properties.main.baseDirectory + properties.healthData.network_for_airPollutant_model;
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkFile);
        Map<Id<Link>, LinkInfo> linkInfoMap = new HashMap<>();
        for(Link link : scenario.getNetwork().getLinks().values()){
            linkInfoMap.put(link.getId(), new LinkInfo(link.getId()));
        }
        dataContainer.setLinkInfo(linkInfoMap);
        logger.info("Initialized Link Info for " + dataContainer.getLinkInfo().size() + " links ");

        Map<String, ReceiverPointInfo> receiverPointInfo = new HashMap<>();
        for(Zone zone : dataContainer.getGeoData().getZones().values()){
            receiverPointInfo.put("zone"+zone.getId(),new ReceiverPointInfo("zone"+zone.getId(),zone.getPopCentroidCoord()));
        }
        dataContainer.setReceiverPointInfo(receiverPointInfo);

        //airPollutantModel.endYear(2021);
        //noiseModel.endYear(2021);
        sportPAModelMCR.endYear(2021);
        exposureModelMCR.endYear(2021);
        diseaseModelMCR.setup();
        diseaseModelMCR.endYear(2021);
        dataContainer.endSimulation();

        logger.info("Finished SILO.");
    }
}
