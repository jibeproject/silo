package de.tum.bgu.msm.health;

import cern.colt.map.tfloat.OpenIntFloatHashMap;
import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.health.injury.*;
import de.tum.bgu.msm.properties.Properties;
import uk.cam.mrc.phm.util.MelbourneImplementationConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.accidents.AccidentsModule;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Injector;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.EventsManagerModule;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioByInstanceModule;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

public class AccidentRateModelOsmMEL {
    private static final Logger log = LogManager.getLogger(AccidentRateModelOsmMEL.class);
    private final Scenario scenario;
    private final float scaleFactor;
    private final Day day;
    private final AccidentsContext accidentsContext = new AccidentsContext();
    private AnalysisEventHandlerMotorized motorizedHandler;
    private AnalysisEventHandlerNonMotorized nonMotorizedHandler;
    private int counterCar;
    private int counterBikePed;
    private List<OsmLink> osmLinks;
    private Properties properties;

    private static final Set<AccidentType> ACCIDENT_TYPES_EXCLUDED = Set.of(AccidentType.CAR, AccidentType.BIKECAR, AccidentType.BIKEBIKE);
    private static final Set<AccidentSeverity> ACCIDENT_SEVERITIES_EXCLUDED = Set.of(AccidentSeverity.LIGHT);
    public static final Set<String> MAJOR = Set.of(
            "primary", "primary_link", "secondary", "secondary_link",
            "tertiary", "tertiary_link", "trunk", "trunk_link", "bus_guideway", "cycleway", "motorway", "motorway_link"
    );
    public static final Set<String> MINOR = Set.of(
            "unclassified", "residential", "living_street", "service",
            "pedestrian", "track", "footway", "bridleway", "steps",
            "path", "road"
    );

    // TODO: adjust
    private static final double VEHICLE_SCALE_FACTOR = 10.0;

    public AccidentRateModelOsmMEL(Properties properties, Scenario scenario, float scaleFactor, Day day) {
        this.properties = properties;
        this.scenario = scenario;
        this.scaleFactor = scaleFactor;
        this.day = day;
    }

    public void runCasualtyRateMEL() {
        // Early skip mechanism - check if casualty rate files already exist
        String casualtyRatesFile = scenario.getConfig().controller().getOutputDirectory() + "casualtyRates.csv";
        String hourlyCasualtyRatesFile = scenario.getConfig().controller().getOutputDirectory() + "hourlyCasualtyRates.csv";

        if (new File(casualtyRatesFile).exists() && new File(hourlyCasualtyRatesFile).exists()) {
            log.info("Casualty rate files found, loading existing data instead of recomputing:");
            log.info("  - {}", casualtyRatesFile);
            log.info("  - {}", hourlyCasualtyRatesFile);

            // Initialize accident context for ALL links in current network
            initializeAccidentContextFromNetwork();

            // Initialize OSM links structure for file operations
            initializeOsmLinksForFileLoading();

            // Load available casualty data from files (missing links will have zero casualties)
            loadCasualtyDataFromFiles(hourlyCasualtyRatesFile);

            log.info("Successfully loaded casualty data from existing files.");
            return;
        }

        log.info("Casualty rate files not found, proceeding with full computation...");

        // Initialize injector
        com.google.inject.Injector injector = Injector.createInjector(scenario.getConfig(), new AbstractModule() {
            @Override
            public void install() {
                install(new ScenarioByInstanceModule(scenario));
                install(new AccidentsModule());
                install(new EventsManagerModule());
            }
        });

        // Network is already loaded in the scenario - no need to read it again!
        log.info("Updating scenario network context for {} links", scenario.getNetwork().getLinks().size());

        // Set accidentContext - initialize for all links
        for (Link link : scenario.getNetwork().getLinks().values()) {
            AccidentLinkInfo info = new AccidentLinkInfo(link.getId());
            this.accidentsContext.getLinkId2info().put(link.getId(), info);
        }
        log.info("Initializing all link-specific information... Done.");

        // Read vehicles
        Vehicles vehiclesMotorized = VehicleUtils.createVehiclesContainer();
        Vehicles vehiclesNonMotorized = VehicleUtils.createVehiclesContainer();
        readVehicles(vehiclesMotorized, vehiclesNonMotorized);

        // Initialize event handlers and read events
        setupEventHandlers(vehiclesMotorized, vehiclesNonMotorized);
        //readEvents(injector, "car", "output_events.xml.gz");
        //readEvents(injector, "bikePed", "output_events.xml.gz");

        // Process events
        EventsManagerImpl events = new EventsManagerImpl();
        MatsimEventsReader eventsReader = new MatsimEventsReader(events);

        events.addHandler(motorizedHandler);
        events.addHandler(nonMotorizedHandler);

        // Read event files separately
        System.out.println("Processing motorized events...");
        eventsReader.readFile(getOutputFilePath("car", "output_events.xml.gz")); // car/truck
        System.out.println("Processing non-motorized events...");
        eventsReader.readFile(getOutputFilePath("bikePed", "output_events.xml.gz")); // bike/ped

        // Aggregate network by OSM ID and compute attributes
        initializeOsmLinks();

        // Calculate casualty frequency at OSM level
        calculateCasualtyFrequency();

        // Compute link-level injury risk
        //computeLinkLevelInjuryRisk();

        // Clean up
        motorizedHandler.reset(0);
        nonMotorizedHandler.reset(0);
        System.gc();
    }

    private void readVehicles(Vehicles vehiclesMotorized, Vehicles vehiclesNonMotorized) {
        log.info("Reading vehicle files...");
        String vehicleFileCar = this.scenario.getConfig().controller().getOutputDirectory() + "car/" + this.scenario.getConfig().controller().getRunId() + ".output_vehicles.xml.gz";
        String vehicleFileBikePed = this.scenario.getConfig().controller().getOutputDirectory() + "bikePed/" + this.scenario.getConfig().controller().getRunId() + ".output_vehicles.xml.gz";

        //String vehicleFileCar = "/mnt/usb-TOSHIBA_EXTERNAL_USB_20241124015626F-0:0-part1/manchester/scenOutput/base/matsim/2021/sunday/car/2021.output_vehicles.xml.gz";
        //String vehicleFileBikePed = "/mnt/usb-TOSHIBA_EXTERNAL_USB_20241124015626F-0:0-part1/manchester/scenOutput/base/matsim/2021/sunday/bikePed/2021.output_vehicles.xml.gz";
        try {
            new MatsimVehicleReader(vehiclesMotorized).readFile(vehicleFileCar);
            new MatsimVehicleReader(vehiclesNonMotorized).readFile(vehicleFileBikePed);
            validateVehicleIds(vehiclesMotorized, "Motorized");
            validateVehicleIds(vehiclesNonMotorized, "Non-motorized");
        } catch (Exception e) {
            log.error("Error reading vehicle files", e);
        }
        log.info("Vehicle files loaded.");
    }

    private void validateVehicleIds(Vehicles vehicles, String type) {
        vehicles.getVehicles().keySet().stream()
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .forEach(entry -> log.warn("Duplicate {} vehicle ID found: {}", type, entry.getKey()));
    }

    private void setupEventHandlers(Vehicles vehiclesMotorized, Vehicles vehiclesNonMotorized) {
        log.info("Setting up event handlers...");
        motorizedHandler = new AnalysisEventHandlerMotorized(vehiclesMotorized, scenario);
        nonMotorizedHandler = new AnalysisEventHandlerNonMotorized(vehiclesNonMotorized, scenario);
        //motorizedHandler.setAccidentsContext(accidentsContext);
        //nonMotorizedHandler.setAccidentsContext(accidentsContext);
        log.info("Event handlers set up.");
    }

    private String getOutputFilePath(String mode, String fileName) {
        String runId = scenario.getConfig().controller().getRunId();
        String basePath = scenario.getConfig().controller().getOutputDirectory() + mode + "/";
        return runId == null || runId.isEmpty() ? basePath + fileName : basePath + runId + "." + fileName;
    }

    private void readEvents(com.google.inject.Injector injector, String mode, String fileName) {
        log.info("Reading {} events file...", mode);
        EventsManagerImpl events = new EventsManagerImpl();
        MatsimEventsReader eventsReader = new MatsimEventsReader(events);

        events.addHandler(motorizedHandler);
        events.addHandler(nonMotorizedHandler);
        new MatsimEventsReader(events).readFile(getOutputFilePath(mode, fileName));
        log.info("Reading {} events file... Done.", mode);
    }

    private void initializeOsmLinks() {
        log.info("Aggregating network by OSM ID...");
        Map<Integer, Set<Link>> linksByOsmId = scenario.getNetwork().getLinks().values().stream()
                .collect(Collectors.groupingBy(
                        link -> getOsmId(link),
                        Collectors.toSet()
                ));

        osmLinks = linksByOsmId.entrySet().stream()
                .map(entry -> {
                    OsmLink osmLink = new OsmLink(entry.getKey(), entry.getValue());
                    osmLink.computeAttributes();
                    computeDemandAttributes(osmLink);
                    //initializeLinkInfo(osmLink);
                    return osmLink;
                })
                .collect(Collectors.toList());
        log.info("Network aggregated. {} OsmLinks created.", osmLinks.size());
    }

    private void initializeLinkInfo(OsmLink osmLink) {
        for (Link link : osmLink.getNetworkLinks()) {
            accidentsContext.getLinkId2info().put(link.getId(), new AccidentLinkInfo(link.getId()));
        }
    }

    private int getOsmId(Link link) {
        Object osmIdAttr = link.getAttributes().getAttribute("osmID");
        if (osmIdAttr instanceof String) {
            try {
                return Integer.parseInt((String) osmIdAttr);
            } catch (NumberFormatException e) {
                return 0;
            }
        } else if (osmIdAttr instanceof Number) {
            return ((Number) osmIdAttr).intValue();
        }
        return 0;
    }

    private void computeDemandAttributes(OsmLink osmLink) {
        Set<Link> links = osmLink.getNetworkLinks();
        if (links.isEmpty()) {
            Arrays.fill(osmLink.carHourlyDemand, 0.0);
            Arrays.fill(osmLink.truckHourlyDemand, 0.0);
            Arrays.fill(osmLink.pedHourlyDemand, 0.0);
            Arrays.fill(osmLink.bikeHourlyDemand, 0.0);
            Arrays.fill(osmLink.motorHourlyDemand, 0.0);
            return;
        }

        int count = links.size();
        double[] carSums = new double[24];
        double[] truckSums = new double[24];
        double[] pedSums = new double[24];
        double[] bikeSums = new double[24];

        for (Link link : links) {
            for (int hour = 0; hour < 24; hour++) {
                double carDemand = motorizedHandler.getDemand(link.getId(), "car", hour) * VEHICLE_SCALE_FACTOR;
                double truckDemand = motorizedHandler.getDemand(link.getId(), "truck", hour) * VEHICLE_SCALE_FACTOR;
                double pedDemand = nonMotorizedHandler.getDemand(link.getId(), "walk", hour);
                double bikeDemand = nonMotorizedHandler.getDemand(link.getId(), "bike", hour);

                carSums[hour] += Double.isNaN(carDemand) ? 0.0 : carDemand;
                truckSums[hour] += Double.isNaN(truckDemand) ? 0.0 : truckDemand;
                pedSums[hour] += Double.isNaN(pedDemand) ? 0.0 : pedDemand;
                bikeSums[hour] += Double.isNaN(bikeDemand) ? 0.0 : bikeDemand;
            }
        }

        for (int hour = 0; hour < 24; hour++) {
            osmLink.carHourlyDemand[hour] = carSums[hour] / count;
            osmLink.truckHourlyDemand[hour] = truckSums[hour] / count;
            osmLink.pedHourlyDemand[hour] = pedSums[hour] / count;
            osmLink.bikeHourlyDemand[hour] = bikeSums[hour] / count;
            osmLink.motorHourlyDemand[hour] = osmLink.carHourlyDemand[hour] + osmLink.truckHourlyDemand[hour];
        }
    }

    private void calculateCasualtyFrequency() {
        log.info("Link casualty frequency calculation (by type by time of day) start.");
        Random random = new Random();
        double calibrationFactor = day == Day.thursday || day == Day.saturday || day == Day.sunday ? 2.19 : 2.19;
        String basePath = scenario.getScenarioElement("accidentModelFile").toString();

        for (AccidentType accidentType : AccidentType.values()) {
            if (ACCIDENT_TYPES_EXCLUDED.contains(accidentType)) continue;
            for (AccidentSeverity accidentSeverity : AccidentSeverity.values()) {
                if (ACCIDENT_SEVERITIES_EXCLUDED.contains(accidentSeverity)) continue;
                CasualtyRateCalculationOsmMEL calculator = new CasualtyRateCalculationOsmMEL(
                        accidentsContext,
                        accidentType,
                        accidentSeverity,
                        basePath);
                List<OsmLink> relevantOsmLinks = extractOsmLinksSpecific(osmLinks, accidentType);
                calculator.run(relevantOsmLinks, random);
                log.info("Calculating {} {} crash rate done.", accidentType, accidentSeverity);
            }
        }
        log.info("Link casualty frequency calculation completed.");

        // Only write files if they don't already exist (optimization)
        String casualtyRatesFile = scenario.getConfig().controller().getOutputDirectory() + "casualtyRates.csv";
        String hourlyCasualtyRatesFile = scenario.getConfig().controller().getOutputDirectory() + "hourlyCasualtyRates.csv";

        boolean casualtyRatesExist = new File(casualtyRatesFile).exists();
        boolean hourlyCasualtyRatesExist = new File(hourlyCasualtyRatesFile).exists();

        if (casualtyRatesExist && hourlyCasualtyRatesExist) {
            log.info("Casualty rate output files already exist, skipping file writing:");
            log.info("  - {}", casualtyRatesFile);
            log.info("  - {}", hourlyCasualtyRatesFile);
        } else {
            try {
                if (!casualtyRatesExist) {
                    writeOutCasualtyRate();
                    log.info("Written casualty rates to: {}", casualtyRatesFile);
                }
                if (!hourlyCasualtyRatesExist) {
                    writeOutHourlyCasualtyRate();
                    log.info("Written hourly casualty rates to: {}", hourlyCasualtyRatesFile);
                }
            } catch (FileNotFoundException e) {
                log.error("Error writing casualty rates", e);
            }
        }
    }

    private List<OsmLink> extractOsmLinksSpecific(List<OsmLink> osmLinks, AccidentType accidentType) {
        return osmLinks.stream().filter(osmLink -> {
            switch (accidentType) {
                case PED:
                    return osmLink.walkAllowed;
                case CAR_ONEWAY:
                    return osmLink.onwysmm && osmLink.carAllowed;
                case CAR_TWOWAY:
                    return !osmLink.onwysmm && osmLink.carAllowed;
                case BIKE_MINOR:
                    return MINOR.contains(osmLink.roadType) && osmLink.bikeAllowed;
                case BIKE_MAJOR:
                    return MAJOR.contains(osmLink.roadType) && osmLink.bikeAllowed;
                default:
                    return false;
            }
        }).collect(Collectors.toList());
    }

    private void computeLinkLevelInjuryRisk() {
        log.info("Link casualty exposure calculation start.");
        for (OsmLink osmLink : osmLinks) {
            computeLinkCasualtyExposureMEL(osmLink);
        }
        log.info("{} car links have no hourly traffic volume", counterCar);
        log.info("{} bike/ped links have no hourly traffic volume", counterBikePed);
        log.info("Link casualty exposure calculation completed.");

        try {
            //writeOutExposure();
            writeOutCasualtyRate();
        } catch (FileNotFoundException e) {
            log.error("Error writing exposure data", e);
        }

    }

    private void computeLinkCasualtyExposureMEL(OsmLink osmLink) {
        for (AccidentType accidentType : AccidentType.values()) {
            String mode = getModeForAccidentType(accidentType);
            if ("null".equals(mode)) continue;

            for (Link link : osmLink.getNetworkLinks()) {
                OpenIntFloatHashMap severeCasualtyExposureByTime = new OpenIntFloatHashMap();
                for (int hour = 0; hour < 24; hour++) {
                    float severeCasualty = getSevereCasualty(link.getId(), accidentType, hour);
                    float exposure = calculateExposure2(link, mode, hour, severeCasualty);
                    severeCasualtyExposureByTime.put(hour, exposure);
                }
                accidentsContext.getLinkId2info().get(link.getId())
                        .getSevereFatalCasualtyExposureByAccidentTypeByTime()
                        .put(accidentType, severeCasualtyExposureByTime);
            }
        }
    }

    private float calculateExposure2(Link link, String mode, int hour, float severeCasualty) {
        double demand;
        switch (mode) {
            case "car":
                demand = motorizedHandler.getDemand(link.getId(), "car", hour) * VEHICLE_SCALE_FACTOR;
                break;
            case "truck":
                demand = motorizedHandler.getDemand(link.getId(), "truck", hour) * VEHICLE_SCALE_FACTOR;
                break;
            case "walk":
                demand = nonMotorizedHandler.getDemand(link.getId(), "walk", hour);
                break;
            case "bike":
                demand = nonMotorizedHandler.getDemand(link.getId(), "bike", hour);
                break;
            default:
                log.warn("Unknown mode: {}", mode);
                return 0.0f;
        }

        if (demand == 0) {
            if (severeCasualty > 0.1) {
                log.warn("Casualty predicted in link with no {} flows: {}", mode, link.getId());
            }
            if (mode.equals("car") || mode.equals("truck")) {
                counterCar++;
            } else {
                counterBikePed++;
            }
            return 0.0f;
        }

        // float scaleFactor = (mode.equals("car") || mode.equals("truck")) ? scaleFactor : 1.0f;
        return (float) (severeCasualty / demand);
    }

    private String getModeForAccidentType(AccidentType accidentType) {
        switch (accidentType) {
            case CAR_TWOWAY:
            case CAR_ONEWAY:
                return "car";
            case PED:
                return "walk";
            case BIKE_MAJOR:
            case BIKE_MINOR:
                return "bike";
            default:
                return "null";
        }
    }

    private float getSevereCasualty(Id<Link> linkId, AccidentType accidentType, int hour) {
        OpenIntFloatHashMap timeMap = accidentsContext.getLinkId2info()
                .get(linkId)
                .getSevereFatalCasualtyExposureByAccidentTypeByTime()
                .get(accidentType);
        return timeMap != null ? timeMap.get(hour) : 0.0f;
    }

    private float calculateExposure(OsmLink osmLink, String mode, int hour, float severeCasualty) {
        double demand = mode.equals("car") ? osmLink.carHourlyDemand[hour] :
                mode.equals("walk") ? osmLink.pedHourlyDemand[hour] :
                        osmLink.bikeHourlyDemand[hour];
        if (demand == 0) {
            if (severeCasualty == 1) {
                log.warn("A casualty was predicted in a link with no {} flows: OSM ID {}", mode, osmLink.osmId);
            }
            if (mode.equals("car")) counterCar++;
            else counterBikePed++;
            return 0.0f;
        }
        return mode.equals("car") ?
                (float) (severeCasualty / (demand * scaleFactor)) :
                (float) (severeCasualty / demand);
    }

    public void writeOutHourlyCasualtyRate() throws FileNotFoundException {
        String csvPath = scenario.getConfig().controller().getOutputDirectory() + "hourlyCasualtyRates.csv";
        String parquetPath = scenario.getConfig().controller().getOutputDirectory() + "hourlyCasualtyRates.parquet";

        // Write CSV
        try (PrintWriter writer = new PrintWriter(new FileOutputStream(csvPath, false))) {
            writer.println("osmId,linkId,accidentType,hour,casualty");
            for (OsmLink osmLink : osmLinks) {
                for (Link link : osmLink.getNetworkLinks()) {
                    for (AccidentType accidentType : AccidentType.values()) {
                        if (ACCIDENT_TYPES_EXCLUDED.contains(accidentType)) continue;
                        for (AccidentSeverity accidentSeverity : AccidentSeverity.values()) {
                            if (ACCIDENT_SEVERITIES_EXCLUDED.contains(accidentSeverity)) continue;
                            if (accidentsContext.getLinkId2info().get(link.getId()).getSevereFatalCasualtyExposureByAccidentTypeByTime().get(accidentType) != null) {
                                for (int hour = 0; hour < 24; hour++) {
                                    double casualty = accidentsContext.getLinkId2info().get(link.getId())
                                            .getSevereFatalCasualtyExposureByAccidentTypeByTime()
                                            .get(accidentType).get(hour);
                                    if (casualty > 0) {
                                        writer.printf("%d,%s,%s,%d,%s\n",
                                                osmLink.osmId,
                                                link.getId().toString(),
                                                accidentType.name(),
                                                hour,
                                                Double.toString(casualty));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Write Parquet
        writeHourlyCasualtyRateParquet(parquetPath);
    }

    private void writeHourlyCasualtyRateParquet(String parquetPath) {
        try (org.apache.arrow.memory.RootAllocator allocator = new org.apache.arrow.memory.RootAllocator()) {
            // Define schema
            java.util.List<org.apache.arrow.vector.types.pojo.Field> fields = java.util.Arrays.asList(
                org.apache.arrow.vector.types.pojo.Field.nullable("osmId", org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE),
                org.apache.arrow.vector.types.pojo.Field.nullable("linkId", org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE),
                org.apache.arrow.vector.types.pojo.Field.nullable("accidentType", org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE),
                org.apache.arrow.vector.types.pojo.Field.nullable("hour", new org.apache.arrow.vector.types.pojo.ArrowType.Int(32, true)),
                org.apache.arrow.vector.types.pojo.Field.nullable("casualty", new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(org.apache.arrow.vector.types.pojo.FloatingPointPrecision.SINGLE))
            );
            org.apache.arrow.vector.types.pojo.Schema schema = new org.apache.arrow.vector.types.pojo.Schema(fields);

            // Collect all records first
            java.util.List<HourlyCasualtyRecord> records = new java.util.ArrayList<>();
            for (OsmLink osmLink : osmLinks) {
                for (Link link : osmLink.getNetworkLinks()) {
                    for (AccidentType accidentType : AccidentType.values()) {
                        if (ACCIDENT_TYPES_EXCLUDED.contains(accidentType)) continue;
                        for (AccidentSeverity accidentSeverity : AccidentSeverity.values()) {
                            if (ACCIDENT_SEVERITIES_EXCLUDED.contains(accidentSeverity)) continue;
                            if (accidentsContext.getLinkId2info().get(link.getId()).getSevereFatalCasualtyExposureByAccidentTypeByTime().get(accidentType) != null) {
                                for (int hour = 0; hour < 24; hour++) {
                                    double casualty = accidentsContext.getLinkId2info().get(link.getId())
                                            .getSevereFatalCasualtyExposureByAccidentTypeByTime()
                                            .get(accidentType).get(hour);
                                    if (casualty > 0) {
                                        records.add(new HourlyCasualtyRecord(
                                            String.valueOf(osmLink.osmId),
                                            link.getId().toString(),
                                            accidentType.name(),
                                            hour,
                                            (float) casualty
                                        ));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Write in batches
            int batchSize = 10000;
            org.apache.hadoop.fs.Path path = new org.apache.hadoop.fs.Path(parquetPath);
            org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();

            try (org.apache.parquet.hadoop.ParquetWriter<org.apache.arrow.vector.VectorSchemaRoot> writer =
                 org.apache.parquet.arrow.ArrowParquetWriter.builder(
                     org.apache.hadoop.conf.HadoopOutputFile.fromPath(path, conf))
                     .withSchema(schema)
                     .build()) {

                for (int i = 0; i < records.size(); i += batchSize) {
                    int endIndex = Math.min(i + batchSize, records.size());
                    java.util.List<HourlyCasualtyRecord> batch = records.subList(i, endIndex);

                    try (org.apache.arrow.vector.VectorSchemaRoot root = org.apache.arrow.vector.VectorSchemaRoot.create(schema, allocator)) {
                        root.allocateNew();

                        org.apache.arrow.vector.VarCharVector osmIdVector = (org.apache.arrow.vector.VarCharVector) root.getVector("osmId");
                        org.apache.arrow.vector.VarCharVector linkIdVector = (org.apache.arrow.vector.VarCharVector) root.getVector("linkId");
                        org.apache.arrow.vector.VarCharVector accidentTypeVector = (org.apache.arrow.vector.VarCharVector) root.getVector("accidentType");
                        org.apache.arrow.vector.IntVector hourVector = (org.apache.arrow.vector.IntVector) root.getVector("hour");
                        org.apache.arrow.vector.Float4Vector casualtyVector = (org.apache.arrow.vector.Float4Vector) root.getVector("casualty");

                        for (int j = 0; j < batch.size(); j++) {
                            HourlyCasualtyRecord record = batch.get(j);
                            osmIdVector.setSafe(j, record.getOsmId().getBytes());
                            linkIdVector.setSafe(j, record.getLinkId().getBytes());
                            accidentTypeVector.setSafe(j, record.getAccidentType().getBytes());
                            hourVector.setSafe(j, record.getHour());
                            casualtyVector.setSafe(j, record.getCasualty());
                        }

                        root.setRowCount(batch.size());
                        writer.write(root);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error writing Parquet hourly casualty rates file: {}", parquetPath, e);
        }
    }

    public void writeOutCasualtyRate() throws FileNotFoundException {
        String parquetPath = scenario.getConfig().controller().getOutputDirectory() + "casualtyRates.parquet";

        StringBuilder data = new StringBuilder("osmId,linkId,accidentType,casualty\n");
        java.util.List<CasualtyRateRecord> records = new java.util.ArrayList<>();

        for (OsmLink osmLink : osmLinks) {
            for (Link link : osmLink.getNetworkLinks()) {
                for (AccidentType accidentType : AccidentType.values()) {
                    if (ACCIDENT_TYPES_EXCLUDED.contains(accidentType)) continue;
                    for (AccidentSeverity accidentSeverity : AccidentSeverity.values()) {
                        if (ACCIDENT_SEVERITIES_EXCLUDED.contains(accidentSeverity)) continue;

                        double totalCasualty = 0;
                        if (accidentsContext.getLinkId2info().get(link.getId()).getSevereFatalCasualtyExposureByAccidentTypeByTime().get(accidentType) != null) {
                            for (int hour = 0; hour < 24; hour++) {
                                totalCasualty += accidentsContext.getLinkId2info().get(link.getId()).getSevereFatalCasualtyExposureByAccidentTypeByTime().get(accidentType).get(hour);
                            }
                        }

                        if (totalCasualty > 0) {
                            data.append(String.format("%d,%s,%s,%s\n", osmLink.osmId, link.getId().toString(), accidentType.name(), Double.toString(totalCasualty)));
                            records.add(new CasualtyRateRecord(
                                String.valueOf(osmLink.osmId),
                                link.getId().toString(),
                                accidentType.name(),
                                (float) totalCasualty
                            ));
                        }
                    }
                }
            }
        }

        // Write Parquet
        writeCasualtyRateParquet(parquetPath, records);
    }

    private void writeCasualtyRateParquet(String parquetPath, java.util.List<CasualtyRateRecord> records) {
        try (org.apache.arrow.memory.RootAllocator allocator = new org.apache.arrow.memory.RootAllocator()) {
            // Define schema
            java.util.List<org.apache.arrow.vector.types.pojo.Field> fields = java.util.Arrays.asList(
                org.apache.arrow.vector.types.pojo.Field.nullable("osmId", org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE),
                org.apache.arrow.vector.types.pojo.Field.nullable("linkId", org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE),
                org.apache.arrow.vector.types.pojo.Field.nullable("accidentType", org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE),
                org.apache.arrow.vector.types.pojo.Field.nullable("casualty", new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(org.apache.arrow.vector.types.pojo.FloatingPointPrecision.SINGLE))
            );
            org.apache.arrow.vector.types.pojo.Schema schema = new org.apache.arrow.vector.types.pojo.Schema(fields);

            // Write in batches
            int batchSize = 10000;
            org.apache.hadoop.fs.Path path = new org.apache.hadoop.fs.Path(parquetPath);
            org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();

            try (org.apache.parquet.hadoop.ParquetWriter<org.apache.arrow.vector.VectorSchemaRoot> writer =
                 org.apache.parquet.arrow.ArrowParquetWriter.builder(
                     org.apache.hadoop.conf.HadoopOutputFile.fromPath(path, conf))
                     .withSchema(schema)
                     .build()) {

                for (int i = 0; i < records.size(); i += batchSize) {
                    int endIndex = Math.min(i + batchSize, records.size());
                    java.util.List<CasualtyRateRecord> batch = records.subList(i, endIndex);

                    try (org.apache.arrow.vector.VectorSchemaRoot root = org.apache.arrow.vector.VectorSchemaRoot.create(schema, allocator)) {
                        root.allocateNew();

                        org.apache.arrow.vector.VarCharVector osmIdVector = (org.apache.arrow.vector.VarCharVector) root.getVector("osmId");
                        org.apache.arrow.vector.VarCharVector linkIdVector = (org.apache.arrow.vector.VarCharVector) root.getVector("linkId");
                        org.apache.arrow.vector.VarCharVector accidentTypeVector = (org.apache.arrow.vector.VarCharVector) root.getVector("accidentType");
                        org.apache.arrow.vector.Float4Vector casualtyVector = (org.apache.arrow.vector.Float4Vector) root.getVector("casualty");

                        for (int j = 0; j < batch.size(); j++) {
                            CasualtyRateRecord record = batch.get(j);
                            osmIdVector.setSafe(j, record.getOsmId().getBytes());
                            linkIdVector.setSafe(j, record.getLinkId().getBytes());
                            accidentTypeVector.setSafe(j, record.getAccidentType().getBytes());
                            casualtyVector.setSafe(j, record.getCasualty());
                        }

                        root.setRowCount(batch.size());
                        writer.write(root);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error writing Parquet casualty rates file: {}", parquetPath, e);
        }
    }



    public AccidentsContext getAccidentsContext() {
        return accidentsContext;
    }

    /**
     * Initialize accident context for all network links when loading from files.
     * This is needed when we skip the full computation but still need the context structure.
     */
    private void initializeAccidentContextFromNetwork() {
        log.info("Initializing accident context from existing network...");

        // Check if network is already loaded in the scenario
        if (scenario.getNetwork().getLinks().isEmpty()) {
            log.info("Network not loaded, loading network file...");
            java.util.Properties props = MelbourneImplementationConfig.getMitoBaseProperties();
            String networkFile = props.getProperty("MATSIM_NETWORK", "input/mito/trafficAssignment/network.xml");
            new MatsimNetworkReader(scenario.getNetwork()).readFile(networkFile);
            log.info("Network loaded with {} links", scenario.getNetwork().getLinks().size());
        } else {
            log.info("Using pre-loaded network with {} links", scenario.getNetwork().getLinks().size());
        }

        // Initialize accident context for all links
        for (Link link : scenario.getNetwork().getLinks().values()) {
            AccidentLinkInfo info = new AccidentLinkInfo(link.getId());
            this.accidentsContext.getLinkId2info().put(link.getId(), info);
        }

        log.info("Accident context initialized for {} links", scenario.getNetwork().getLinks().size());
    }

    /**
     * Load casualty data from existing CSV files into the accident context.
     * Missing links in the files will simply have zero casualties in the current network.
     */
    private void loadCasualtyDataFromFiles(String hourlyCasualtyRatesFile) {
        long startTime = System.currentTimeMillis();

        // Check if Parquet file exists, otherwise fall back to CSV
        String parquetFile = hourlyCasualtyRatesFile.replace(".csv", ".parquet");
        boolean useParquet = new java.io.File(parquetFile).exists();

        if (useParquet) {
            log.info("Loading casualty data from Parquet file: {}", parquetFile);
            loadCasualtyDataFromParquet(parquetFile, startTime);
        } else {
            log.info("Parquet file not found, loading from CSV: {}", hourlyCasualtyRatesFile);
            loadCasualtyDataFromCSV(hourlyCasualtyRatesFile, startTime);
        }
    }

    private void loadCasualtyDataFromParquet(String parquetFile, long startTime) {
        int loadedRecords = 0;
        int skippedRecords = 0;

        try (org.apache.arrow.memory.RootAllocator allocator = new org.apache.arrow.memory.RootAllocator()) {
            org.apache.hadoop.fs.Path path = new org.apache.hadoop.fs.Path(parquetFile);
            org.apache.parquet.arrow.schema.SchemaConverter converter = new org.apache.parquet.arrow.schema.SchemaConverter();

            try (org.apache.parquet.hadoop.ParquetFileReader reader = org.apache.parquet.hadoop.ParquetFileReader.open(
                    org.apache.hadoop.conf.HadoopInputFile.fromPath(path, new org.apache.hadoop.conf.Configuration()))) {

                org.apache.parquet.schema.MessageType schema = reader.getFileMetaData().getSchema();
                org.apache.arrow.vector.types.pojo.Schema arrowSchema = converter.fromParquet(schema).getArrowSchema();

                try (org.apache.arrow.vector.VectorSchemaRoot root = org.apache.arrow.vector.VectorSchemaRoot.create(arrowSchema, allocator);
                     org.apache.parquet.arrow.ArrowParquetReader arrowReader = new org.apache.parquet.arrow.ArrowParquetReader(allocator, reader)) {

                    while (arrowReader.loadNextBatch()) {
                        int batchSize = root.getRowCount();

                        org.apache.arrow.vector.VarCharVector linkIdVector = (org.apache.arrow.vector.VarCharVector) root.getVector("linkId");
                        org.apache.arrow.vector.VarCharVector accidentTypeVector = (org.apache.arrow.vector.VarCharVector) root.getVector("accidentType");
                        org.apache.arrow.vector.IntVector hourVector = (org.apache.arrow.vector.IntVector) root.getVector("hour");
                        org.apache.arrow.vector.Float4Vector casualtyVector = (org.apache.arrow.vector.Float4Vector) root.getVector("casualty");

                        java.util.List<CasualtyRecord> batch = new java.util.ArrayList<>(batchSize);

                        for (int i = 0; i < batchSize; i++) {
                            if (!linkIdVector.isNull(i) && !accidentTypeVector.isNull(i) &&
                                !hourVector.isNull(i) && !casualtyVector.isNull(i)) {

                                String linkIdStr = linkIdVector.getObject(i).toString();
                                String accidentTypeStr = accidentTypeVector.getObject(i).toString();
                                int hour = hourVector.get(i);
                                float casualty = casualtyVector.get(i);

                                try {
                                    batch.add(new CasualtyRecord(
                                        Id.createLinkId(linkIdStr),
                                        AccidentType.valueOf(accidentTypeStr),
                                        hour,
                                        casualty
                                    ));
                                } catch (Exception e) {
                                    // Skip malformed records
                                }
                            }
                        }

                        if (!batch.isEmpty()) {
                            int[] batchResults = processCasualtyBatch(batch);
                            loadedRecords += batchResults[0];
                            skippedRecords += batchResults[1];
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error reading Parquet casualty rates file: {}", parquetFile, e);
            throw new RuntimeException("Failed to load casualty data from Parquet file", e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Loaded {} casualty records from Parquet, skipped {} records in {} ms",
                loadedRecords, skippedRecords, elapsed);
    }

    private void loadCasualtyDataFromCSV(String hourlyCasualtyRatesFile, long startTime) {
        int loadedRecords = 0;
        int skippedRecords = 0;

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(hourlyCasualtyRatesFile), 1024 * 1024)) {

            String line = reader.readLine(); // Skip header
            java.util.List<CasualtyRecord> batch = new java.util.ArrayList<>(10000);

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                CasualtyRecord record = parseCasualtyLine(line);
                if (record != null) {
                    batch.add(record);

                    if (batch.size() >= 10000) {
                        int[] batchResults = processCasualtyBatch(batch);
                        loadedRecords += batchResults[0];
                        skippedRecords += batchResults[1];
                        batch.clear();
                    }
                }
            }

            if (!batch.isEmpty()) {
                int[] batchResults = processCasualtyBatch(batch);
                loadedRecords += batchResults[0];
                skippedRecords += batchResults[1];
            }

        } catch (Exception e) {
            log.error("Error reading CSV casualty rates file: {}", hourlyCasualtyRatesFile, e);
            throw new RuntimeException("Failed to load casualty data from CSV file", e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Loaded {} casualty records from CSV, skipped {} records in {} ms",
                loadedRecords, skippedRecords, elapsed);
    }

    private static class CasualtyRecord {
        private final Id<Link> linkId;
        private final AccidentType accidentType;
        private final int hour;
        private final float casualty;

        public CasualtyRecord(Id<Link> linkId, AccidentType accidentType, int hour, float casualty) {
            this.linkId = linkId;
            this.accidentType = accidentType;
            this.hour = hour;
            this.casualty = casualty;
        }

        public Id<Link> getLinkId() {
            return linkId;
        }

        public AccidentType getAccidentType() {
            return accidentType;
        }

        public int getHour() {
            return hour;
        }

        public float getCasualty() {
            return casualty;
        }
    }

    private static class HourlyCasualtyRecord {
        private final String osmId;
        private final String linkId;
        private final String accidentType;
        private final int hour;
        private final float casualty;

        public HourlyCasualtyRecord(String osmId, String linkId, String accidentType, int hour, float casualty) {
            this.osmId = osmId;
            this.linkId = linkId;
            this.accidentType = accidentType;
            this.hour = hour;
            this.casualty = casualty;
        }

        public String getOsmId() { return osmId; }
        public String getLinkId() { return linkId; }
        public String getAccidentType() { return accidentType; }
        public int getHour() { return hour; }
        public float getCasualty() { return casualty; }
    }

    private static class CasualtyRateRecord {
        private final String osmId;
        private final String linkId;
        private final String accidentType;
        private final float casualty;

        public CasualtyRateRecord(String osmId, String linkId, String accidentType, float casualty) {
            this.osmId = osmId;
            this.linkId = linkId;
            this.accidentType = accidentType;
            this.casualty = casualty;
        }

        public String getOsmId() { return osmId; }
        public String getLinkId() { return linkId; }
        public String getAccidentType() { return accidentType; }
        public float getCasualty() { return casualty; }
    }
}
