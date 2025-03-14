package de.tum.bgu.msm.health.noise;

import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.data.dwelling.RealEstateDataManager;
import de.tum.bgu.msm.models.AbstractModel;
import de.tum.bgu.msm.models.ModelUpdateListener;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.utils.SiloUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.noise.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class NoiseModel extends AbstractModel implements ModelUpdateListener {

    private static final Logger logger = LogManager.getLogger(NoiseModel.class);

    private final Config initialConfig;

    private final RealEstateDataManager realEstateDataManager;
    private int latestMatsimYear = -1;

    private final NoiseReceiverPoints noiseReceiverPoints;
    private List<Day> simulatedDays;
    public static final int NOISE_TIME_BIN_SIZE = 3600;


    public NoiseModel(DataContainer data, Properties properties, Random random, Config initialConfig) {
        super(data, properties, random);
        this.initialConfig = initialConfig;
        this.realEstateDataManager = data.getRealEstateDataManager();
        this.noiseReceiverPoints = new NoiseReceiverPoints();
        simulatedDays = Arrays.asList(Day.thursday,Day.saturday,Day.sunday);
    }

    @Override
    public void setup() {
    }

    @Override
    public void prepareYear(int year) {

    }

    @Override
    public void endYear(int year) {
        updateImmissions(year);
    }

    @Override
    public void endSimulation() {
    }

    private void updateImmissions(int year) {
        logger.info("Updating noise immisisons for year " + year + ".");

//        NoiseReceiverPoints newNoiseReceiverPoints = new NoiseReceiverPoints();
//        NoiseReceiverPoints existingNoiseReceiverPoints = new NoiseReceiverPoints();
//
//        for (Dwelling dwelling : realEstateDataManager.getDwellings()) {
//            Id<ReceiverPoint> id = Id.create(dwelling.getId(), ReceiverPoint.class);
//            final NoiseReceiverPoint existing = noiseReceiverPoints.remove(id);
//            if (existing == null) {
//                newNoiseReceiverPoints.put(id, new NoiseReceiverPoint(id, CoordUtils.createCoord(dwelling.getCoordinate())));
//            } else {
//                existingNoiseReceiverPoints.put(id, existing);
//            }
//        }
//
//        this.noiseReceiverPoints.clear();
//        this.noiseReceiverPoints.putAll(newNoiseReceiverPoints);
//        this.noiseReceiverPoints.putAll(existingNoiseReceiverPoints);

        //Temoparary
        if(properties.main.startYear == year) {
            latestMatsimYear = year;
            for(Day day : simulatedDays){
                logger.info("Updating noise immisisons for year " + year + " day " + day + ".");
                this.noiseReceiverPoints.clear();
                readBuidlingFile();
                calculateNoiseOffline(noiseReceiverPoints, day);
                writeNoiseImission(day);
            }
        }


        /*int counter65 = 0;
        int counter55 = 0;
        for (Dwelling dwelling : dataContainer.getRealEstateDataManager().getDwellings()) {
            final Id<ReceiverPoint> id = Id.create(dwelling.getId(), ReceiverPoint.class);
            if (noiseReceiverPoints.containsKey(id)) {
                double lden = noiseReceiverPoints.get(id).getLden();
                ((NoiseDwelling) dwelling).setNoiseImmision(lden);
                if (lden > 55) {
                    if (lden > 65) {
                        counter65++;
                    } else {
                        counter55++;
                    }
                }
            }
        }
        int total = dataContainer.getRealEstateDataManager().getDwellings().size();
        int quiet = total - counter55 - counter65;
        logger.info("Dwellings <55dB(A) : " + quiet + " (" + ((double) quiet) / total + "%)");
        logger.info("Dwellings 55dB(A)-65dB(A) : " + counter55 + " (" + ((double) counter55) / total + "%)");
        logger.info("Dwellings >65dB(A) : " + counter65 + " (" + ((double) counter65) / total + "%)");*/
    }




    private void calculateNoiseOffline(NoiseReceiverPoints noiseReceiverPoints, Day day) {
        final String outputDirectoryRoot = properties.main.baseDirectory + "scenOutput/"
                + properties.main.scenarioName + "/matsim/" + latestMatsimYear + "/" + day + "/car/";

        Config config = ConfigUtils.loadConfig(initialConfig.getContext());
        config.controller().setOutputDirectory(outputDirectoryRoot);
        config.controller().setRunId(String.valueOf(latestMatsimYear));
        Scenario scenario = ScenarioUtils.createMutableScenario(config);

        scenario = ScenarioUtils.loadScenario(scenario.getConfig());

        scenario.addScenarioElement(NoiseReceiverPoints.NOISE_RECEIVER_POINTS, noiseReceiverPoints);

        NoiseConfigGroup noiseParameters = ConfigUtils.addOrGetModule(initialConfig, NoiseConfigGroup.class);
        noiseParameters.setInternalizeNoiseDamages(false);
        noiseParameters.setComputeCausingAgents(false);
        noiseParameters.setComputeNoiseDamages(false);
        noiseParameters.setComputePopulationUnits(false);
        noiseParameters.setComputeAvgNoiseCostPerLinkAndTime(false);
        noiseParameters.setThrowNoiseEventsCaused(false);
        noiseParameters.setThrowNoiseEventsAffected(false);
        noiseParameters.setNoiseComputationMethod(NoiseConfigGroup.NoiseComputationMethod.RLS19);
        noiseParameters.setWriteOutputIteration(1);
        double scalingFactor = properties.healthData.matsim_scale_factor_car;
        noiseParameters.setScaleFactor(1./scalingFactor);

        config.qsim().setEndTime(24 * 60 * 60);
        noiseParameters.setConsiderNoiseBarriers(true);
        noiseParameters.setConsiderNoiseReflection(false);
        noiseParameters.setNoiseBarriersFilePath(properties.healthData.noiseBarriersFile);
        noiseParameters.setNoiseBarriersSourceCRS("EPSG:27700");
        config.global().setCoordinateSystem("EPSG:27700");
        config.addModule(noiseParameters);

        NoiseOfflineCalculation noiseOfflineCalculation = new NoiseOfflineCalculation(scenario, outputDirectoryRoot);
        noiseOfflineCalculation.run();

    }

    private void readBuidlingFile() {
        String fileName = properties.healthData.microBuildingFile;

        String recString = "";
        int recCount = 0;
        try {
            BufferedReader in = new BufferedReader(new FileReader(fileName));
            recString = in.readLine();

            // read header
            String[] header = recString.split(",");
            int posId = SiloUtil.findPositionInArray("index", header);
            int posCoordX = SiloUtil.findPositionInArray("coordX", header);
            int posCoordY = SiloUtil.findPositionInArray("coordY", header);

            // read line
            while ((recString = in.readLine()) != null) {
                    recCount++;
                    String[] lineElements = recString.split(",");
                    String index = lineElements[posId];
                    double xCoordinate = Double.parseDouble(lineElements[posCoordX]);
                    double yCoordinate = Double.parseDouble(lineElements[posCoordY]);

                    Id<ReceiverPoint> id = Id.create(index, ReceiverPoint.class);
                    noiseReceiverPoints.put(id, new NoiseReceiverPoint(id, CoordUtils.createCoord(xCoordinate, yCoordinate)));
            }

        } catch (IOException e) {
            logger.fatal("IO Exception caught reading dwelling file: " + fileName, new RuntimeException());
            logger.fatal("recCount = " + recCount + ", recString = <" + recString + ">", new RuntimeException());
        }
        logger.info("Finished reading " + recCount + " dwellings.");
    }

    private void writeNoiseImission(Day day) {
        int counter65 = 0;
        int counter55 = 0;

        final String outputDirectory = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName +"/";
        String path = outputDirectory
                + properties.realEstate.dwellingsFinalFileName
                + "_noise_"
                + day
                + "_2021"
                + ".csv";

        logger.info("  Writing noise imission file to " + path);
        PrintWriter pwd = SiloUtil.openFileForSequentialWriting(path, false);
        pwd.print("id,noise");
        pwd.println();



        for (NoiseReceiverPoint point : noiseReceiverPoints.values()) {
            double lden = point.getLden();
            pwd.print(point.getId());
            pwd.print(",");
            pwd.print(lden);
            pwd.println();

            if (lden > 55) {
                if (lden > 65) {
                    counter65++;
                } else {
                    counter55++;
                }
            }

        }

        pwd.close();

        int total = noiseReceiverPoints.size();
        int quiet = total - counter55 - counter65;
        logger.info("Dwellings <55dB(A) : " + quiet + " (" + ((double) quiet) / total + "%)");
        logger.info("Dwellings 55dB(A)-65dB(A) : " + counter55 + " (" + ((double) counter55) / total + "%)");
        logger.info("Dwellings >65dB(A) : " + counter65 + " (" + ((double) counter65) / total + "%)");


    }
}
