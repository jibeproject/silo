package de.tum.bgu.msm.health.accidentModel;

import de.tum.bgu.msm.health.injury.AccidentType;
import de.tum.bgu.msm.health.injury.AccidentSeverity;
import org.matsim.api.core.v01.network.Link;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Placeholder implementation for OptimisedAccidentRateCalculationMEL.
 * This class will be replaced with the optimised implementation.
 */
public class OptimisedAccidentRateCalculationMEL {

    private final double scaleFactor;
    private final AccidentType accidentType;
    private final AccidentSeverity accidentSeverity;
    private final AccidentCoefficientManager coefficientManager;
    private final LinkDemandCalculator demandCalculator;
    private final AccidentProbabilityCalculator probabilityCalculator;

    private int linksProcessed = 0;
    private int linksSkipped = 0;
    private long processingTimeMillis = 0;

    public OptimisedAccidentRateCalculationMEL(double scaleFactor,
                                             AccidentType accidentType,
                                             AccidentSeverity accidentSeverity,
                                             AccidentCoefficientManager coefficientManager,
                                             LinkDemandCalculator demandCalculator,
                                             AccidentProbabilityCalculator probabilityCalculator) {
        this.scaleFactor = scaleFactor;
        this.accidentType = accidentType;
        this.accidentSeverity = accidentSeverity;
        this.coefficientManager = coefficientManager;
        this.demandCalculator = demandCalculator;
        this.probabilityCalculator = probabilityCalculator;
    }

    public void processLinksInParallel(Collection<Link> links, int day) {
        long startTime = System.currentTimeMillis();
        linksProcessed = 0;
        linksSkipped = 0;

        for (Link link : links) {
            if (shouldProcessLink(link)) {
                probabilityCalculator.calculateProbabilityZeroCrash(link);
                probabilityCalculator.calculateMeanCrashPoisson(link);
                linksProcessed++;
            } else {
                linksSkipped++;
            }
        }

        processingTimeMillis = System.currentTimeMillis() - startTime;
    }

    private boolean shouldProcessLink(Link link) {
        switch (accidentType) {
            case BIKEBIKE:
            case BIKECAR:
                return demandCalculator.hasBikeDemand(link);
            case CAR:
                return demandCalculator.hasCarDemand(link);
            case PED:
                return demandCalculator.hasWalkDemand(link);
            default:
                return true;
        }
    }

    public boolean hasProcessedResults() {
        return linksProcessed > 0;
    }

    public Map<String, Object> getProcessingStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("linksProcessed", linksProcessed);
        stats.put("linksSkipped", linksSkipped);
        stats.put("processingTimeMillis", processingTimeMillis);
        return stats;
    }
}
