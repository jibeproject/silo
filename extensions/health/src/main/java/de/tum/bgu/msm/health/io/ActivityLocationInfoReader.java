package de.tum.bgu.msm.health.io;

import cern.colt.map.tfloat.OpenIntFloatHashMap;
import de.tum.bgu.msm.health.data.DataContainerHealth;
import de.tum.bgu.msm.health.data.ActivityLocation;
import de.tum.bgu.msm.utils.SiloUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.emissions.Pollutant;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;

public class ActivityLocationInfoReader {

    private final static Logger logger = LogManager.getLogger(ActivityLocationInfoReader.class);

    public void readConcentrationData(DataContainerHealth dataContainer, String path){

        logger.info("Reading location concentration data from csv file");

        String recString = "";
        int recCount = 0;
        try {
            BufferedReader in = new BufferedReader(new FileReader(path));
            recString = in.readLine();

            // read header
            String[] header = recString.split(",");
            int posId = SiloUtil.findPositionInArray("id", header);
            int posPollutant = SiloUtil.findPositionInArray("pollutant", header);
            int posTimebin = SiloUtil.findPositionInArray("timebin", header);
            int posValue = SiloUtil.findPositionInArray("value", header);

            // read line
            while ((recString = in.readLine()) != null) {
                recCount++;
                String[] lineElements = recString.split(",");
                String locationId = lineElements[posId];
                Pollutant pollutant  = Pollutant.valueOf(lineElements[posPollutant]);
                int startTime = Integer.parseInt(lineElements[posTimebin]);
                float value = Float.parseFloat(lineElements[posValue]);

                if (dataContainer.getActivityLocations().get(locationId)==null){
                    logger.error("Location " + locationId + " does not exist in activity location container.");
                }

                Map<Pollutant, OpenIntFloatHashMap> exposure2Pollutant2TimeBin =  dataContainer.getActivityLocations().get(locationId).getExposure2Pollutant2TimeBin();
                if(exposure2Pollutant2TimeBin.get(pollutant)==null){
                    OpenIntFloatHashMap exposureByTimeBin = new OpenIntFloatHashMap();
                    exposureByTimeBin.put(startTime/3600, value);
                    exposure2Pollutant2TimeBin.put(pollutant, exposureByTimeBin);
                }else {
                    float oldValue = exposure2Pollutant2TimeBin.get(pollutant).get(startTime/3600);
                    exposure2Pollutant2TimeBin.get(pollutant).put(startTime/3600, oldValue + value);
                }
            }
        } catch (IOException e) {
            logger.fatal("IO Exception caught reading location concentration file: " + path);
            logger.fatal("recCount = " + recCount + ", recString = <" + recString + ">");
        }
        logger.info("Finished reading " + recCount + " locations with concentration.");
    }

    public void readNoiseLevelData(DataContainerHealth dataContainer, String path) {
        logger.info("Reading noise level data from csv files");

        int hourlyBins = 24;
        int secondsPerHour = 3600;
        int totalPointsRead = 0;

        for (int hourBin = 1; hourBin <= hourlyBins; hourBin++) {
            double timeInSeconds = hourBin * secondsPerHour;
            String fileName = path + "immission" + "_" + timeInSeconds + ".csv";
            int pointsReadInBin = readNoiseFileForTimeBin(dataContainer, fileName, hourBin);
            totalPointsRead += pointsReadInBin;
        }

        logger.info("Completed reading noise data from " + hourlyBins + " files with a total of " +
                    totalPointsRead + " receiver points.");
    }

    private int readNoiseFileForTimeBin(DataContainerHealth dataContainer, String fileName, int hourBin) {
        String recString = "";
        int recCount = 0;

        try {
            BufferedReader in = new BufferedReader(new FileReader(fileName));
            recString = in.readLine();

            // read header
            String[] header = recString.split(";");
            int posRpId = 0;
            int posValue = 1;

            // read line
            while ((recString = in.readLine()) != null) {
                recCount++;
                String[] lineElements = recString.split(";");
                String receiverPointId = lineElements[posRpId];
                float noiseLevel = Float.parseFloat(lineElements[posValue]);

                if (dataContainer.getActivityLocations().get(receiverPointId) == null) {
                    logger.error("Receiver point " + receiverPointId + " does not exist in receiver point container.");
                    continue;
                }

                // Store non-negative noise level values
                dataContainer.getActivityLocations().get(receiverPointId).getNoiseLevel2TimeBin()
                        .put(hourBin - 1, Math.max(0, noiseLevel));
            }
        } catch (IOException e) {
            logger.error("Failed to read noise data file: " + fileName, e);
            return 0;
        }

        return recCount;
    }
}
