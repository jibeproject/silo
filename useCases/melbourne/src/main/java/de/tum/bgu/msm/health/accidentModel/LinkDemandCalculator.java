package de.tum.bgu.msm.health.accidentModel;

import org.matsim.api.core.v01.network.Link;

/**
 * Placeholder implementation for LinkDemandCalculator.
 * This class will be replaced with the optimised implementation.
 */
public class LinkDemandCalculator {

    private static final double BIKE_SCALE_FACTOR = 0.1;
    private static final double CAR_SCALE_FACTOR = 0.001;

    public double calculateBikeDemand(Link link) {
        Object bikeAttribute = link.getAttributes().getAttribute("bike");
        if (bikeAttribute instanceof Double) {
            return (Double) bikeAttribute * BIKE_SCALE_FACTOR;
        }
        return 0.0;
    }

    public double calculateCarDemand(Link link) {
        Object carAttribute = link.getAttributes().getAttribute("car");
        if (carAttribute instanceof Double) {
            return (Double) carAttribute * CAR_SCALE_FACTOR;
        }
        return 0.0;
    }

    public double calculateCarDemandInThousands(Link link) {
        return calculateCarDemand(link);
    }

    public double calculateWalkDemand(Link link) {
        return link.getLength() * 50.0;
    }

    public boolean hasBikeDemand(Link link) {
        return calculateBikeDemand(link) > 0.0;
    }

    public boolean hasCarDemand(Link link) {
        return calculateCarDemand(link) > 0.0;
    }

    public boolean hasWalkDemand(Link link) {
        return link.getLength() > 0.0;
    }
}
