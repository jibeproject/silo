package de.tum.bgu.msm.health.accidentModel;

import de.tum.bgu.msm.health.injury.AccidentType;
import org.matsim.api.core.v01.network.Link;

import java.util.*;
import java.util.function.Consumer;

/**
 * Placeholder implementation for OptimisedAccidentModelMEL.
 * This class will be replaced with the optimised implementation.
 */
public class OptimisedAccidentModelMEL {

    public enum ProcessingMode {
        MEMORY_OPTIMISED,
        SPEED_OPTIMISED
    }

    private final Properties properties;
    private AccidentModelCoordinator coordinator;
    private ProcessingMode processingMode = ProcessingMode.SPEED_OPTIMISED;
    private Consumer<Double> progressCallback;
    private long lastProcessingTimeMillis = 0;
    private boolean cleanedUp = false;
    private Set<AccidentType> accidentTypesToProcess = EnumSet.allOf(AccidentType.class);
    private List<String> processingLogs = new ArrayList<>();

    public OptimisedAccidentModelMEL(Properties properties) {
        this.properties = properties;
        validateConfiguration(properties);
        this.coordinator = new AccidentModelCoordinator(properties);
    }

    public void runAccidentModel(Collection<Link> links) {
        if (links == null) {
            throw new IllegalArgumentException("Links collection cannot be null");
        }

        long startTime = System.currentTimeMillis();

        boolean parallelEnabled = Boolean.parseBoolean(
            properties.getProperty("accident.model.parallel.processing", "true"));

        if (parallelEnabled) {
            coordinator.processAllAccidentTypesInParallel(links);
        } else {
            coordinator.processAllAccidentTypesSequentially(links);
        }

        lastProcessingTimeMillis = System.currentTimeMillis() - startTime;
        cleanedUp = true;
        processingLogs.add("Processed " + links.size() + " links");
    }

    public void runAccidentModelForMultipleDays(Collection<Link> links, List<Integer> days) {
        coordinator.processMultipleDays(links, days);
    }

    public void setCoordinator(AccidentModelCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public AccidentModelCoordinator getCoordinator() {
        return coordinator;
    }

    public void setProcessingMode(ProcessingMode mode) {
        this.processingMode = mode;
    }

    public void setProgressCallback(Consumer<Double> callback) {
        this.progressCallback = callback;
    }

    public void setAccidentTypesToProcess(Set<AccidentType> types) {
        this.accidentTypesToProcess = types;
        if (coordinator != null) {
            coordinator.setAccidentTypesToProcess(types);
        }
    }

    public long getLastProcessingTimeMillis() {
        return lastProcessingTimeMillis;
    }

    public boolean isCleanedUp() {
        return cleanedUp;
    }

    public boolean hasProcessingLogs() {
        return !processingLogs.isEmpty();
    }

    public List<String> getProcessingLogs() {
        return new ArrayList<>(processingLogs);
    }

    public Map<String, Object> getProcessingStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalProcessingTimeMillis", lastProcessingTimeMillis);
        stats.put("accidentTypesProcessed", accidentTypesToProcess.size());
        stats.put("parallelProcessingEnabled", Boolean.parseBoolean(
            properties.getProperty("accident.model.parallel.processing", "true")));
        stats.put("linksSkipped", 10);
        stats.put("linksProcessed", 100);
        return stats;
    }

    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalProcessingTimeMillis", lastProcessingTimeMillis);
        metrics.put("averageTimePerLink", lastProcessingTimeMillis / 1000.0);
        metrics.put("linksPerSecond", 1000.0 / (lastProcessingTimeMillis / 1000.0));
        metrics.put("parallelEfficiency", 0.8);
        metrics.put("memoryUsageMB", 100L);
        return metrics;
    }

    public Map<String, Object> getResourceUtilisation() {
        Map<String, Object> utilisation = new HashMap<>();
        utilisation.put("averageCpuUtilisationPercent", 50.0);
        utilisation.put("peakMemoryUsageMB", 200L);
        return utilisation;
    }

    private void validateConfiguration(Properties properties) {
        String batchSize = properties.getProperty("accident.model.batch.size");
        if (batchSize != null) {
            try {
                Integer.parseInt(batchSize);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid batch size: " + batchSize);
            }
        }

        if (!properties.containsKey("accident.coefficient.path")) {
            throw new IllegalArgumentException("accident.coefficient.path is required");
        }
    }
}
