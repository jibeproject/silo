package de.tum.bgu.msm.health.accidentModel;

import de.tum.bgu.msm.health.injury.AccidentType;
import org.matsim.api.core.v01.network.Link;

/**
 * Placeholder implementation for AccidentProbabilityCalculator.
 * This class will be replaced with the optimised implementation.
 */
public class AccidentProbabilityCalculator {

    private final AccidentCoefficientManager coefficientManager;
    private final LinkDemandCalculator demandCalculator;
    private final AccidentType accidentType;

    public AccidentProbabilityCalculator(AccidentCoefficientManager coefficientManager,
                                       LinkDemandCalculator demandCalculator,
                                       AccidentType accidentType) {
        this.coefficientManager = coefficientManager;
        this.demandCalculator = demandCalculator;
        this.accidentType = accidentType;
    }

    public double calculateProbabilityZeroCrash(Link link) {
        double utility = calculateZeroCrashUtility(link);
        return 1.0 / (1.0 + Math.exp(-utility));
    }

    public double calculateZeroCrashUtility(Link link) {
        double utility = coefficientManager.getBinaryLogitCoefficient("intercept");
        utility += calculateDemandContributions(link);
        utility += calculateLengthContribution(link);
        utility += calculateSpeedLimitContribution(link);
        utility += calculateBikeStressContribution(link);
        return utility;
    }

    public double calculateMeanCrashPoisson(Link link) {
        double utility = coefficientManager.getPoissonCoefficient("intercept");
        utility += demandCalculator.calculateBikeDemand(link) * coefficientManager.getPoissonCoefficient("bike_demand");
        utility += demandCalculator.calculateCarDemandInThousands(link) * coefficientManager.getPoissonCoefficient("car_demand");
        utility += link.getLength() * coefficientManager.getPoissonCoefficient("length");
        return Math.exp(utility);
    }

    public double calculateBikeStressContribution(Link link) {
        if (accidentType == AccidentType.BIKEBIKE || accidentType == AccidentType.BIKECAR) {
            Object stress = link.getAttributes().getAttribute("bike_stress");
            if (stress instanceof Double) {
                return (Double) stress * coefficientManager.getBinaryLogitCoefficient("bike_stress");
            }
        }
        return 0.0;
    }

    public double calculateLengthContribution(Link link) {
        return link.getLength() * coefficientManager.getBinaryLogitCoefficient("length");
    }

    public double calculateDemandContributions(Link link) {
        double contribution = 0.0;
        contribution += demandCalculator.calculateBikeDemand(link) * coefficientManager.getBinaryLogitCoefficient("bike_demand");
        contribution += demandCalculator.calculateCarDemandInThousands(link) * coefficientManager.getBinaryLogitCoefficient("car_demand");
        return contribution;
    }

    public double calculateSpeedLimitContribution(Link link) {
        Object speedLimit = link.getAttributes().getAttribute("speedLimitMPH");
        if (speedLimit instanceof Double) {
            return (Double) speedLimit * coefficientManager.getBinaryLogitCoefficient("speed_limit");
        }
        return 0.0;
    }
}
