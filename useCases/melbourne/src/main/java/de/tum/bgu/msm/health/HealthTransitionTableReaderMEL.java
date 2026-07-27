package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.EducationLevel;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.health.disease.Diseases;
import de.tum.bgu.msm.utils.SiloUtil;
import uk.cam.mrc.phm.util.parseMEL;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import static uk.cam.mrc.phm.util.MelbourneImplementationConfig.getMelbourneProperties;

public class HealthTransitionTableReaderMEL {

    private final static Logger logger = LogManager.getLogger(de.tum.bgu.msm.health.io.HealthTransitionTableReader.class);
    static java.util.Properties properties = getMelbourneProperties();
    private final String CATCHMENT_ID_COLUMN = properties.getProperty("zone.catchment.id.field");
    public EnumMap<Diseases, Map<String, Double>> readData(HealthDataContainerImpl dataContainer, String path) {
        logger.info("Reading health disease prob table from csv file: {}", path);

        EnumMap<Diseases, Map<String, Double>> healthDiseaseData = new EnumMap<>(Diseases.class);
        Set<String> diseasesNotInLookup = new HashSet<>();
        Set<String> educationNotInLookup = new HashSet<>();
        String recString = "";
        int recCount = 0;
        int educationKeyed = 0;
        try {
            BufferedReader in = new BufferedReader(new FileReader(path));
            recString = in.readLine();

            // read header
            String[] header = parseMEL.stringParse(recString.split(","));
            int posAge = SiloUtil.findPositionInArray("age", header);
            int posGender= SiloUtil.findPositionInArray("sex", header);
            int posLocation = SiloUtil.findPositionInArray(CATCHMENT_ID_COLUMN, header);
            int posCause= SiloUtil.findPositionInArray("cause", header);
            int posProb= SiloUtil.findPositionInArray("prob", header);
            // optional: present only once the transition table is disaggregated by education.
            // Probed quietly, since findPositionInArray logs an error for absent columns.
            int posEducation = findOptionalColumn("education", header);
            if (posEducation < 0) {
                posEducation = findOptionalColumn("educ", header); // name used in the R pipeline
            }

            // read line
            while ((recString = in.readLine()) != null) {
                recCount++;
                String[] lineElements = parseMEL.stringParse(recString.split(","));
                int age = Integer.parseInt(lineElements[posAge]);
                Gender gender = Gender.valueOf(Integer.parseInt(lineElements[posGender]));
                String location = lineElements[posLocation];
                String disease = lineElements[posCause];
                Diseases diseases;
                try {
                    diseases = Diseases.valueOf(disease);
                } catch (IllegalArgumentException e) {
                    diseasesNotInLookup.add(disease);
                    continue; // Skip to next line if disease not in lookup
                }
                double prob = Double.parseDouble(lineElements[posProb]);

                // Rows carrying an education value are keyed with it (all-cause mortality);
                // rows without one keep the age|gender|location key (disease incidence).
                String compositeKey;
                // split() drops trailing empty fields, so guard the index as well
                String educationString = (posEducation < 0 || posEducation >= lineElements.length)
                        ? "" : lineElements[posEducation];
                if (educationString == null || educationString.isEmpty()
                        || "NA".equalsIgnoreCase(educationString)) {
                    compositeKey = dataContainer.createTransitionLookupIndex(age, gender, location);
                } else {
                    try {
                        compositeKey = dataContainer.createTransitionLookupIndex(age, gender, location,
                                EducationLevel.valueOf(educationString));
                        educationKeyed++;
                    } catch (IllegalArgumentException e) {
                        educationNotInLookup.add(educationString);
                        continue;
                    }
                }

                healthDiseaseData.computeIfAbsent(diseases, k -> new HashMap<>()).put(compositeKey, prob);

            }
        } catch (IOException e) {
            logger.fatal("IO Exception caught reading health disease prob file: {}", path);
            logger.fatal("recCount = {}, recString = <{}>", recCount, recString);
        } catch (IllegalArgumentException e){
            logger.warn(e.getMessage());
        }
        if (!diseasesNotInLookup.isEmpty()) {
            logger.warn("Diseases not present in lookup: {}", diseasesNotInLookup);
        }
        if (!educationNotInLookup.isEmpty()) {
            logger.warn("Education levels not present in lookup (rows skipped): {}", educationNotInLookup);
        }
        if (educationKeyed > 0) {
            logger.info("{} of {} transition rows are disaggregated by education.", educationKeyed, recCount);
        } else {
            logger.info("Transition table carries no education column; mortality will use "
                    + "un-disaggregated rates.");
        }
        logger.info("Finished reading health disease prob table from csv file.");
        return healthDiseaseData;
    }

    /**
     * Header lookup that returns -1 without logging, for columns that may legitimately be absent.
     */
    private static int findOptionalColumn(String name, String[] header) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
