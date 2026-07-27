package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.person.*;

public class PersonFactoryMELHealth implements PersonFactory {

    @Override
    public PersonHealthMEL createPerson(int id, int age,
                                        Gender gender, Occupation occupation,
                                        PersonRole role, int workplace,
                                        int income) {
        return new PersonHealthMEL(id, age, gender,
                occupation, role, workplace,
                income);
    }

    @Override
    public Person giveBirth(Person parent, int id, Gender gender) {
        PersonHealthMEL pp = new PersonHealthMEL(id, 0, gender, Occupation.TODDLER, PersonRole.CHILD, 0, 0);
        pp.setEthnic(((PersonHealthMEL)parent).getEthnic());
        // education is deliberately left unset: inheriting the parent's would assert perfect
        // intergenerational transmission. EducationAttainmentModelMEL draws a level once the
        // newborn reaches the attainment age, and mortality is unaffected until then.
        return pp;
    }

    //TODO duplicate as well school attributes
    @Override
    public PersonHealthMEL duplicate(Person originalPerson, int id) {
        PersonHealthMEL duplicate = new PersonHealthMEL(id,
                originalPerson.getAge(),
                originalPerson.getGender(),
                originalPerson.getOccupation(),
                originalPerson.getRole(),
                -1,
                originalPerson.getAnnualIncome());
        duplicate.setDriverLicense(originalPerson.hasDriverLicense());
        duplicate.setSchoolId(((PersonHealthMEL) originalPerson).getSchoolId());
        // a duplicate is the same person cloned, so both attributes carry over
        duplicate.setEthnic(((PersonHealthMEL) originalPerson).getEthnic());
        duplicate.setEducationLevel(((PersonHealthMEL) originalPerson).getEducationLevel());
        return duplicate;
    }
}
