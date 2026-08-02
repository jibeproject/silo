package de.tum.bgu.msm.health.io;

import cern.colt.map.tfloat.OpenIntFloatHashMap;
import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.health.data.DataContainerHealth;
import de.tum.bgu.msm.utils.SiloUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Envelope;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.analysis.vsp.qgis.*;
import org.matsim.contrib.emissions.Pollutant;
import org.matsim.contrib.noise.ReceiverPoint;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Time;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class LinkInfoReader {

    private final static Logger logger = LogManager.getLogger(LinkInfoReader.class);

    public void readConcentrationData(DataContainerHealth dataContainer, String path){

        logger.info("Reading link concentration data from csv file");

        String recString = "";
        int recCount = 0;
        int unmatchedCount = 0;
        String firstUnmatchedId = null;
        try {
            BufferedReader in = new BufferedReader(new FileReader(path));
            recString = in.readLine();

            // read header
            String[] header = recString.split(",");
            int posLinkId = SiloUtil.findPositionInArray("linkId", header);
            int posPollutant = SiloUtil.findPositionInArray("pollutant", header);
            int posTimebin = SiloUtil.findPositionInArray("timebin", header);
            int posValue = SiloUtil.findPositionInArray("value", header);

            // read line
            while ((recString = in.readLine()) != null) {
                recCount++;
                String[] lineElements = recString.split(",");
                Id<Link> linkId = Id.createLinkId(lineElements[posLinkId]);
                Pollutant pollutant  = Pollutant.valueOf(lineElements[posPollutant]);
                int startTime = Integer.parseInt(lineElements[posTimebin]);
                float value = Float.parseFloat(lineElements[posValue]);

                if (dataContainer.getLinkInfo().get(linkId)==null){
                    if (firstUnmatchedId == null) {
                        firstUnmatchedId = linkId.toString();
                    }
                    unmatchedCount++;
                    continue;
                }

                Map<Pollutant, OpenIntFloatHashMap> exposure2Pollutant2TimeBin =  dataContainer.getLinkInfo().get(linkId).getExposure2Pollutant2TimeBin();
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
            logger.fatal("IO Exception caught reading link concentration file: " + path);
            logger.fatal("recCount = " + recCount + ", recString = <" + recString + ">");
        }
        reportUnmatched("link concentration", path, recCount, unmatchedCount, firstUnmatchedId);
        logger.info("Finished reading " + recCount + " links with concentration.");
    }

    /**
     * Logs unmatched link ids as a single summary rather than one line per record. Link ids are
     * stable across synthetic population rebuilds, so a mismatch here means the file was written
     * against a different network.
     */
    private void reportUnmatched(String dataDescription, String path, int recCount, int unmatchedCount,
                                 String firstUnmatchedId) {
        if (unmatchedCount == 0) {
            return;
        }
        logger.warn(String.format(
                "%,d of %,d %s records (%.1f%%) reference links that do not exist in the Link Info "
                        + "container, starting with '%s'. This file may have been written against a "
                        + "different network. File: %s",
                unmatchedCount, recCount, dataDescription,
                (recCount == 0 ? 1d : (double) unmatchedCount / recCount) * 100, firstUnmatchedId, path));
    }

    public void readNoiseLevelData(DataContainerHealth dataContainer, String outputDirectory, Day day) {
        String path = outputDirectory + "/" + day + "/car/noise-analysis/emissions/";
        logger.info("Reading noise level emissions data from csv files: {}", path);

        NoiseDataReader.FileProcessor fileProcessor = new NoiseDataReader.FileProcessor() {
            @Override
            public int processFile(String filePath, int hourBinZeroBased) {
                return processNoiseFile(dataContainer, filePath, hourBinZeroBased);
            }
        };

        NoiseDataReader.readNoiseFilesForDay(path, "emission", fileProcessor);
    }

    private int processNoiseFile(DataContainerHealth dataContainer, String filePath, int hourBinZeroBased) {
        int[] unmatched = new int[1];
        String[] firstUnmatchedId = new String[1];

        int recCount = NoiseDataReader.readNoiseFile(filePath, 0, 1, (linkIdStr, noiseLevel) -> {
            Id<Link> linkId = Id.createLinkId(linkIdStr);

            if (dataContainer.getLinkInfo().get(linkId) == null) {
                if (firstUnmatchedId[0] == null) {
                    firstUnmatchedId[0] = linkIdStr;
                }
                unmatched[0]++;
                return;
            }

            dataContainer.getLinkInfo().get(linkId).getNoiseLevel2TimeBin().put(hourBinZeroBased, noiseLevel);
        });

        reportUnmatched("noise emission", filePath, recCount, unmatched[0], firstUnmatchedId[0]);
        return recCount;
    }
}
