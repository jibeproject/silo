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

    /**
     * Fraction of unmatched records above which the input is treated as belonging to a different
     * synthetic population vintage rather than as isolated data gaps. Activity location ids
     * ("dd*", "job*", "ss*") are renumbered whenever the synthetic population is rebuilt, so a
     * stale concentration file matches almost nothing and would otherwise assign every dwelling
     * the exposures of an unrelated location.
     */
    private final static double MAX_UNMATCHED_SHARE = 0.01;

    public void readConcentrationData(DataContainerHealth dataContainer, String path){

        logger.info("Reading location concentration data from csv file");

        String recString = "";
        int recCount = 0;
        int unmatchedCount = 0;
        String firstUnmatchedId = null;
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
                    if (firstUnmatchedId == null) {
                        firstUnmatchedId = locationId;
                    }
                    unmatchedCount++;
                    continue;
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
        reportUnmatched("location concentration", path, recCount, unmatchedCount, firstUnmatchedId);
        logger.info("Finished reading " + recCount + " locations with concentration.");
    }

    /**
     * Logs unmatched activity location ids as a single summary, and aborts when so few match that
     * the file cannot describe the current synthetic population. Reporting per record would emit
     * millions of lines, and continuing would produce plausible-looking but wrong exposures.
     */
    private void reportUnmatched(String dataDescription, String path, int recCount, int unmatchedCount,
                                 String firstUnmatchedId) {
        if (unmatchedCount == 0) {
            return;
        }
        double unmatchedShare = recCount == 0 ? 1d : (double) unmatchedCount / recCount;
        String message = String.format(
                "%,d of %,d %s records (%.1f%%) reference activity locations that do not exist, "
                        + "starting with '%s'. File: %s",
                unmatchedCount, recCount, dataDescription, unmatchedShare * 100, firstUnmatchedId, path);
        if (unmatchedShare > MAX_UNMATCHED_SHARE) {
            throw new IllegalStateException(message
                    + " -- this file was almost certainly generated from a different synthetic population."
                    + " Activity location ids are renumbered whenever the population is rebuilt, so delete"
                    + " this file and regenerate it against the current population before re-running.");
        }
        logger.warn(message);
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
        int[] unmatched = new int[1];
        String[] firstUnmatchedId = new String[1];

        int recCount = NoiseDataReader.readNoiseFile(filePath, 0, 1, (receiverPointId, noiseLevel) -> {
            if (dataContainer.getActivityLocations().get(receiverPointId) == null) {
                if (firstUnmatchedId[0] == null) {
                    firstUnmatchedId[0] = receiverPointId;
                }
                unmatched[0]++;
                return;
            }

            // Store non-negative noise level values
            dataContainer.getActivityLocations().get(receiverPointId).getNoiseLevel2TimeBin()
                    .put(hourBinZeroBased, Math.max(0, noiseLevel));
        });

        reportUnmatched("noise immission", filePath, recCount, unmatched[0], firstUnmatchedId[0]);
        return recCount;
    }
}
