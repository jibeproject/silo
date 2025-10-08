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
        logger.info("Reading noise level imissions data from csv files: {}", path);

        NoiseDataReader.FileProcessor fileProcessor = new NoiseDataReader.FileProcessor() {
            @Override
            public int processFile(String filePath, int hourBinZeroBased) {
                return processNoiseFile(dataContainer, filePath, hourBinZeroBased);
            }
        };

        NoiseDataReader.readNoiseFilesForDay(path, "immission", fileProcessor);
    }

    private int processNoiseFile(DataContainerHealth dataContainer, String filePath, int hourBinZeroBased) {
        return NoiseDataReader.readNoiseFile(filePath, 0, 1, (receiverPointId, noiseLevel) -> {
            if (dataContainer.getActivityLocations().get(receiverPointId) == null) {
                logger.error("Receiver point " + receiverPointId + " does not exist in receiver point container.");
                return;
            }

            // Store non-negative noise level values
            dataContainer.getActivityLocations().get(receiverPointId).getNoiseLevel2TimeBin()
                    .put(hourBinZeroBased, Math.max(0, noiseLevel));
        });
    }
}
