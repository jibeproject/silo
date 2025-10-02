package de.tum.bgu.msm.health.accidentModel;

import de.tum.bgu.msm.health.injury.AccidentType;
import de.tum.bgu.msm.health.injury.AccidentSeverity;
import de.tum.bgu.msm.health.AccidentRateCalculationMEL;
import org.matsim.api.core.v01.network.Link;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Placeholder implementation for AccidentModelCoordinator.
 * This class will be replaced with the optimised implementation.
 */
public class AccidentModelCoordinator {

    private final Properties properties;
    private BiFunction<AccidentType, AccidentSeverity, AccidentRateCalculationMEL> calculationProvider;
    private int processedAccidentTypesCount = 0;
    private long totalProcessingTimeMillis = 0;
    private Set<AccidentType> accidentTypesToProcess = EnumSet.allOf(AccidentType.class);

    public AccidentModelCoordinator(Properties properties) {
        this.properties = properties;
    }

    public void setCalculationProvider(BiFunction<AccidentType, AccidentSeverity, AccidentRateCalculationMEL> provider) {
        this.calculationProvider = provider;
    }

    public void processAllAccidentTypes(Collection<Link> links, int day) {
        processAllAccidentTypesInParallel(links);
    }

    public void processAllAccidentTypesInParallel(Collection<Link> links) {
        long startTime = System.currentTimeMillis();

        for (AccidentType type : accidentTypesToProcess) {
            if (calculationProvider != null) {
                AccidentRateCalculationMEL calculation = calculationProvider.apply(type, AccidentSeverity.SEVEREFATAL);
                calculation.run(links);
                processedAccidentTypesCount++;
            }
        }

        totalProcessingTimeMillis = System.currentTimeMillis() - startTime;
    }

    public void processAllAccidentTypesSequentially(Collection<Link> links) {
        processAllAccidentTypesInParallel(links);
    }

    public void processMultipleDays(Collection<Link> links, List<Integer> days) {
        for (Integer day : days) {
            processAllAccidentTypes(links, day);
        }
    }

    public int getBatchSize() {
        return Integer.parseInt(properties.getProperty("accident.model.batch.size", "1000"));
    }

    public boolean isParallelProcessingEnabled() {
        return Boolean.parseBoolean(properties.getProperty("accident.model.parallel.processing", "true"));
    }

    public int getProcessedAccidentTypesCount() {
        return processedAccidentTypesCount;
    }

    public long getTotalProcessingTimeMillis() {
        return totalProcessingTimeMillis;
    }

    public void setAccidentTypesToProcess(Set<AccidentType> types) {
        this.accidentTypesToProcess = types;
    }
}
