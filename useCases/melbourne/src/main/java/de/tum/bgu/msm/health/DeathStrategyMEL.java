package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.EducationLevel;
import de.tum.bgu.msm.data.ZoneMEL;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.health.data.PersonHealth;
import de.tum.bgu.msm.health.disease.Diseases;
import de.tum.bgu.msm.health.disease.HealthExposures;
import de.tum.bgu.msm.models.demography.death.DeathStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class DeathStrategyMEL implements DeathStrategy {

    private final static Logger logger = LogManager.getLogger(DeathStrategyMEL.class);

    /** Age at which EducationAttainmentModelMEL assigns a level to persons born in-simulation. */
    static final int ATTAINMENT_AGE = 25;
    /** Ages over which the transition table is disaggregated by education (factors are 1 outside). */
    private static final int DISAGG_MIN_AGE = 25;
    private static final int DISAGG_MAX_AGE = 84;

    private final HealthDataContainerImpl dataContainer;
    private final Boolean adjustByRelativeRisk;

    private long educationKeyedLookups;
    private long fallbackNoEducationData;
    private long fallbackNoEducationDataInBand;
    private long fallbackNotYetAttained;
    private long fallbackNoTransitionRow;
    private long fallbackNoTransitionRowInBand;

    public DeathStrategyMEL(HealthDataContainerImpl dataContainer, Boolean adjustByRelativeRisk) {
        this.dataContainer = dataContainer;
        this.adjustByRelativeRisk = adjustByRelativeRisk;
    }

    @Override
    public double calculateDeathProbability(Person person, Random random) {
        final int personAge = Math.min(person.getAge(), 100);
        Gender personSex = person.getGender();

        if (personAge < 0){
            throw new RuntimeException("Undefined negative person age: " + personAge);
        }

        //cap age at 100, over 100 all cause mortality prob = 1
        if (personAge >= 100){
            return 1.;
        }

        // check killed by injury
        Set<Diseases> killedInAccident = Set.of(
                Diseases.dead_car,
                Diseases.dead_bike,
                Diseases.dead_walk
        );

        if (!Collections.disjoint(((PersonHealth) person).getCurrentDisease(), killedInAccident)) {
            return 1.;
        }

        int zoneId = dataContainer.getRealEstateDataManager().getDwelling(person.getHousehold().getDwellingId()).getZoneId();
        String location = ((ZoneMEL)dataContainer.getGeoData().getZones().get(zoneId)).getCatchmentCode();

        double alpha = lookupMortalityRate(person, personAge, location);


        /*calculate odds: odd_1 = transition_raw/(1 - transition_raw). transition_raw = 1 - exp-(transition_raw-this is the data)
        multiply by relative risks: odd_2 = odd_1 * rr
        translate to probability = probability = odd_2/(1+odd_2)

         */


        //calculation of probabilities for mortality with first adjustment using rates*rr exposures/PA and then
        // adjusting probabilities (previous rate converted to probability) to odds ratios and multiplied by teh
        // disease rr and back to prob. I understand this was not done this way before.
        //no rr adjustment for age under 18
        if(personAge < 18){
            return alpha;
        }

        if(adjustByRelativeRisk){
            for(HealthExposures healthExposures : ((PersonHealth)person).getRelativeRisksByDisease().keySet()){
                alpha *= ((PersonHealth)person).getRelativeRisksByDisease().get(healthExposures).get(Diseases.all_cause_mortality);
            }
        }



        //alpha = alpha / (1 - alpha);

        // risk factors
        Set<Diseases> currentDiseases = new HashSet<>(((PersonHealth) person).getCurrentDisease());
        Set<Diseases> cancers = Set.of(
                Diseases.breast_cancer,
                Diseases.endometrial_cancer,
                Diseases.colon_cancer,
                Diseases.bladder_cancer,
                //Diseases.esophageal_cancer,
                //Diseases.gastric_cardia_cancer,
                Diseases.head_neck_cancer,
                //Diseases.liver_cancer,
                Diseases.lung_cancer
                //Diseases.rectum_cancer // removed: not in modelled disease scope
        );
        Set<Diseases> injuries = Set.of(
                Diseases.severely_injured_car,
                Diseases.severely_injured_bike,
                Diseases.severely_injured_walk
        );

        if (Collections.disjoint(currentDiseases, injuries)) {

            if (currentDiseases.size() == 1) {
                alpha *= 1.23;
            }
            if (currentDiseases.size() == 2) {
                alpha *= 1.62;
            }
            if (currentDiseases.size() == 3) {
                alpha *= 2.09;
            }
            if (currentDiseases.size() == 4) {
                alpha *= 2.77;
            }
            if (currentDiseases.size() == 5) {
                alpha *= 3.46;
            }
            if (currentDiseases.size() > 5) {
                alpha *= 5.14;
            }
        }

        if (!Collections.disjoint(currentDiseases, injuries)) {
            if (person.getGender().equals(Gender.MALE)) {
                alpha *= 1.71;
            } else {
                alpha *= 1.74;
            }
        }

        return  (1 - Math.exp(-alpha));

        //return alpha/(1+alpha);
    }

    /**
     * All-cause mortality rate for a person, preferring the education-disaggregated rate and
     * falling back to the un-disaggregated one.
     *
     * Falling back is correct, not merely tolerable, below {@link #DISAGG_MIN_AGE} and above
     * {@link #DISAGG_MAX_AGE}: the disaggregation factors are 1 outside that band, so all
     * education levels share the same rate there. Inside the band the fallback is a real
     * approximation, so it is tallied separately.
     */
    private double lookupMortalityRate(Person person, int personAge, String location) {
        Map<String, Double> mortality = dataContainer.getHealthTransitionData().get(Diseases.all_cause_mortality);
        EducationLevel education = ((PersonHealthMEL) person).getEducationLevel();
        boolean inDisaggBand = personAge >= DISAGG_MIN_AGE && personAge <= DISAGG_MAX_AGE;

        if (education != null && education != EducationLevel.no) {
            Double rate = mortality.get(dataContainer.createTransitionLookupIndex(
                    personAge, person.getGender(), location, education));
            if (rate != null) {
                educationKeyedLookups++;
                return rate;
            }
            fallbackNoTransitionRow++;
            if (inDisaggBand) {
                fallbackNoTransitionRowInBand++;
            }
        } else if (personAge < ATTAINMENT_AGE) {
            // by design: education is not asserted before it has been attained
            fallbackNotYetAttained++;
        } else {
            fallbackNoEducationData++;
            if (inDisaggBand) {
                fallbackNoEducationDataInBand++;
            }
        }

        Double rate = mortality.get(dataContainer.createTransitionLookupIndex(
                personAge, person.getGender(), location));
        if (rate == null) {
            throw new RuntimeException("No all-cause mortality rate for person " + person.getId()
                    + " (age " + personAge + ", " + person.getGender() + ", location " + location + ")");
        }
        return rate;
    }

    /**
     * Reports how many mortality lookups used the un-disaggregated rate, and resets the tally.
     * Called once per simulated year from {@link DeathModelMEL#endYear(int)}.
     */
    void logAndResetFallbackTally(int year) {
        long total = fallbackNoEducationData + fallbackNotYetAttained + fallbackNoTransitionRow;
        if (total == 0) {
            logger.info("Mortality {}: all {} lookups used education-disaggregated rates.",
                    year, educationKeyedLookups);
        } else {
            logger.info("Mortality {}: {} lookups used education-disaggregated rates, {} fell back "
                            + "to un-disaggregated rates.", year, educationKeyedLookups, total);
            if (fallbackNoEducationData > 0) {
                logger.warn("  {} had no education data ({} aged {}-{}, where this changes the rate).",
                        fallbackNoEducationData, fallbackNoEducationDataInBand,
                        DISAGG_MIN_AGE, DISAGG_MAX_AGE);
            }
            if (fallbackNoTransitionRow > 0) {
                logger.warn("  {} had education but no matching transition table row ({} aged {}-{}).",
                        fallbackNoTransitionRow, fallbackNoTransitionRowInBand,
                        DISAGG_MIN_AGE, DISAGG_MAX_AGE);
            }
            if (fallbackNotYetAttained > 0) {
                logger.info("  {} had not yet reached the attainment age of {} (rate is identical "
                        + "there, so this is expected).", fallbackNotYetAttained, ATTAINMENT_AGE);
            }
        }
        educationKeyedLookups = 0;
        fallbackNoEducationData = 0;
        fallbackNoEducationDataInBand = 0;
        fallbackNotYetAttained = 0;
        fallbackNoTransitionRow = 0;
        fallbackNoTransitionRowInBand = 0;
    }
}