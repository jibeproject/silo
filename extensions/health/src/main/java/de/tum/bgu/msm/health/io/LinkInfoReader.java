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
                    logger.error("Link " + linkId + " does not exist in Link Info container.");
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
        logger.info("Finished reading " + recCount + " links with concentration.");
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
        return NoiseDataReader.readNoiseFile(filePath, 0, 1, (linkIdStr, noiseLevel) -> {
            Id<Link> linkId = Id.createLinkId(linkIdStr);

            if (dataContainer.getLinkInfo().get(linkId) == null) {
                logger.warn("Link " + linkId + " does not exist in Link Info container.");
                return;
            }

            dataContainer.getLinkInfo().get(linkId).getNoiseLevel2TimeBin().put(hourBinZeroBased, noiseLevel);
        });
    }
}
