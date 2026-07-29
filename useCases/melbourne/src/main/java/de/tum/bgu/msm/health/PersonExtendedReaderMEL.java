package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.EducationLevel;
import de.tum.bgu.msm.data.household.HouseholdDataManager;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.utils.SiloUtil;
import uk.cam.mrc.phm.util.parseMEL;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Reads the Melbourne extended person microdata (pp_extended_&lt;year&gt;.csv), which carries
 * attributes keyed on person id that are not in the main person file. Currently only
 * education is read.
 *
 * Education feeds the education-disaggregated all-cause mortality lookup in
 * {@link DeathStrategyMEL}; it is deliberately not used by DiseaseModelMEL, whose incidence
 * rates are not disaggregated by education. Persons absent from this file keep
 * {@link EducationLevel#no} and fall back to the un-disaggregated rate.
 */
public class PersonExtendedReaderMEL {

    private final static Logger logger = LogManager.getLogger(PersonExtendedReaderMEL.class);
    private final HouseholdDataManager householdDataManager;

    public PersonExtendedReaderMEL(HouseholdDataManager householdDataManager) {
        this.householdDataManager = householdDataManager;
    }

    public void readData(String path) {
        logger.info("Reading extended person micro data from ascii file ({})", path);

        String recString = "";
        int recCount = 0;
        int assigned = 0;
        int notInPopulation = 0;
        int unparseable = 0;
        try {
            BufferedReader in = new BufferedReader(new FileReader(path));
            recString = in.readLine();

            String[] header = parseMEL.stringParse(recString.split(","));
            int posId = SiloUtil.findPositionInArray("id", header);
            int posEducation = SiloUtil.findPositionInArray("education", header);

            while ((recString = in.readLine()) != null) {
                recCount++;
                String[] lineElements = parseMEL.stringParse(recString).split(",");
                int id = parseMEL.intParse(lineElements[posId]);

                Person pp = householdDataManager.getPersonFromId(id);
                if (pp == null) {
                    // expected when the scenario uses a subset of the population (e.g. the
                    // 100-household test file) against the full extended microdata
                    notInPopulation++;
                    continue;
                }

                final String educationString = parseMEL.stringParse(lineElements[posEducation]);
                if (educationString == null || educationString.isEmpty()
                        || "NA".equalsIgnoreCase(educationString)) {
                    unparseable++;
                    continue;
                }
                try {
                    ((PersonHealthMEL) pp).setEducationLevel(EducationLevel.valueOf(educationString));
                    assigned++;
                } catch (IllegalArgumentException e) {
                    unparseable++;
                }
            }
        } catch (IOException e) {
            logger.fatal("IO Exception caught reading extended person file: " + path);
            logger.fatal("recCount = " + recCount + ", recString = <" + recString + ">");
        }

        int withoutEducation = 0;
        for (Person pp : householdDataManager.getPersons()) {
            if (((PersonHealthMEL) pp).getEducationLevel() == EducationLevel.no) {
                withoutEducation++;
            }
        }

        logger.info("Finished reading {} extended person records; education assigned to {} persons.",
                recCount, assigned);
        if (notInPopulation > 0) {
            logger.info("{} extended records refer to persons not in the simulated population.",
                    notInPopulation);
        }
        if (unparseable > 0) {
            logger.warn("{} extended records had a missing or unrecognised education value.",
                    unparseable);
        }
        if (withoutEducation > 0) {
            logger.warn("{} persons in the population have no education level and will use "
                    + "un-disaggregated mortality rates until they reach the attainment age.",
                    withoutEducation);
        }
    }
}
