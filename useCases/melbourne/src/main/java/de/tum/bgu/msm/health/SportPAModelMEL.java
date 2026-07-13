package de.tum.bgu.msm.health;

import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.data.ZoneMEL;
import de.tum.bgu.msm.data.dwelling.Dwelling;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.data.person.Occupation;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.health.io.SportPAmodelCoefficientReader;
import de.tum.bgu.msm.models.AbstractModel;
import de.tum.bgu.msm.models.ModelUpdateListener;
import de.tum.bgu.msm.properties.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class SportPAModelMEL extends AbstractModel implements ModelUpdateListener {
    private static final Logger logger = LogManager.getLogger(SportPAModelMEL.class);
    private Map<String,Map<String,Double>> coef = new HashMap<>();

    // Median observed SA1 IRSD decile, used for dwellings in zones without an ABS SEIFA
    // IRSD score (low-population/low-response SA1s are not assigned SEIFA indices)
    private static final int IRSD_MEDIAN_DECILE = 6;

    public SportPAModelMEL(DataContainer dataContainer, Properties properties, Random random) {
        super(dataContainer, properties, random);
        this.coef = new SportPAmodelCoefficientReader().readData(properties.healthData.sportPAmodel);
        if (coef.get("linear").containsKey("sigma")) {
            logger.info("Sport PA model: log-normal positive stage detected (sigma = {}, cap = {}).",
                    coef.get("linear").get("sigma"), coef.get("linear").getOrDefault("cap", Double.MAX_VALUE));
        } else {
            logger.warn("Sport PA model: no 'sigma' row in {}; using legacy linear-scale positive stage.",
                    properties.healthData.sportPAmodel);
        }
    }

    @Override
    public void setup() {
    }

    @Override
    public void prepareYear(int year) {

    }

    @Override
    public void endYear(int year) {
        logger.warn("Sport Physical Activity end year:" + year);
        if((properties.healthData.baseExposureFile == null && year == properties.main.startYear) || properties.healthData.exposureModelYears.contains(year)) {
            updateSportPA();
        }
    }

    @Override
    public void endSimulation() {
    }

    public void updateSportPA() {
        int missingIrsdCount = 0;
        for(Person person : dataContainer.getHouseholdDataManager().getPersons()) {
            PersonHealthMEL personHealth = (PersonHealthMEL) person;

            // The NHS-derived hurdle model is fitted on adults (18+) only
            if (person.getAge() < 18) {
                personHealth.setWeeklyMarginalMetHoursSport(0.f);
                continue;
            }

            int irsdDecile = lookupIrsdDecile(person);
            if (irsdDecile < 1 || irsdDecile > 10) {
                irsdDecile = IRSD_MEDIAN_DECILE;
                missingIrsdCount++;
            }

            //zero model
            double utility = getPredictor(person, coef.get("zero"), irsdDecile);
            double zeroProb = Math.exp(utility)/(1+Math.exp(utility));

            if (random.nextDouble() < zeroProb) {
                personHealth.setWeeklyMarginalMetHoursSport(0.f);
                continue;
            }

            //positive stage weekly mMET hours
            Map<String, Double> linearCoef = coef.get("linear");
            double predictor = getPredictor(person, linearCoef, irsdDecile);
            Double sigma = linearCoef.get("sigma");
            double otherSport_wkhr;
            if (sigma == null) {
                // legacy coefficients: linear predictor is mMET hours/week directly
                otherSport_wkhr = Math.max(0, predictor);
            } else {
                // log-normal positive stage: predictor is log(mMET hours/week); draw from
                // the conditional distribution and cap at the survey-truncation maximum
                double cap = linearCoef.getOrDefault("cap", Double.MAX_VALUE);
                otherSport_wkhr = Math.min(cap, Math.exp(predictor + sigma * random.nextGaussian()));
            }
            personHealth.setWeeklyMarginalMetHoursSport((float) otherSport_wkhr);
        }
        if (missingIrsdCount > 0) {
            logger.warn("Sport PA model: {} adults live in zones without an IRSD decile; median decile {} used.",
                    missingIrsdCount, IRSD_MEDIAN_DECILE);
        }
    }

    private double getPredictor(Person person, Map<String, Double> coef, int irsdDecile) {
        double predictor = 0.0;

        // Intercept
        predictor += coef.get("intercept");

        // gender
        if (person.getGender().equals(Gender.FEMALE)){
            predictor += coef.get("female");
        }

        // age
        if (person.getAge() < 25) {
            predictor += handleCoefficient(coef, "age_group_under25");
        } else if (person.getAge() < 35) {
            predictor += handleCoefficient(coef, "age_group_25_34");
        } else if (person.getAge() < 45) {
            predictor += handleCoefficient(coef, "age_group_35_44");
        } else if (person.getAge() < 55) {
            predictor += handleCoefficient(coef, "age_group_45_54");
        } else if (person.getAge() < 65) {
            predictor += handleCoefficient(coef, "age_group_55_64");
        } else if (person.getAge() < 75) {
            predictor += handleCoefficient(coef, "age_group_65_74");
        } else {
            predictor += handleCoefficient(coef, "age_group_over75");
        }

        // occupation
        if (person.getOccupation().equals(Occupation.EMPLOYED)){
            predictor += handleCoefficient(coef, "is_employed");
        } else if(person.getOccupation().equals(Occupation.STUDENT)){
            predictor += handleCoefficient(coef, "student_status");
        }

        // Socio-economic disadvantage decile (SA1 IRSD, 1 = most disadvantaged, 10 = least)
        predictor += irsdDecile * handleCoefficient(coef, "IRSD");

        return predictor;
    }

    private int lookupIrsdDecile(Person person) {
        Dwelling dwelling = dataContainer.getRealEstateDataManager().getDwelling(person.getHousehold().getDwellingId());
        if (dwelling == null) {
            return Integer.MIN_VALUE;
        }
        ZoneMEL zoneMEL = (ZoneMEL) dataContainer.getGeoData().getZones().get(dwelling.getZoneId());
        if (zoneMEL == null) {
            return Integer.MIN_VALUE;
        }
        // zones with NA IRSD in the zonal data are read as Integer.MIN_VALUE
        return zoneMEL.getSocioEconomicDisadvantageDeciles();
    }

    private double handleCoefficient(Map<String, Double> coef, String coefName) {
        Double coefValue = coef.get(coefName);
        if (coefValue == null) {
            logger.warn("Missing coefficient for key '{}'. Using default value 0.0.", coefName);
            return 0.0;
        }
        return coefValue;
    }

}
