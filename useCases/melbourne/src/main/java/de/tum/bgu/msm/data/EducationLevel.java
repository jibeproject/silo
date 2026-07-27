package de.tum.bgu.msm.data;

/**
 * Three-level educational attainment, following the ISCED 2011 groupings used by
 * Welsh et al. (2021) for education-specific mortality: low = ISCED 0-2,
 * medium = ISCED 3-5, high = ISCED 6-8. The synthetic population derives these from the
 * ABS HEAP classification (see determineEducationLevel.R and
 * "ref_ASCED - ISCED Level Correspondence Table.xlsx" in the synthetic population data).
 *
 * {@link #no} means "not yet attained or unknown" rather than a level of its own: it is the
 * state of a person born during the simulation who has not reached the attainment age, and of
 * anyone missing from the extended person microdata. Lookups keyed on it fall back to the
 * un-disaggregated mortality rate.
 */
public enum EducationLevel {
    no (0),
    low (1),
    medium (2),
    high(3);

    private final int educationCode;

    EducationLevel(int educationCode) {
        this.educationCode = educationCode;
    }

    public int getEducationCode() {
        return educationCode;
    }

}
