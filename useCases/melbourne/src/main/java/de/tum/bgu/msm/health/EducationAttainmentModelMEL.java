package de.tum.bgu.msm.health;

import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.EducationLevel;
import de.tum.bgu.msm.data.ZoneMEL;
import de.tum.bgu.msm.data.dwelling.Dwelling;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.models.AbstractModel;
import de.tum.bgu.msm.models.ModelUpdateListener;
import de.tum.bgu.msm.properties.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Assigns an educational attainment level to persons who reach the attainment age during the
 * simulation, drawing from the base-year distribution of their SA2 and sex.
 *
 * Persons born in-simulation start at {@link EducationLevel#no}. Asserting a level for them at
 * birth would be wrong in both available directions: inheriting the mother's implies perfect
 * intergenerational transmission, and a fixed default removes within-cohort variation. Leaving
 * them unassigned is instead exactly correct until age {@value DeathStrategyMEL#ATTAINMENT_AGE},
 * because the mortality disaggregation factors are 1 below that age, so every education level
 * yields the same rate and the fallback in {@link DeathStrategyMEL} returns it.
 *
 * Drawing from the SA2 distribution (rather than a Melbourne-wide marginal) preserves the
 * within-area education mix that the disaggregation factors normalise against, so aggregate
 * mortality continues to reproduce the life table as cohorts turn over. It assumes the base-year
 * education distribution of 25-34 year olds persists, i.e. no further educational expansion over
 * the simulated horizon.
 *
 * Persons already at or above the attainment age in the base year with no education are treated
 * as data gaps, not as unattained: they are never imputed, so they keep falling back to
 * un-disaggregated rates and stay visible in the {@link DeathStrategyMEL} tally.
 */
public class EducationAttainmentModelMEL extends AbstractModel implements ModelUpdateListener {

    private static final Logger logger = LogManager.getLogger(EducationAttainmentModelMEL.class);

    /** Width of the base-year cohort used as the reference distribution for new attainers. */
    private static final int REFERENCE_WINDOW = 10;

    private static final EducationLevel[] LEVELS =
            {EducationLevel.low, EducationLevel.medium, EducationLevel.high};

    private final Map<String, EnumMap<Gender, int[]>> countsByLocation = new HashMap<>();
    private final EnumMap<Gender, int[]> countsByGender = new EnumMap<>(Gender.class);
    private final int[] countsOverall = new int[LEVELS.length];

    /** Persons already of attainment age in the base year but with no education: data gaps. */
    private final Set<Integer> unresolvedAtBaseYear = new HashSet<>();

    private int assignedThisYear;
    private int assignedTotal;

    public EducationAttainmentModelMEL(DataContainer dataContainer, Properties properties, Random random) {
        super(dataContainer, properties, random);
    }

    @Override
    public void setup() {
        for (Person person : dataContainer.getHouseholdDataManager().getPersons()) {
            EducationLevel education = ((PersonHealthMEL) person).getEducationLevel();
            int age = person.getAge();

            if (education == EducationLevel.no) {
                if (age >= DeathStrategyMEL.ATTAINMENT_AGE) {
                    unresolvedAtBaseYear.add(person.getId());
                }
                continue;
            }

            if (age < DeathStrategyMEL.ATTAINMENT_AGE
                    || age >= DeathStrategyMEL.ATTAINMENT_AGE + REFERENCE_WINDOW) {
                continue;
            }
            int index = indexOf(education);
            if (index < 0) {
                continue;
            }
            String location = locationOf(person);
            if (location != null) {
                countsByLocation
                        .computeIfAbsent(location, k -> new EnumMap<>(Gender.class))
                        .computeIfAbsent(person.getGender(), k -> new int[LEVELS.length])[index]++;
            }
            countsByGender.computeIfAbsent(person.getGender(), k -> new int[LEVELS.length])[index]++;
            countsOverall[index]++;
        }

        if (total(countsOverall) == 0) {
            logger.warn("No base-year education data found; persons reaching age {} will keep "
                            + "un-disaggregated mortality rates.", DeathStrategyMEL.ATTAINMENT_AGE);
        } else {
            logger.info("Education attainment reference distribution built from {} persons aged {}-{} "
                            + "across {} locations.", total(countsOverall),
                    DeathStrategyMEL.ATTAINMENT_AGE,
                    DeathStrategyMEL.ATTAINMENT_AGE + REFERENCE_WINDOW - 1, countsByLocation.size());
        }
        if (!unresolvedAtBaseYear.isEmpty()) {
            logger.warn("{} persons were already aged {}+ in the base year with no education data. "
                            + "These are treated as data gaps and will not be imputed.",
                    unresolvedAtBaseYear.size(), DeathStrategyMEL.ATTAINMENT_AGE);
        }
    }

    @Override
    public void prepareYear(int year) {
        assignedThisYear = 0;
        if (total(countsOverall) == 0) {
            return;
        }
        for (Person person : dataContainer.getHouseholdDataManager().getPersons()) {
            PersonHealthMEL pp = (PersonHealthMEL) person;
            if (pp.getEducationLevel() != EducationLevel.no
                    || pp.getAge() < DeathStrategyMEL.ATTAINMENT_AGE
                    || unresolvedAtBaseYear.contains(pp.getId())) {
                continue;
            }
            pp.setEducationLevel(draw(referenceCounts(pp)));
            assignedThisYear++;
        }
        assignedTotal += assignedThisYear;
        if (assignedThisYear > 0) {
            logger.info("Education attainment {}: assigned a level to {} persons reaching age {} "
                    + "({} in total so far).", year, assignedThisYear,
                    DeathStrategyMEL.ATTAINMENT_AGE, assignedTotal);
        }
    }

    @Override
    public void endYear(int year) {
    }

    @Override
    public void endSimulation() {
    }

    /** Most specific reference distribution available: SA2 and sex, then sex, then region-wide. */
    private int[] referenceCounts(Person person) {
        String location = locationOf(person);
        if (location != null) {
            EnumMap<Gender, int[]> byGender = countsByLocation.get(location);
            if (byGender != null) {
                int[] counts = byGender.get(person.getGender());
                if (counts != null && total(counts) > 0) {
                    return counts;
                }
            }
        }
        int[] counts = countsByGender.get(person.getGender());
        return counts != null && total(counts) > 0 ? counts : countsOverall;
    }

    private EducationLevel draw(int[] counts) {
        int draw = random.nextInt(total(counts));
        int cumulative = 0;
        for (int i = 0; i < LEVELS.length; i++) {
            cumulative += counts[i];
            if (draw < cumulative) {
                return LEVELS[i];
            }
        }
        return LEVELS[LEVELS.length - 1];
    }

    private String locationOf(Person person) {
        if (person.getHousehold() == null) {
            return null;
        }
        Dwelling dwelling = dataContainer.getRealEstateDataManager()
                .getDwelling(person.getHousehold().getDwellingId());
        if (dwelling == null) {
            return null;
        }
        Object zone = dataContainer.getGeoData().getZones().get(dwelling.getZoneId());
        return zone instanceof ZoneMEL ? ((ZoneMEL) zone).getCatchmentCode() : null;
    }

    private static int indexOf(EducationLevel education) {
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i] == education) {
                return i;
            }
        }
        return -1;
    }

    private static int total(int[] counts) {
        return counts[0] + counts[1] + counts[2];
    }
}
