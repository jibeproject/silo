package de.tum.bgu.msm.util;

import de.tum.bgu.msm.data.jobTypes.JobTypeFactory;
import uk.cam.mrc.phm.jobTypes.MelbourneJobTypeFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class MelbourneImplementationConfig implements ImplementationConfig {

    private static final Logger logger = LogManager.getLogger(MelbourneImplementationConfig.class);
    private final static MelbourneImplementationConfig instance = new MelbourneImplementationConfig();

    private MelbourneImplementationConfig() {}

    public static MelbourneImplementationConfig get() {
        return instance;
    }

    @Override
    public JobTypeFactory getJobTypeFactory() {
        return new MelbourneJobTypeFactory();
    }

    public static Properties getMelbourneProperties() {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream("./project.properties")) {
            properties.load(input);
        } catch (IOException e) {
            logger.warn("Could not load project.properties file: {}. Loading test properties instead.", e.getMessage());
            return getTestProperties();
        }
        return properties;
    }

    public static Properties getMitoBaseProperties() {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream("./mito7daysMEL_reference.properties")) {
            properties.load(input);
        } catch (IOException e) {
            logger.warn("Could not load mito7daysMEL_reference.properties file: {}. Loading test properties instead.", e.getMessage());
            return getTestProperties();
        }
        return properties;
    }

    private static Properties getTestProperties() {
        Properties testProperties = new Properties();

        // Base year for analysis (eg for synthetic population)
        testProperties.setProperty("base_year", "2018");

        // Project coordinate reference system (GDA94 MGA Zone 55; as used in network and other JIBE Melbourne resources to date)
        testProperties.setProperty("crs", "28355");

        // Zone identifiers
        testProperties.setProperty("zone.id.field", "SA1_7DIG16");
        testProperties.setProperty("zone.urban.type.field", "UrbanType");
        testProperties.setProperty("zone.catchment.id.field", "SA2_MAIN16");
        testProperties.setProperty("zone.socioeconomic.disadvantage.deciles.field", "SEIFA_IRSD_DECILE_2016");
        testProperties.setProperty("zone.pop.centroid.x.field", "pwc_x_epsg_28355");
        testProperties.setProperty("zone.pop.centroid.y.field", "pwc_y_epsg_28355");

        // additional MITO variables
        testProperties.setProperty("ACTIVE_SPEEDS", "input/mito/maxSpeeds.csv");
        testProperties.setProperty("MATSIM_NETWORK", "input/mito/trafficAssignment/network.xml");
        testProperties.setProperty("MATSIM_PLAN", "scenOutput/base/2018/matsimPlans_thursday.xml.gz");
        testProperties.setProperty("MC_COEFFICIENTS", "input/mito/modeChoice/mc_coefficients");
        testProperties.setProperty("trip.purposes", "HBW,HBE,HBS,HBO,HBR,RRT,NHBW,NHBO,HBA");
        // additional MATsim configuration
        testProperties.setProperty("run.matsim.days.parallel", "false");

        return testProperties;
    }
}
