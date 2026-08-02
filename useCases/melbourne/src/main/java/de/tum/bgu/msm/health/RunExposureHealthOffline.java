package de.tum.bgu.msm.health;

import de.tum.bgu.msm.health.airPollutant.AirPollutantModel;
import de.tum.bgu.msm.health.noise.NoiseModel;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.resources.Resources;
import de.tum.bgu.msm.utils.SiloUtil;
import org.apache.logging.log4j.Level;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Implements SILO for Greater Melbourne
 *
 * @author Qin Zhang
 * @author Carl Higgs
 **/


public class RunExposureHealthOffline {

    private final static Logger logger = LogManager.getLogger(RunExposureHealthOffline.class);

    public static void main(String[] args) throws IOException {
        SiloUtil.captureLog(Level.INFO, "Started SILO offline health exposure model for Greater Melbourne");
        SiloUtil.captureLog(Level.INFO, "Scenario properties: " + args[0]);
        Properties properties = SiloUtil.siloInitialization(args[0]);

        Resources.initializeResources(properties.transportModel.mitoPropertiesPath);

        Config config = null;
        if (args.length > 1 && args[1] != null) {
            config = ConfigUtils.loadConfig(args[1]);
        }
        int endYear = properties.main.endYear;
        HealthDataContainerImpl dataContainer = DataBuilderHealth.getModelDataForMelbourne(properties, config);
        DataBuilderHealth.read(properties, dataContainer, config);

        // setup
        AirPollutantModel airPollutantModel = null;
        NoiseModel noiseModel = null;
        final String outputDirectory = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName + "/";
        String day = "thursday";
        // Both concentration files are written together by PollutantConcentrationWriter, so both must
        // be present and current before the air pollutant model can be skipped.
        String[] airPollutionFileCheckPaths = {
                outputDirectory + "linkConcentration_" + day + ".csv",
                outputDirectory + "locationConcentration_" + day + ".csv"};
        String noiseFileCheckPath = outputDirectory + "matsim/" + endYear + "/" + day + "/car/noise-analysis/receiverPoints/receiverPoints.csv";

        // Concentrations are keyed by MATSim link id and by activity location id. Link ids come from
        // the network, but activity location ids ("dd*", "job*", "ss*") are renumbered whenever the
        // synthetic population is rebuilt, and link emissions change whenever MATSim is re-run. Cached
        // concentrations older than either input therefore describe a population that no longer exists.
        String[] airPollutionInputPaths = {
                properties.main.baseDirectory + properties.realEstate.dwellingsFileName + "_" + properties.main.startYear + ".csv",
                outputDirectory + "matsim/" + endYear + "/" + day + "/car/" + endYear + ".output_events.xml.gz"};

        // Air pollution and noise outputs are only consumed by the exposure model's event
        // processing. When a base exposure file is supplied (and the end year is not an
        // exposure model year), exposures are read from that file instead, so both models
        // can be skipped regardless of whether their outputs exist on this machine.
        boolean exposureProcessingNeeded =
                (properties.healthData.baseExposureFile == null && endYear == properties.main.startYear)
                || properties.healthData.exposureModelYears.contains(endYear);
        if (!exposureProcessingNeeded) {
            logger.warn("Base exposure file supplied ({}); exposure event processing, air pollution and noise modelling will be skipped.",
                    properties.healthData.baseExposureFile);
        } else if (OutputsAreCurrent(airPollutionFileCheckPaths, airPollutionInputPaths)) {
            logger.warn("Air pollution output exists and is newer than its inputs ({}). Air pollution modelling will be skipped. Please delete existing results to re-run.",
                    String.join(", ", airPollutionFileCheckPaths));
        } else {
            airPollutantModel = new AirPollutantModel(dataContainer, properties, SiloUtil.provideNewRandom(), config);
        }
        if (!exposureProcessingNeeded) {
            // noise model skipped along with air pollution (see warning above)
        } else if (OutputFileExists(noiseFileCheckPath)) {
            logger.warn("Noise output exists ({}). Noise modelling will be skipped. Please delete existing results to re-run.",noiseFileCheckPath);
        } else {
            noiseModel = new NoiseModel(dataContainer, properties, SiloUtil.provideNewRandom(), config);
        }
        
        AccidentModelMEL accidentModel = new AccidentModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());
        HealthExposureModelMEL exposureModelMEL = new HealthExposureModelMEL(dataContainer, properties, SiloUtil.provideNewRandom(),config);
        SportPAModelMEL sportPAModelMEL = new SportPAModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());
        DiseaseModelMEL diseaseModelMEL = new DiseaseModelMEL(dataContainer, properties, SiloUtil.provideNewRandom());

        // runs (models are only constructed above when they actually need to run)
        if (airPollutantModel != null) {
            airPollutantModel.endYear(endYear);
        }
        if (noiseModel != null) {
            noiseModel.endYear(endYear);
        }
        accidentModel.endYear(endYear);
        exposureModelMEL.setup(); // read-in the exposure file
        exposureModelMEL.endYear(endYear);
        // Sport PA is cheap and independent of the MATSim-based exposure processing, so
        // always recompute it here. Calling endYear() instead would skip it whenever a
        // base exposure file is supplied (same gate as the exposure model), preventing
        // re-runs with updated sportPAmodel coefficients from taking effect.
        sportPAModelMEL.updateSportPA();
        diseaseModelMEL.setup();
        diseaseModelMEL.endYear(endYear);
        dataContainer.endSimulation();

        logger.info("Finished SILO.");
    }
    
    public static boolean OutputFileExists(String path) {
        return Files.exists(Paths.get(path));
    }

    /**
     * True when every cached output exists and none is older than any of the inputs it was derived
     * from, i.e. when the cached results can safely be reused instead of regenerated. Missing inputs
     * are ignored so that a run is never blocked by a path that does not apply to this scenario.
     */
    public static boolean OutputsAreCurrent(String[] outputPaths, String[] inputPaths) {
        long newestInput = Long.MIN_VALUE;
        for (String inputPath : inputPaths) {
            Path input = Paths.get(inputPath);
            if (!Files.exists(input)) {
                logger.warn("Cannot check whether cached results are up to date: input {} not found.", inputPath);
                continue;
            }
            try {
                newestInput = Math.max(newestInput, Files.getLastModifiedTime(input).toMillis());
            } catch (IOException e) {
                logger.warn("Cannot read modification time of input {}; assuming cached results are stale.", inputPath);
                return false;
            }
        }

        for (String outputPath : outputPaths) {
            Path output = Paths.get(outputPath);
            if (!Files.exists(output)) {
                logger.warn("Cached result {} is missing; it will be regenerated.", outputPath);
                return false;
            }
            try {
                if (Files.getLastModifiedTime(output).toMillis() < newestInput) {
                    logger.warn("Cached result {} is older than its inputs and will be regenerated. "
                            + "Reusing it would apply results computed for a superseded population or network.", outputPath);
                    return false;
                }
            } catch (IOException e) {
                logger.warn("Cannot read modification time of {}; it will be regenerated.", outputPath);
                return false;
            }
        }
        return true;
    }
}