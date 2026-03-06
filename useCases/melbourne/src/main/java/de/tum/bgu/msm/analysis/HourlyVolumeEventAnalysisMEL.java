package de.tum.bgu.msm.analysis;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Properties;

public class HourlyVolumeEventAnalysisMEL {
    /*
    Based on code by Qin Zhang (@Qinnnnn) from MITO:
    https://github.com/jibeproject/mito/blob/main/analysis/src/main/java/de/tum/bgu/msm/HourlyVolumeEventAnalysis.java
    Generalised for Melbourne use case.
    */
    private static final String MATSIM_NETWORK = "input/mito/trafficAssignment/network.xml";
    private static final String SCENARIO_NAME_PROPERTY = "scenario.name";
    private static final String YEAR = "2018";
    private static final int SCALE_FACTOR_ACTIVE = 1;
    private static final int SCALE_FACTOR_CAR = 10;
    private static final Logger logger = LogManager.getLogger(HourlyVolumeEventAnalysisMEL.class);


    private static String loadScenarioName(String propertiesFilePath) {
        Properties properties = new Properties();

        try (FileInputStream fis = new FileInputStream(propertiesFilePath)) {
            properties.load(fis);
            String scenarioName = properties.getProperty(SCENARIO_NAME_PROPERTY);

            if (scenarioName == null || scenarioName.trim().isEmpty()) {
                throw new IllegalArgumentException("Property '" + SCENARIO_NAME_PROPERTY + "' not found or empty in properties file");
            }

            return scenarioName.trim();

        } catch (IOException e) {
            throw new RuntimeException("Failed to load properties file: " + propertiesFilePath, e);
        }
    }

    private static void writeHourlyVolumes(Network network, HourlyVolumeEventHandler handler, String outputPath, String header, int scaleFactor, String[] volumeTypes) {
        try (PrintWriter pw = new PrintWriter(outputPath)) {
            pw.println(header);
            for (Link link : network.getLinks().values()) {
                String linkId = link.getId().toString();
                String edgeId = link.getAttributes().getAttribute("edgeID").toString();
                String osmId = link.getAttributes().getAttribute("osmID") == null ? "NA" : link.getAttributes().getAttribute("osmID").toString();
                for (int hour = 0; hour < 24; hour++) {
                    StringBuilder line = new StringBuilder();
                    line.append(linkId).append(",").append(edgeId).append(",").append(osmId).append(",").append(hour);
                    int totalVolume = 0;
                    for (String type : volumeTypes) {
                        int volume = 0;
                        if (type.equals("bike") && handler.getBikeVolumes().get(link.getId()) != null) {
                            volume = handler.getBikeVolumes().get(link.getId()).getOrDefault(hour, 0) * scaleFactor;
                        } else if (type.equals("ped") && handler.getPedVolumes().get(link.getId()) != null) {
                            volume = handler.getPedVolumes().get(link.getId()).getOrDefault(hour, 0) * scaleFactor;
                        } else if (type.equals("car") && handler.getCarVolumes().get(link.getId()) != null) {
                            volume = handler.getCarVolumes().get(link.getId()).getOrDefault(hour, 0) * scaleFactor;
                        } else if (type.equals("truck") && handler.getTruckVolumes().get(link.getId()) != null) {
                            volume = handler.getTruckVolumes().get(link.getId()).getOrDefault(hour, 0) * scaleFactor;
                        }
                        totalVolume += volume;
                        line.append(",").append(volume);
                    }
                    if (totalVolume > 0) {
                        pw.println(line);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            logger.warn("File not found: {}\n{}", outputPath, e);
        }
    }

    private static class ModeCategory {
        String subfolder;
        String outputSuffix;
        String[] volumeTypes;
        int scaleFactor;
        String header;
        ModeCategory(String subfolder, String[] volumeTypes, int scaleFactor, String header, String day) {
            this.subfolder = subfolder;
            this.outputSuffix = String.format("%s_%s.csv", this.subfolder, day);
            this.volumeTypes = volumeTypes;
            this.scaleFactor = scaleFactor;
            this.header = header;
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("Properties file path must be provided as first argument");
        }

        String scenarioName = loadScenarioName(args[0]);
        String scenarioPath = String.format("scenOutput/%s/matsim/%s", scenarioName, YEAR);

        logger.info("Running hourly volume analysis for scenario: {}", scenarioName);

        String[] days = {"thursday", "saturday", "sunday"};
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(MATSIM_NETWORK);

        for (String day : days) {
            ModeCategory[] modes = new ModeCategory[]{
                new ModeCategory("bikePed", new String[]{"bike", "ped"}, SCALE_FACTOR_ACTIVE, "linkId,edgeId,osmId,hour,bike,ped", day),
                new ModeCategory("car", new String[]{"car", "truck"}, SCALE_FACTOR_CAR, "linkId,edgeId,osmId,hour,car,truck", day)
            };
            for (ModeCategory mode : modes) {
                String eventPath = String.format("%s/%s/%s/%s.output_events.xml.gz", scenarioPath, day, mode.subfolder, YEAR);
                String vehiclesPath = String.format("%s/%s/%s/%s.output_vehicles.xml.gz", scenarioPath, day, mode.subfolder, YEAR);
                String outputPath = String.format("%s/hourlyVolume_%s", scenarioPath, mode.outputSuffix);

                Vehicles vehicles = VehicleUtils.createVehiclesContainer();
                new MatsimVehicleReader(vehicles).readFile(vehiclesPath);
                EventsManager eventsManager = new EventsManagerImpl();
                HourlyVolumeEventHandler handler = new HourlyVolumeEventHandler(vehicles);
                eventsManager.addHandler(handler);
                EventsUtils.readEvents(eventsManager, eventPath);
                writeHourlyVolumes(network, handler, outputPath, mode.header, mode.scaleFactor, mode.volumeTypes);
                logger.info("Hourly volume by {} written to {}", mode.subfolder, outputPath);
            }
        }
        logger.info("Hourly volume analysis completed.");
    }
}
