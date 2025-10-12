package de.tum.bgu.msm.health;

import cern.colt.map.tfloat.OpenIntFloatHashMap;
import com.google.common.collect.Iterables;
import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.*;
import de.tum.bgu.msm.data.job.JobMEL;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.health.data.*;
import de.tum.bgu.msm.health.diseaseModelOffline.HealthExposuresReader;
import de.tum.bgu.msm.health.injury.AccidentType;
import de.tum.bgu.msm.health.io.LinkInfoReader;
import de.tum.bgu.msm.health.io.ActivityLocationInfoReader;
import de.tum.bgu.msm.health.io.TripExposureWriter;
import de.tum.bgu.msm.health.io.TripReaderHealth;
import de.tum.bgu.msm.health.noise.NoiseMetrics;
import de.tum.bgu.msm.models.AbstractModel;
import de.tum.bgu.msm.models.ModelUpdateListener;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.util.concurrent.ConcurrentExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contrib.dvrp.trafficmonitoring.TravelTimeUtils;
import org.matsim.contrib.emissions.Pollutant;
import org.matsim.core.config.Config;
import org.matsim.core.controler.ControlerDefaults;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.VehiclesFactory;
import routing.BicycleConfigGroup;
import routing.TransportModeNetworkFilter;
import routing.WalkConfigGroup;
import routing.components.Gradient;
import routing.components.JctStress;
import routing.components.LinkAmbience;
import routing.components.LinkStress;
import routing.travelDisutility.ActiveDisutilityPrecalc;
import routing.travelTime.WalkLinkSpeedCalculatorImpl;
import routing.travelTime.WalkTravelTime;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import uk.cam.mrc.phm.util.CoefficientLookup;
import uk.cam.mrc.phm.util.CoefficientLookup.CoefficientSet;

public class HealthExposureModelMEL extends AbstractModel implements ModelUpdateListener {
    private int latestMatsimYear = -1;
    private int latestMITOYear = -1;
    private static final Logger logger = LogManager.getLogger(HealthExposureModelMEL.class);
    private Map<Integer, Trip> mitoTrips = new HashMap<>();
    private final Config initialMatsimConfig;
    private MutableScenario scenario;
    private List<Day> simulatedDays;
    private List<Day> weekdays = Arrays.asList(Day.monday,Day.tuesday,Day.wednesday,Day.thursday,Day.friday);
    // private Map<Day, Map<String, Map<Id<Link>, Map<Integer, Integer>>>> trafficFlowsByDayModeLinkHour = new HashMap<>();
    private Map<Day, Map<String, Map<Id<Link>, Map<Integer, Integer>>>> trafficFlowsByDayModeLinkHour = new ConcurrentHashMap<>();

    private final HealthOutputFileManager fileManager;

    private final Map<Day, Boolean> linkInfoLoadedCache = new ConcurrentHashMap<>();
    private final Map<Day, Boolean> activityLocationInfoLoadedCache = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Trip>> processedHealthIndicatorCache = new ConcurrentHashMap<>();
    private final Map<String, Map<Id<Link>, Map<Integer, Double>>> preComputedRisksByModeLinkHour = new ConcurrentHashMap<>();

    public HealthExposureModelMEL(DataContainer dataContainer, Properties properties, Random random, Config config) {
        super(dataContainer, properties, random);
        this.initialMatsimConfig = config;
        this.fileManager = new HealthOutputFileManager(properties.main.baseDirectory, properties.main.scenarioName);
        //simulatedDays = Arrays.asList(Day.sunday,Day.saturday,Day.thursday);
        //simulatedDays = Arrays.asList(Day.sunday);
        simulatedDays = Arrays.asList(Day.sunday, Day.saturday, Day.friday, Day.thursday, Day.wednesday, Day.tuesday, Day.monday);
    }

    @Override
    public void setup() {
        if (properties.healthData.baseExposureFile != null) {
            new HealthExposuresReader().readData((HealthDataContainerImpl) dataContainer,properties.healthData.baseExposureFile);
        }
        // Initialize coefficient lookup table once at startup
        logger.info("Initialising coefficient lookup table for efficient processing...");
        CoefficientLookup.initialise();
        logger.info("Coefficient lookup initialised: {}", CoefficientLookup.getStatistics());
    }

    @Override
    public void prepareYear(int year) {}

    @Override
    public void endYear(int year) {
        //TODO: clean up the code to be compatible for different simulation setting
        if((properties.healthData.baseExposureFile == null && year == properties.main.startYear) || properties.healthData.exposureModelYears.contains(year)) {
            logger.warn("Health model end year:" + year);
            TreeSet<Integer> sortedYears = new TreeSet<>(properties.transportModel.transportModelYears);
            latestMatsimYear = sortedYears.floor(year);
            latestMITOYear = year;
            List<Day> completedDays = new ArrayList<>();

            Map<Integer, Trip> mitoTripsAll = new TripReaderHealth().readData(properties.main.baseDirectory + "scenOutput/"
                    + properties.main.scenarioName + "/" + latestMITOYear + "/microData/trips.csv");

            // todo: extract subset for testing !!
            //mitoTripsAll = TripSelector.selectRandomSubset(mitoTripsAll, 100);

            //
            // Readin full network
            // TODO simplify this
            //Set<Id<Link>> allLinks = scenario.getNetwork().getLinks().keySet();
            Network networkFull = ((HealthDataContainerImpl) dataContainer).getNetwork();


            //clear the health data from last exposure model year
            for(Person person : dataContainer.getHouseholdDataManager().getPersons()) {
                ((PersonHealth) person).resetHealthData();
            }

            // process ndvi data
            processNdviData(((HealthDataContainerImpl) dataContainer).getNetwork());

            // Initialize the table to count the flows for the injury model
            initializeTrafficFlows();

            // assemble travel-activity health exposure data
            for(Day day : simulatedDays){
                logger.warn("Setting up health model for {}", day);

                // Use 'thursday' for weekdays in healthDataAssembler, otherwise use the actual day
                Day dayForHealthData = weekdays.contains(day)
                        ? Day.thursday
                        : day;

                loadLinkInfoFromFile(dayForHealthData);
                logger.warn("Link info for {} loaded.", dayForHealthData);

                loadActivityLocationInfoFromFile(dayForHealthData);
                logger.warn("Activity info for {} loaded.", dayForHealthData);
                System.gc();

                // Pre-compute risk values
                preComputeRiskValues(day, networkFull);

                logger.warn("Run health exposure model for " + day);

                for(Mode mode : Mode.values()){
                    switch (mode){
                        case autoDriver:
                        case autoPassenger:
                        case bicycle:
                        case walk:
                        case pt:
                            // Filter trips for the specific day only
                            mitoTrips = mitoTripsAll.values().stream()
                                    .filter(trip -> trip.getTripMode().equals(mode) && trip.getDepartureDay().equals(day))
                                    .collect(Collectors.toMap(Trip::getId, trip -> trip));

                            logger.info("{} {} trips: {}", day, mode, mitoTrips.size());

                            healthDataAssembler(latestMatsimYear, dayForHealthData, mode);
                            final String outputDirectory = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName + "/" + year + "/";
                            String filett = outputDirectory
                                    + "healthIndicators"
                                    + "_" + day
                                    + "_" + mode
                                    + ".csv";
                            new TripExposureWriter().writeMitoTrips(mitoTrips, filett);
                            break;
                        default:
                            logger.warn("No exposure model for mode: {}", mode);
                    }
                    mitoTrips.clear();
                    mitoTrips = new HashMap<>();
                }

                // Track completed simulated days
                completedDays.add(day);

                // Load existing traffic flow data
                loadExistingTrafficFlowData(year, day, networkFull);

                //
                checkAccumulatedRisksByModeDayHour(networkFull, day, (HealthDataContainerImpl) dataContainer, trafficFlowsByDayModeLinkHour);

                // update injury risks here
                RunLinkToPersonInjuryRisks(networkFull);

                writeAndClearTrafficFlows(year, networkFull, day);

                // Reset
                ((DataContainerHealth) dataContainer).getLinkInfo().values().forEach(LinkInfo::reset);

                //
                /*  COMMENTED OUT FOR MANCHESTER
                if(completedDays.contains(Day.sunday)){
                    ((DataContainerHealth) dataContainer).getLinkInfoByDay(Day.sunday).values().forEach(linkInfo -> {linkInfo.reset();});
                } else if(completedDays.contains(Day.saturday)) {
                    ((DataContainerHealth) dataContainer).getLinkInfoByDay(Day.saturday).values().forEach(linkInfo -> {
                        linkInfo.reset();
                    });
                }
                if (weekdays.stream().allMatch(completedDays::contains)) {
                    logger.info("All weekdays (Monday to Friday) have been processed and are stored in completedDays.");
                    ((DataContainerHealth) dataContainer).getLinkInfoByDay(Day.thursday).values().forEach(linkInfo -> {
                        linkInfo.reset();
                    });
                }
                 */

                //
                ((DataContainerHealth) dataContainer).getActivityLocations().values().forEach(ActivityLocation::reset);
                //System.gc();
            }

            // TODO: free memory
            // write the traffic flows from routed trips and free memory
            //writeAndClearTrafficFlows(year, networkFull);
            //System.gc();


            // assemble home location health exposure data
            for(Day day : Day.values()){
                loadActivityLocationInfoFromFile(weekdays.contains(day) ? Day.thursday : day);
                calculatePersonHealthExposuresAtHome(day);
                ((DataContainerHealth)dataContainer).getActivityLocations().values().forEach(ActivityLocation::reset);
                System.gc();
            }

            // normalize person-level home-travel-activity exposure
            calculatePersonHealthExposureMetrics();


        }
    }

    @Override
    public void endSimulation() {
    }

    public void checkAccumulatedRisksByModeDayHour(Network network,
                                                   Day day,
                                                   HealthDataContainerImpl dataContainer,
                                                   Map<Day, Map<String, Map<Id<Link>, Map<Integer, Integer>>>> trafficFlowsByDayModeLinkHour) {
        // Define modes to loop over
        List<String> modes = Arrays.asList("car", "bike", "walk");
        Day dayForHealthData = weekdays.contains(day)
                ? Day.thursday
                : day;
        // Loop over each mode
        for (String mode : modes) {
            // Loop over each mode
            double zeroFlowRisk = 0.0;
            double nonZeroFlowRisk = 0.0;
            int zeroFlowCount = 0;
            int nonZeroFlowCount = 0;

            for (int hour = 0; hour < 24; hour++) {
                // Initialize accumulators for risks

                // Loop over all links in the MATSim network
                for (Link link : network.getLinks().values()) {
                    // Use pre-computed Risk
//                    double linkRisk = getPreComputedRiskValue(mode, link.getId(), hour);
//                    // OR Get link info for the specific day and link
                    LinkInfo linkInfo = ((HealthDataContainerImpl) dataContainer)
                            .getLinkInfoByDay(dayForHealthData)
                            .get(link.getId());

                    // Skip if linkInfo is null
                    if (linkInfo == null) {
                        continue;
                    }

                    // Get risk for the mode, hour, and link
                    double linkRisk = getLinkInjuryRisk2(mode, hour, linkInfo);

                    // Get flow for the day, mode, link, and hour
                    int flow = trafficFlowsByDayModeLinkHour
                            .getOrDefault(day, new HashMap<>())
                            .getOrDefault(mode, new HashMap<>())
                            .getOrDefault(link.getId(), new HashMap<>())
                            .getOrDefault(hour, 0);

                    // Accumulate risks based on flow for this hour
                    if (flow == 0) {
                        zeroFlowRisk += linkRisk;
                        zeroFlowCount++;
                    } else {
                        nonZeroFlowRisk += linkRisk;
                        nonZeroFlowCount++;
                    }
                }
            }

            // Print results for the current mode
            System.out.println(); // Empty line for readability
            logger.info("Analysis for day: " + day + ", mode: " + mode);

            // Format risk values to 4 significant digits
            String formattedZeroFlowRisk = String.format("%.4g", zeroFlowRisk);
            String formattedZeroFlowAvgRisk = String.format("%.4g",
                    zeroFlowCount > 0 ? zeroFlowRisk / zeroFlowCount : 0.0);

            String formattedNonZeroFlowRisk = String.format("%.4g", nonZeroFlowRisk);
            String formattedNonZeroFlowAvgRisk = String.format("%.4g",
                    nonZeroFlowCount > 0 ? nonZeroFlowRisk / nonZeroFlowCount : 0.0);

            logger.info("  - {} Links with Zero Flow: Total Risk: {}, Average Risk: {}",
                    zeroFlowCount, formattedZeroFlowRisk, formattedZeroFlowAvgRisk);
            logger.info(" - {} links with Non-Zero Flow: Total Risk: {}, Average Risk: {}",
                    nonZeroFlowCount, formattedNonZeroFlowRisk, formattedNonZeroFlowAvgRisk);
            System.out.println(); // Empty line for readability
        }
    }

    private void RunLinkToPersonInjuryRisks(Network network) {
        logger.warn("Updating person-based risks");

        for (Person person : dataContainer.getHouseholdDataManager().getPersons()) {
            PersonHealthMEL personHealth = (PersonHealthMEL) person;
            List<VisitedLink> visitedLinks = personHealth.getVisitedLinks();
            if (visitedLinks == null || visitedLinks.isEmpty()) {
                //logger.warn("Person " + person.getId() + " has no paths");
                continue;
            }

            // Map<Day, Map<String, Double>> risksByDayMode = new HashMap<>();

            for (VisitedLink visit : visitedLinks) {
                Link link = network.getLinks().get(visit.linkId); // TODO: this will be the active network :/
                if (link == null) {
                    //logger.warn("Link " + visit.linkId + " not found in network for person " + person.getId());
                    continue;
                }

                /*
                Mode modeForRisk;
                switch (visit.mode) {
                    case "car":
                        modeForRisk = Mode.autoDriver;
                        break;
                    case "bike":
                        modeForRisk = Mode.bicycle;
                        break;
                    case "walk":
                        modeForRisk = Mode.walk;
                        break;
                    case "pt":
                        modeForRisk = Mode.pt;
                        break;
                    default:
                        throw new RuntimeException("Undefined mode " + visit.mode);
                }

                 */


                int flow = trafficFlowsByDayModeLinkHour.getOrDefault(visit.day, new HashMap<>())
                        .getOrDefault(visit.mode, new HashMap<>())
                        .getOrDefault(visit.linkId, new HashMap<>())
                        .getOrDefault(visit.hour, 0);

                // TODO: get thursday for weekdays
                LinkInfo linkInfo;
                double linkRisk = 0.0, linkRiskPerPerson=0.0;

                if((!visit.day.equals(Day.saturday)) && (!visit.day.equals(Day.sunday))){
                    linkInfo = ((HealthDataContainerImpl) dataContainer).getLinkInfoByDay(Day.thursday).get(visit.linkId);
                    linkRisk = getLinkInjuryRisk2(visit.mode, visit.hour, linkInfo);
                    linkRisk = linkRisk/5;
                    linkRiskPerPerson = flow > 0 ? linkRisk / flow : 0.0;
                }else{
                    linkInfo = ((HealthDataContainerImpl) dataContainer).getLinkInfoByDay(visit.day).get(visit.linkId);
                    linkRisk = getLinkInjuryRisk2(visit.mode, visit.hour, linkInfo);
                    /*
                    if(linkRisk > 0){
                        logger.warn("Risk positive !!!");
                    }

                     */
                    linkRiskPerPerson = flow > 0 ? linkRisk / flow : 0.0;
                }

                /*
                risksByDayMode.computeIfAbsent(visit.day, k -> new HashMap<>())
                        .merge(visit.mode, riskPerTrip, Double::sum);

                 */

                // Age/gender interactions
                //
                int agePerson = person.getAge();
                Gender genderPerson = person.getGender();

                double AgeGenderRR=1.;
                //AgeGenderRR = getCasualtyRR_byAge_Gender(genderPerson, agePerson, mapToModeEnum(visit.mode));
                linkRiskPerPerson = linkRiskPerPerson * AgeGenderRR;



                switch(visit.mode){
                    case "car":
                        personHealth.updateWeeklyAccidentRisks(Map.of("severeFatalInjuryCar", linkRiskPerPerson));
                        break;
                    case "bike":
                        personHealth.updateWeeklyAccidentRisks(Map.of("severeFatalInjuryBike", linkRiskPerPerson));
                        break;
                    case "walk":
                        personHealth.updateWeeklyAccidentRisks(Map.of("severeFatalInjuryWalk", linkRiskPerPerson));
                        break;
                    default:
                        throw new RuntimeException("Undefined mode " + visit.mode);
                }
            }

            // Store aggregated risks in PersonHealthMEL (implementation-specific)
            /*
            for (Map.Entry<Day, Map<String, Double>> dayEntry : risksByDayMode.entrySet()) {
                Day day = dayEntry.getKey();
                for (Map.Entry<String, Double> modeEntry : dayEntry.getValue().entrySet()) {
                    String mode = modeEntry.getKey();
                    double risk = modeEntry.getValue();
                    personHealth.updateWeeklyAccidentRisks(Map.of(mode, (float) risk));
                }
            }

             */

            /*
            if(((PersonHealthMEL) person).getWeeklyAccidentRisk("severeFatalInjuryCar") > 0){
                logger.warn("Person " + person.getId() + " has weekly accident risks by car");
            }
            if(((PersonHealthMEL) person).getWeeklyAccidentRisk("severeFatalInjuryWalk") > 0){
                logger.warn("Person " + person.getId() + " has weekly accident risks by walk");
            }
            if(((PersonHealthMEL) person).getWeeklyAccidentRisk("severeFatalInjuryBike") > 0){
                logger.warn("Person " + person.getId() + " has weekly accident risks by bike");
            }

             */

            // Remove visited links after being used for calculation
            personHealth.getVisitedLinks().clear();
        }
    }

    public Mode mapToModeEnum(String modeStr) {
        if (modeStr == null) {
            logger.warn("Null mode string provided");
            return null; // or throw an exception
        }

        return switch (modeStr.toLowerCase()) {
            case "car" -> Mode.autoDriver;
            case "bike" -> Mode.bicycle;
            case "walk" -> Mode.walk;
            case "pt" -> Mode.pt;
            default -> {
                logger.warn("Unknown mode string: " + modeStr);
                yield null;
            }
        };
    }

    private String getAdjustedModeName(Mode mode) {
        return switch (mode) {
            case autoDriver, autoPassenger -> "car";
            case bicycle -> "bike";
            case walk -> "walk";
            case pt -> "pt";
            default -> throw new RuntimeException("Undefined mode " + mode);
        };
    }

    private void initializeTrafficFlows() {
        String[] modeAdjustedNames = {"car", "bike", "walk"};
        for (Day day : Day.values()) {
            Map<String, Map<Id<Link>, Map<Integer, Integer>>> modeMap = new ConcurrentHashMap<>();
            for (String modeName : modeAdjustedNames) {
                modeMap.put(modeName, new ConcurrentHashMap<>());
            }
            trafficFlowsByDayModeLinkHour.put(day, modeMap);
        }
    }

    private void writeAndClearTrafficFlows(int year, Network network, Day day) {

        List<String> modesToProcess = determineModesToProcess(year, day);

        for (String modeAdjusted : modesToProcess) {
            writeTrafficFlowsToCSV(year, day, modeAdjusted, network);
        }

        // Clear traffic flow data for the day to free memory
        trafficFlowsByDayModeLinkHour.remove(day);
        System.gc();
    }

    /**
     * Loads existing traffic flow data from CSV files into memory for downstream processes.
     * This ensures that injury risk calculations have access to traffic flow data whether
     * it comes from fresh processing or existing files.
     */
    private void loadExistingTrafficFlowData(int year, Day day, Network network) {
        List<String> allModes = Arrays.asList("car", "walk", "bike");

        for (String mode : allModes) {
            fileManager.loadTrafficFlowDataIfExists(year, day, mode, trafficFlowsByDayModeLinkHour, network);
        }
    }

    /**
     * Determines which transport modes need traffic flow processing based on file existence.
     */
    private List<String> determineModesToProcess(int year, Day day) {
        List<String> allModes = Arrays.asList("car", "walk", "bike");
        List<String> modesToProcess = new ArrayList<>();

        for (String mode : allModes) {
            if (!fileManager.trafficFlowFileExists(year, day, mode)) {
                modesToProcess.add(mode);
            }
        }

        return modesToProcess;
    }

    private void writeTrafficFlowsToCSV(int year, Day day, String mode, Network network) {
        String outputDirectory = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName + "/" + year + "/";
        String filePath = outputDirectory + "traffic_flows_" + day + "_" + mode + ".csv";

        try (java.io.FileWriter writer = new java.io.FileWriter(filePath)) {
            // Write CSV header
            writer.write("linkId,hour,count\n");

            // Get the flow data for the current day and mode
            Map<Id<Link>, Map<Integer, Integer>> linkFlows = trafficFlowsByDayModeLinkHour.getOrDefault(day, new HashMap<>()).getOrDefault(mode, new HashMap<>());

            // Iterate through links and hours
            for (Map.Entry<Id<Link>, Map<Integer, Integer>> linkEntry : linkFlows.entrySet()) {
                Id<Link> linkId = linkEntry.getKey();
                Link link = network.getLinks().get(linkId);
                if (link == null) {
                    logger.warn("Link " + linkId + " not found in network.");
                    continue;
                }

                // Write flow counts for each hour
                for (Map.Entry<Integer, Integer> hourEntry : linkEntry.getValue().entrySet()) {
                    int hour = hourEntry.getKey();
                    int count = hourEntry.getValue();
                    writer.write(String.format("%s,%d,%d\n",
                            linkId.toString(), hour, count));
                }
            }

            logger.info("Wrote traffic flows to " + filePath);
        } catch (java.io.IOException e) {
            logger.error("Failed to write traffic flows CSV: " + filePath, e);
        }
    }

    private void loadLinkInfoFromFile(Day day) {
        // Check cache first to avoid repeated loading
        if (linkInfoLoadedCache.getOrDefault(day, false)) {
            logger.info("Link info for {} already loaded from cache", day);
            return;
        }

        String outputDirectory = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName + "/";

        new LinkInfoReader().readConcentrationData(((DataContainerHealth)dataContainer), outputDirectory + "linkConcentration_" + day + ".csv");

//        // concentration from bus vehicle not evaluated for Melbourne
//        new LinkInfoReader().readConcentrationData(((DataContainerHealth)dataContainer), properties.healthData.busLinkConcentration);

        new LinkInfoReader().readNoiseLevelData(((DataContainerHealth)dataContainer), outputDirectory + "matsim/" + latestMatsimYear, day);

        // Mark as loaded in cache
        linkInfoLoadedCache.put(day, true);
        logger.info("Initialized Link Info for " + ((DataContainerHealth) dataContainer).getLinkInfo().size() + " links (cached for reuse)");
    }

    private void loadActivityLocationInfoFromFile(Day day) {
        // Check cache first to avoid repeated loading
        if (activityLocationInfoLoadedCache.getOrDefault(day, false)) {
            logger.info("Activity location info for {} already loaded from cache", day);
            return;
        }

        String outputDirectory = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName + "/";

        ActivityLocationInfoReader reader = new ActivityLocationInfoReader();
        reader.readConcentrationData(((DataContainerHealth)dataContainer), outputDirectory + "locationConcentration_" + day + ".csv");

        //we produced concentration from bus vehicle source at location level, currently it is static over days and scenarios.
        //So add this as an additional concentration to activity location
        reader.readConcentrationData(((DataContainerHealth)dataContainer), properties.healthData.busLocationConcentration);

        reader.readNoiseLevelData(((DataContainerHealth)dataContainer), outputDirectory + "matsim/" + latestMatsimYear + "/" + day +  "/car/noise-analysis/immissions/");

        // Mark as loaded in cache
        activityLocationInfoLoadedCache.put(day, true);
        logger.info("Activity location info for {} loaded and cached for reuse", day);
    }

    private void healthDataAssembler(int year, Day day, Mode mode) {
        logger.info("Updating health data for year " + year + "|day: " + day + "|mode: " + mode + ".");

        final String outputDirectoryRoot = properties.main.baseDirectory + "scenOutput/"
                + properties.main.scenarioName + "/matsim/" + latestMatsimYear;

        scenario = ScenarioUtils.createMutableScenario(initialMatsimConfig);
        ScenarioUtils.loadScenario(scenario);

        if (mode.equals(Mode.walk) || mode.equals(Mode.bicycle)) {
            Network activeNetwork = extractModeSpecificNetwork(scenario.getNetwork(),new HashSet<>(Arrays.asList(TransportMode.bike, TransportMode.walk)));
            scenario.setNetwork(activeNetwork);
        }

        scenario.getConfig().routing().setRoutingRandomness(0);
        scenario.getConfig().controller().setOutputDirectory(outputDirectoryRoot);

        //TODO: currently we don't have decent pt simulation. so pt trips has no actual routes.
        // Simple approach is used to roughly calculate exposures while pt (access, egress and bus-part) exposure. rail part is ignored
        if(mode.equals(Mode.pt)){
            calculateTripHealthIndicatorPt(new ArrayList<>(mitoTrips.values()), day, mode);
        }else{
            calculateTripHealthIndicator(new ArrayList<>(mitoTrips.values()), day, mode);
        }
    }

    private void calculateTripHealthIndicatorPt(ArrayList<Trip> trips, Day day, Mode mode) {
        logger.info("Updating trip health data for mode " + mode + ", day " + day);
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int processorsToUse = Math.min(availableProcessors, 14);
        final int partitionSize = (int) ((double) trips.size() / processorsToUse);
        Iterable<List<Trip>> partitions = Iterables.partition(trips, partitionSize);
        logger.info("  - {} partitions across {}/{} available processors.", availableProcessors,processorsToUse);
        ConcurrentExecutor<Void> executor = ConcurrentExecutor.fixedPoolService(processorsToUse);
        // Progress tracking variables
        final int totalTrips = trips.size();
        final AtomicInteger processedTrips = new AtomicInteger(0);
        AtomicInteger NO_PATH_TRIP = new AtomicInteger();
        final int logInterval = Math.max(1, totalTrips / 20); // Log progress about 20 times (5% intervals)
        logger.info(String.format("Processing %d trips for %s, %s", totalTrips, day, mode));
        for (final List<Trip> partition : partitions) {

            executor.addTaskToQueue(() -> {
                try {
                    for (Trip trip : partition) {

                        Person siloPerson = dataContainer.getHouseholdDataManager().getPersonFromId(trip.getPerson());
                        MitoGender gender = MitoGender.valueOf(siloPerson.getGender().toString());
                        int age = Math.min(siloPerson.getAge(),100);
                        double walkSpeed = ((DataContainerHealth)dataContainer).getAvgSpeeds().get(Mode.walk).get(gender).get(age);

                        Zone originZone = dataContainer.getGeoData().getZones().get(trip.getTripOriginZone());
                        Zone destinationZone = dataContainer.getGeoData().getZones().get(trip.getTripDestinationZone());

                        double accessTime_s = dataContainer.getTravelTimes().getTravelTime(originZone,destinationZone,3600 * 8, "ptAccess");
                        double egressTime_s = dataContainer.getTravelTimes().getTravelTime(originZone,destinationZone,3600 * 8, "ptEgress");
                        double totalTravelTime_s = dataContainer.getTravelTimes().getTravelTime(originZone,destinationZone,3600 * 8, "ptTotalTravelTime");
                        double totalInVehicleTime_s = (totalTravelTime_s - accessTime_s - egressTime_s);
                        double busInVehicleTime_s =  totalInVehicleTime_s * dataContainer.getTravelTimes().getTravelTime(originZone,destinationZone,3600 * 8, "ptBusTimeShare");

                        if(Double.isInfinite(accessTime_s) || Double.isInfinite(egressTime_s)||Double.isInfinite(totalTravelTime_s)){
                            NO_PATH_TRIP.incrementAndGet();
                            continue;
                        }
                        // update access egress time based on person's walk speed;
                        // default beeline walk speed in MATSim is 3.0 km/h.
                        // it returns beeline distance. apply detour factor 1.2
                        accessTime_s = (accessTime_s * (3.0 / 3.6) * 1.2) / walkSpeed;
                        egressTime_s = (egressTime_s * (3.0 / 3.6) * 1.2) / walkSpeed;

                        int departureTimeInSeconds = trip.getDepartureTimeInMinutes() * 60;
                        processPtLegExposures(trip, Mode.walk, accessTime_s * walkSpeed, accessTime_s, departureTimeInSeconds);
                        if (busInVehicleTime_s > 0) {
                            processPtLegExposures(trip, Mode.bus,  -1, busInVehicleTime_s, departureTimeInSeconds + accessTime_s);
                        }

                        // rail/train part currently no exposure processed, but need to add up travel time
                        trip.updateMatsimTravelTime(totalInVehicleTime_s-busInVehicleTime_s);
                        ((PersonHealth) siloPerson).updateWeeklyTravelSeconds((float) (totalInVehicleTime_s-busInVehicleTime_s));

                        processPtLegExposures(trip, Mode.walk, egressTime_s * walkSpeed, egressTime_s, departureTimeInSeconds + accessTime_s + totalInVehicleTime_s);

                        if(trip.isHomeBased()) {
                            calculateActivityExposures(trip);
                            int returnDepartureTimeInSeconds = trip.getDepartureReturnInMinutes()*60;

                            accessTime_s = dataContainer.getTravelTimes().getTravelTime(destinationZone,originZone,3600 * 8, "ptAccess");
                            egressTime_s = dataContainer.getTravelTimes().getTravelTime(destinationZone,originZone,3600 * 8, "ptEgress");
                            totalTravelTime_s = dataContainer.getTravelTimes().getTravelTime(destinationZone,originZone,3600 * 8, "ptTotalTravelTime");
                            totalInVehicleTime_s = (totalTravelTime_s - accessTime_s - egressTime_s);
                            busInVehicleTime_s =  totalInVehicleTime_s * dataContainer.getTravelTimes().getTravelTime(destinationZone,originZone,3600 * 8, "ptBusTimeShare");

                            if(Double.isInfinite(totalTravelTime_s)||Double.isInfinite(accessTime_s) || Double.isInfinite(egressTime_s)){
                                NO_PATH_TRIP.incrementAndGet();
                                continue;
                            }
                            // update access egress time based on person's walk speed;
                            // default beeline walk speed in MATSim is 3.0 km/h.
                            // it returns beeline distance. apply detour factor 1.2
                            accessTime_s = (accessTime_s * (3.0 / 3.6) * 1.2) / walkSpeed;
                            egressTime_s = (egressTime_s * (3.0 / 3.6) * 1.2) / walkSpeed;

                            processPtLegExposures(trip, Mode.walk, accessTime_s * walkSpeed, accessTime_s, returnDepartureTimeInSeconds);
                            if (busInVehicleTime_s > 0) {
                                processPtLegExposures(trip, Mode.bus,  -1, busInVehicleTime_s, returnDepartureTimeInSeconds + accessTime_s);
                            }

                            // rail/train part currently no exposure processed, but need to add up travel time
                            trip.updateMatsimTravelTime(totalInVehicleTime_s-busInVehicleTime_s);
                            ((PersonHealth) siloPerson).updateWeeklyTravelSeconds((float) (totalInVehicleTime_s-busInVehicleTime_s));

                            processPtLegExposures(trip, Mode.walk, egressTime_s * walkSpeed, egressTime_s, returnDepartureTimeInSeconds + accessTime_s + totalInVehicleTime_s);

                        }
                        // Update progress counter and log at intervals
                        int current = processedTrips.incrementAndGet();
                        if (current % logInterval == 0 || current == totalTrips) {
                            double percentage = 100.0 * current / totalTrips;
                            logger.info(String.format("%s, %s: Processed %d of %d trips (%.1f%%)",
                                    day, mode, current, totalTrips, percentage));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    logger.warn(e.getLocalizedMessage());
                    throw new RuntimeException(e);
                }
                return null;
            });
        }
        executor.execute();

        logger.info("No path trips for mode " + mode + " : " + NO_PATH_TRIP.get());
    }

    private void processPtLegExposures(Trip trip, Mode legMode, double legDist_m, double legTime_s, double startTimeInSecond) {

        double legMarginalMetHours = 0.;

        float[] legExposurePm25ByHour = new float[24*7];
        float[] legExposureNo2ByHour = new float[24*7];
        double legExposurePm25 = 0.;
        double legExposureNo2 = 0.;

        float[] hourOccupied = new float[24*7];

        //Physical activity (assume access and egress walk)
        double legMarginalMet = PhysicalActivity.getMMet(legMode, legDist_m, legTime_s, null);
        legMarginalMetHours = legMarginalMet * legTime_s / 3600.;


        //Air Pollutant
        int dayCode = trip.getDepartureDay().getDayCode();

        double startDayHour = startTimeInSecond / 3600.;
        double endDayHour = (startTimeInSecond + legTime_s)/ 3600.;

        for(double currentDayHour = startDayHour; currentDayHour < endDayHour;) {
            if(currentDayHour >= 24){
                dayCode++;
                currentDayHour = currentDayHour - 24;
                endDayHour = endDayHour - 24;
            }

            int exactDayHour = (int) currentDayHour;
            int nextDayHour = exactDayHour + 1;
            double durationInThisHour = Math.min(endDayHour, nextDayHour) - currentDayHour;

            int exactWeekHour = exactDayHour + 24 * dayCode;

            if(exactWeekHour > 167){
                break;
            }

            hourOccupied[exactWeekHour] += (float) durationInThisHour;

            double legPartExposurePm25 = PollutionExposure.getLinkExposurePm25(legMode, properties.get().healthData.DEFAULT_ROAD_TRAFFIC_INCREMENTAL_PM25, legTime_s, legMarginalMet);
            double legPartExposureNo2 = PollutionExposure.getLinkExposureNo2(legMode, properties.get().healthData.DEFAULT_ROAD_TRAFFIC_INCREMENTAL_NO2,legTime_s, legMarginalMet);

            legExposurePm25ByHour[exactWeekHour] += legPartExposurePm25;
            legExposureNo2ByHour[exactWeekHour] += legPartExposureNo2;

            legExposurePm25 += legPartExposurePm25;
            legExposureNo2 += legPartExposureNo2;

            currentDayHour = nextDayHour;
        }

        trip.updateMatsimTravelTime(legTime_s);
        trip.updateMarginalMetHours(legMarginalMetHours);
        trip.updateTravelExposureMap(Map.of(
                "pm2.5", (float) legExposurePm25,
                "no2", (float) legExposureNo2
        ));

        PersonHealth siloPerson = ((PersonHealth)dataContainer.getHouseholdDataManager().getPersonFromId(trip.getPerson()));
        siloPerson.updateWeeklyTravelSeconds((float) legTime_s);
        siloPerson.updateWeeklyMarginalMetHours(legMode, (float) legMarginalMetHours);
        siloPerson.updateWeeklyPollutionExposuresByHour(Map.of(
                "pm2.5", legExposurePm25ByHour,
                "no2", legExposureNo2ByHour
        ));
        siloPerson.updateWeeklyTravelActivityHourOccupied(hourOccupied);
    }

    private void calculateTripHealthIndicator(List<Trip> trips, Day day, Mode mode) {
        logger.info("Updating trip health data for mode " + mode + ", day " + day);
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int processorsToUse = Math.min(availableProcessors, 14);
        final int partitionSize = (int) ((double) trips.size() / processorsToUse);
        logger.info("  - {} partitions across {}/{} available processors.", availableProcessors,processorsToUse);
        Iterable<List<Trip>> partitions = Iterables.partition(trips, partitionSize);

        TravelTime travelTime;
        TravelDisutility travelDisutility;

        EnumMap<Mode, EnumMap<MitoGender, Map<Integer,Double>>> allSpeeds = ((DataContainerHealth)dataContainer).getAvgSpeeds();
        VehiclesFactory fac = VehicleUtils.getFactory();

        switch (mode){
            case autoDriver:
            case autoPassenger:
                String eventsFile = scenario.getConfig().controller().getOutputDirectory() + "/" + day + "/car/" + latestMatsimYear + ".output_events.xml.gz";
                travelTime = TravelTimeUtils.createTravelTimesFromEvents(scenario.getNetwork(),scenario.getConfig(), eventsFile);
                travelDisutility = ControlerDefaults.createDefaultTravelDisutilityFactory(scenario).createTravelDisutility(travelTime);
                break;
            case walk:
                WalkConfigGroup walkConfigGroup = new WalkConfigGroup();
                fillConfigWithWalkStandardValue(walkConfigGroup);
                //without remove, throw run time exception "Module xx exists already".
                scenario.getConfig().removeModule("walk");
                scenario.getConfig().addModule(walkConfigGroup);
                // set vehicles
                for(MitoGender gender : MitoGender.values()) {
                    for(int age = 0 ; age <= 100 ; age++) {
                        VehicleType walk = fac.createVehicleType(Id.create(TransportMode.walk + gender + age, VehicleType.class));
                        walk.setMaximumVelocity(allSpeeds.get(Mode.walk).get(gender).get(age));
                        walk.setNetworkMode(TransportMode.walk);
                        walk.setPcuEquivalents(0.);
                        scenario.getVehicles().addVehicleType(walk);
                    }
                }
                travelTime = new WalkTravelTime(new WalkLinkSpeedCalculatorImpl(scenario.getConfig()));
                travelDisutility = new ActiveDisutilityPrecalc(scenario.getNetwork(),walkConfigGroup,travelTime);
                break;
            case bicycle:
                BicycleConfigGroup bicycleConfigGroup = new BicycleConfigGroup();
                fillConfigWithBikeStandardValue(bicycleConfigGroup);
                scenario.getConfig().removeModule("bike");
                scenario.getConfig().addModule(bicycleConfigGroup);
                // set vehicles
                for(MitoGender gender : MitoGender.values()) {
                    for(int age = 0 ; age <= 100 ; age++) {
                        VehicleType bicycle = fac.createVehicleType(Id.create(TransportMode.bike + gender + age, VehicleType.class));
                        bicycle.setMaximumVelocity(allSpeeds.get(Mode.bicycle).get(gender).get(age));
                        bicycle.setNetworkMode(TransportMode.bike);
                        bicycle.setPcuEquivalents(0.);
                        scenario.getVehicles().addVehicleType(bicycle);
                    }
                }
                // Create the precomp factor provider once
                BicycleLinkSpeedCalculatorPrecomp factorProvider = new BicycleLinkSpeedCalculatorPrecomp(scenario.getConfig());

                // Create the travel time calculator using precomputation
                travelTime = new BicycleTravelTimePreComp(scenario.getNetwork(), factorProvider);
                //travelTime = new BicycleTravelTime(new BicycleLinkSpeedCalculatorImpl(scenario.getConfig()));
                travelDisutility = new ActiveDisutilityPrecalc(scenario.getNetwork(),bicycleConfigGroup,travelTime);
                break;
            default:
                travelTime = null;
                travelDisutility = null;
                logger.error("No travel time/disutility for mode: " + mode);
        }

        ConcurrentExecutor<Void> executor = ConcurrentExecutor.fixedPoolService(processorsToUse);
        // Progress tracking variables
        final int totalTrips = trips.size();
        final AtomicInteger processedTrips = new AtomicInteger(0);
        final int logInterval = Math.max(1, totalTrips / 20); // Log progress about 20 times (5% intervals)
        logger.info(String.format("Processing %d trips for %s, %s", totalTrips, day, mode));
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger NO_PATH_TRIP = new AtomicInteger();
        for (final List<Trip> partition : partitions) {

            LeastCostPathCalculator pathCalculator = new SpeedyALTFactory().createPathCalculator(scenario.getNetwork(), travelDisutility, travelTime);
            PopulationFactory factory = PopulationUtils.getFactory();
            executor.addTaskToQueue(() -> {
                try {
                    for (Trip trip : partition) {
                        Node originNode = NetworkUtils.getNearestNode(scenario.getNetwork(), trip.getTripOrigin());
                        Node destinationNode = NetworkUtils.getNearestNode(scenario.getNetwork(), trip.getTripDestination());

                        // Calculate exposures for outbound path
                        int outboundDepartureTimeInSeconds = trip.getDepartureTimeInMinutes()*60;

                        // Create person and vehicle for each person (i.e., trip) for active traveller
                        Vehicle vehicle = null;
                        org.matsim.api.core.v01.population.Person person = null;
                        if(mode.equals(Mode.walk)||mode.equals(Mode.bicycle)) {
                            Person siloPerson = dataContainer.getHouseholdDataManager().getPersonFromId(trip.getPerson());
                            MitoGender gender = MitoGender.valueOf(siloPerson.getGender().toString());
                            int age = siloPerson.getAge();

                            person = factory.createPerson(Id.createPersonId(trip.getId()));
                            person.getAttributes().putAttribute("purpose",trip.getTripPurpose());
                            person.getAttributes().putAttribute("sex",gender.toString());
                            person.getAttributes().putAttribute("age",age);


                            Id<Vehicle> vehicleId = Id.createVehicleId(person.getId().toString());
                            String key = (mode.equals(Mode.walk)? TransportMode.walk : TransportMode.bike) + gender + age;
                            VehicleType vehicleType = scenario.getVehicles().getVehicleTypes().get(Id.create(key, VehicleType.class));
                            vehicle = fac.createVehicle(vehicleId,vehicleType);
                        }

                        LeastCostPathCalculator.Path outboundPath = pathCalculator.calcLeastCostPath(originNode, destinationNode, outboundDepartureTimeInSeconds, person, vehicle);
                        if(outboundPath == null){
                            logger.warn("trip id: " + trip.getId() + ", trip depart time: " + trip.getDepartureTimeInMinutes() +
                                    "origin coord: [" + trip.getTripOrigin().getX() + "," + trip.getTripOrigin().getY() + "], " +
                                    "dest coord: [" + trip.getTripDestination().getX() + "," + trip.getTripDestination().getY() + "], " +
                                    "origin node: " + originNode + ", dest node: " + destinationNode);
                            NO_PATH_TRIP.getAndIncrement();
                        } else {
                            calculatePathExposures(trip,outboundPath,outboundDepartureTimeInSeconds,travelTime, vehicle);
                        }

                        // Calculate exposures for activity & return trip (home-based trips only)
                        // TODO: exposure for activity of non-home-based trips and RRT, currently we do not know their activity duration, so it is not calculated
                        if(trip.isHomeBased()) {
                            calculateActivityExposures(trip);
                            int returnDepartureTimeInSeconds = trip.getDepartureReturnInMinutes()*60;
                            LeastCostPathCalculator.Path returnPath = pathCalculator.calcLeastCostPath(destinationNode, originNode,returnDepartureTimeInSeconds,person,vehicle);
                            if(returnPath == null){
                                logger.warn("trip id: " + trip.getId() + ", trip depart time: " + trip.getDepartureTimeInMinutes() +
                                        "origin coord: [" + trip.getTripOrigin().getX() + "," + trip.getTripOrigin().getY() + "], " +
                                        "dest coord: [" +  trip.getTripDestination().getX() + "," + trip.getTripDestination().getY() + "], " +
                                        "origin node: " + originNode + ", dest node: " + destinationNode);
                                NO_PATH_TRIP.getAndIncrement();
                            } else {
                                calculatePathExposures(trip,returnPath,returnDepartureTimeInSeconds,travelTime, vehicle);
                            }
                        }
                        // Update progress counter and log at intervals
                        int current = processedTrips.incrementAndGet();
                        if (current % logInterval == 0 || current == totalTrips) {
                            double percentage = 100.0 * current / totalTrips;
                            logger.info(String.format("%s, %s: Processed %d of %d trips (%.1f%%)",
                                    day, mode, current, totalTrips, percentage));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    logger.warn(e.getLocalizedMessage());
                    throw new RuntimeException(e);
                }
                return null;
            });

            //partition.clear();
            //System.gc();
        }
        executor.execute();

        logger.info("No path trips for mode " + mode + " : " + NO_PATH_TRIP.get());

    }

    private void calculatePathExposures(Trip trip, LeastCostPathCalculator.Path path, int departureTimeInSecond, TravelTime travelTime, Vehicle vehicle) {

        Mode mode = trip.getTripMode();

        double pathLength = 0;
        double pathTime = 0;
        double pathMarginalMetHours = 0;

        float[] pathExposurePm25ByHour = new float[24*7];
        float[] pathExposureNo2ByHour = new float[24*7];
        float[] pathExposureNoiseByHour = new float[24*7];
        double pathExposurePm25 = 0.;
        double pathExposureNo2 = 0.;
        double pathExposureNoise = 0.;

        double pathExposureGreen = 0.;

        // Injury variables
        // Munich
        double pathSevereInjuryRisk = 0;
        double pathFatalityRisk = 0;

        // Manchester
        double pathInjuryRisk = 0.0;
        Day currentDay; // by default
        if(trip.getDepartureDay().equals(Day.saturday) || trip.getDepartureDay().equals(Day.sunday)){
            currentDay = trip.getDepartureDay();
        }else{
            currentDay = Day.thursday;
        }

        float[] hourOccupied = new float[24*7];

        List<VisitedLink> visitedLinksPath = new ArrayList<>();

        // Pre-compute mode-specific values
        final String modeAdjusted = getAdjustedModeName(mode);
        final boolean isWeekday = weekdays.contains(trip.getDepartureDay());
        final int dayCode = trip.getDepartureDay().getDayCode();

        // Cache person data once per trip
        final Person tripPerson = dataContainer.getHouseholdDataManager().getPersonFromId(trip.getPerson());
        final int agePerson = tripPerson.getAge();
        final Gender genderPerson = tripPerson.getGender();

        for(Link link : path.links) {
            double enterTimeInSecond = (double) departureTimeInSecond + pathTime;

            // Update counts for traffic flows estimation
            int hour = (int) (enterTimeInSecond / 3600) % 24;
            trafficFlowsByDayModeLinkHour.get(trip.getDepartureDay())
                    .get(modeAdjusted)
                    .computeIfAbsent(link.getId(), k -> new ConcurrentHashMap<>())
                    .merge(hour, 1, Integer::sum);

            double linkLength = link.getLength();
            double linkTime = travelTime.getLinkTravelTime(link,enterTimeInSecond,null,vehicle);

            // Melbourne
            double linkInjuryRisk = 0.;

            double linkMarginalMetHour = 0.;
            double linkExposureGreen = 0.;
            double linkExposurePm25 = 0.;
            double linkExposureNo2 = 0.;
            double linkExposureNoise = 0.;

            LinkInfo linkInfo = ((DataContainerHealth)dataContainer).getLinkInfo().get(link.getId());

            // INJURY
            //double[] severeFatalRisk = getLinkSevereFatalInjuryRisk(mode, (int) (enterTimeInSecond / 3600.), linkInfo);
            //linkSevereInjuryRisk = severeFatalRisk[0];
            //linkFatalityRisk = severeFatalRisk[1];
            LinkInfo linkInfoByDay = ((HealthDataContainerImpl) dataContainer).getLinkInfoByDay(currentDay).get(link.getId());

            // PHYSICAL ACTIVITY
            double linkMarginalMet = PhysicalActivity.getMMet(mode, linkLength, linkTime, link);
            linkMarginalMetHour = linkMarginalMet * linkTime / 3600.;

            // NDVI
            linkExposureGreen = linkInfo.getNdvi() * linkTime / 3600.;

            if(linkInfo!=null) {

                double startDayHour = enterTimeInSecond / 3600.;
                double endDayHour = (enterTimeInSecond + linkTime)/ 3600.;
                int currentDayCode = dayCode;

                for(double currentDayHour = startDayHour; currentDayHour < endDayHour;) {
                    //check if start hour is already next day, it could be that trip starts at 23:30, after travelling (e.g. 40 mins), activity start time is next day
                    //the limitation is already we move it to next day, the AP and noise data is still retrieved from the current day. because to save memory, we handle trips day by day and only retrieve AP noise data of the corresponding day
                    //so it might slightly overestimate the personal exposure, if we use weekday for (next day) saturday
                    if(currentDayHour >= 24){
                        currentDayCode++;
                        currentDayHour = currentDayHour - 24;
                        endDayHour = endDayHour - 24;
                    }

                    int exactDayHour = (int) currentDayHour;
                    int nextDayHour = exactDayHour + 1;
                    double durationInThisHour = Math.min(endDayHour, nextDayHour) - currentDayHour;

                    int exactWeekHour = exactDayHour + 24 * currentDayCode;

                    if(exactWeekHour > 167){
                        break;
                    }

                    hourOccupied[exactWeekHour] += (float) durationInThisHour;

                    // AIR POLLUTION
                    double linkConcentrationPm25 = linkInfo.getExposure2Pollutant2TimeBin().getOrDefault(Pollutant.PM2_5,new OpenIntFloatHashMap()).get(exactDayHour) +
                            linkInfo.getExposure2Pollutant2TimeBin().getOrDefault(Pollutant.PM2_5_non_exhaust,new OpenIntFloatHashMap()).get(exactDayHour);
                    double linkConcentrationNo2 = linkInfo.getExposure2Pollutant2TimeBin().getOrDefault(Pollutant.NO2,new OpenIntFloatHashMap()).get(exactDayHour);

                    linkExposurePm25 = PollutionExposure.getLinkExposurePm25(mode, linkConcentrationPm25, durationInThisHour * 3600, linkMarginalMet);
                    linkExposureNo2 =PollutionExposure.getLinkExposureNo2(mode, linkConcentrationNo2, durationInThisHour * 3600, linkMarginalMet);

                    pathExposurePm25ByHour[exactWeekHour] += (float) linkExposurePm25;
                    pathExposureNo2ByHour[exactWeekHour] += (float) linkExposureNo2;

                    //TODO: bike/walk-only link has no noise emission (noise produced on that link). currently we assume 0 noise level while travelling on those links.
                    // Later, we can do geo-spatialling and associate these link to nearest car link? or Instead of using noise emission, we consider each link as noise receivers and do proper noise exposure
                    if(!linkInfo.getNoiseLevel2TimeBin().isEmpty()){
                        linkExposureNoise = linkInfo.getNoiseLevel2TimeBin().get(exactDayHour) * durationInThisHour;
                        pathExposureNoiseByHour[exactWeekHour] += (float) linkExposureNoise;
                    }

                    pathExposurePm25 += linkExposurePm25;
                    pathExposureNo2 += linkExposureNo2;
                    pathExposureNoise += linkExposureNoise;

                    currentDayHour = nextDayHour;
                }

            } else{
                logger.warn("No link info found for link id " + link.getId());
            }

            pathLength += linkLength;
            pathTime += linkTime;

            // INJURIES
            visitedLinksPath.add(new VisitedLink(link.getId(), (int) enterTimeInSecond/3600, trip.getDepartureDay(), modeAdjusted));

            if(linkInfoByDay != null) {
                // Injuries
                if (weekdays.contains(trip.getDepartureDay())){
                    linkInjuryRisk = getLinkInjuryRisk(mode, (int) enterTimeInSecond, linkInfoByDay)/5;
                } else {
                    linkInjuryRisk = getLinkInjuryRisk(mode, (int) enterTimeInSecond, linkInfoByDay);
                }

                double AgeGenderRR = 1.;
                AgeGenderRR = getCasualtyRR_byAge_Gender(genderPerson, agePerson, trip.getTripMode());
                pathInjuryRisk += (linkInjuryRisk * AgeGenderRR);
            }

            pathMarginalMetHours += linkMarginalMetHour;
            pathExposureGreen += linkExposureGreen;
        }

        trip.updateMatsimTravelDistance(pathLength);
        trip.updateMatsimTravelTime(pathTime);
        trip.updateMatsimLinkCount(path.links.size());

        trip.updateMarginalMetHours(pathMarginalMetHours);

        trip.updateTravelRiskMap(Map.of("severeFatalInjury", (float) 0));

        trip.updateTravelExposureMap(Map.of(
                "pm2.5", (float) pathExposurePm25,
                "no2", (float) pathExposureNo2
        ));
        trip.updateTravelNoiseExposure(pathExposureNoise);
        trip.updateTravelNdviExposure(pathExposureGreen);

        PersonHealth siloPerson = ((PersonHealth)dataContainer.getHouseholdDataManager().getPersonFromId(trip.getPerson()));
        siloPerson.updateWeeklyTravelSeconds((float) pathTime);

        // Injuries
        ((PersonHealthMEL) siloPerson).addVisitedLinks(visitedLinksPath);
        visitedLinksPath.clear();

        siloPerson.updateWeeklyMarginalMetHours(trip.getTripMode(), (float) pathMarginalMetHours);
        siloPerson.updateWeeklyPollutionExposuresByHour(Map.of(
                "pm2.5", pathExposurePm25ByHour,
                "no2", pathExposureNo2ByHour
        ));
        siloPerson.updateWeeklyNoiseExposuresByHour(pathExposureNoiseByHour);
        siloPerson.updateWeeklyGreenExposures((float) pathExposureGreen);
        siloPerson.updateWeeklyTravelActivityHourOccupied(hourOccupied);
    }

    /**
     * Retrieves the relative risk of casualty for a given gender, age, and transportation mode.
     *
     * @param gender The gender of the individual
     * @param age The age of the individual (will be clamped to 0-100)
     * @param mode The trip mode
     * @return The relative risk as a double value
     * @throws IllegalArgumentException if gender or mode is null
     */

    double getCasualtyRR_byAge_Gender(Gender gender, int age, Mode mode) {
        // Parameter validation
        if (gender == null || mode == null) {
            throw new IllegalArgumentException("Gender and mode cannot be null");
        }

        // Determine mode string
        String modeStr;
        switch (mode) {
            case autoDriver:
            case autoPassenger:
                modeStr = "Driver";
                break;
            case bicycle:
                modeStr = "Cycle";
                break;
            case walk:
                modeStr = "Walk";
                break;
            default:
                logger.warn("Impossible to compute injury relative risk for mode " + mode);
                return 1.0; // Consider if this is the appropriate default
        }

        // Determine age group
        String ageGroup;
        if (age < 17) {
            ageGroup = "< 17";
        } else if (age <= 20) {
            ageGroup = "17-20";
        } else if (age <= 29) {
            ageGroup = "21-29";
        } else if (age <= 39) {
            ageGroup = "30-39";
        } else if (age <= 49) {
            ageGroup = "40-49";
        } else if (age <= 59) {
            ageGroup = "50-59";
        } else if (age <= 69) {
            ageGroup = "60-69";
        } else {
            ageGroup = "70+";
        }

        // Safely retrieve the value
        try {
            return ((HealthDataContainerImpl) dataContainer)
                    .getHealthInjuryRRdata()
                    .get(modeStr)
                    .get(gender)
                    .get(ageGroup)
                    .rr;
        } catch (NullPointerException e) {
            logger.error("Missing data for mode: " + modeStr + ", gender: " + gender + ", age: " + age, e);
            return 1.0; // Or consider throwing an exception
        }
    }

    /**
     * Helper method to safely extract a specific pollution exposure value from activity location data.
     * Treats missing exposure data as zero exposure
     *
     * @param activityLocation The activity location containing exposure data
     * @param dayHour The hour of day (0-23) for which to get exposure values
     * @param exposureType The type of exposure to retrieve ("PM25", "NO2")
     * @return The exposure value for the specified type, or zero if data is missing
     */
    private double getPollutionExposureValue(ActivityLocation activityLocation, int dayHour, String exposureType) {
        if (activityLocation == null) {
            return 0.0;
        }

        Map<Pollutant, OpenIntFloatHashMap> exposureMap = activityLocation.getExposure2Pollutant2TimeBin();
        if (exposureMap == null) {
            return 0.0;
        }

        switch (exposureType.toUpperCase()) {
            case "PM25":
                // PM2.5 exposure (exhaust + non-exhaust combined)
                double pm25Total = 0.0;
                OpenIntFloatHashMap pm25Map = exposureMap.get(Pollutant.PM2_5);
                if (pm25Map != null) {
                    pm25Total += pm25Map.get(dayHour);
                }
                OpenIntFloatHashMap pm25NonExhaustMap = exposureMap.get(Pollutant.PM2_5_non_exhaust);
                if (pm25NonExhaustMap != null) {
                    pm25Total += pm25NonExhaustMap.get(dayHour);
                }
                return pm25Total;

            case "NO2":
                // NO2 exposure
                OpenIntFloatHashMap no2Map = exposureMap.get(Pollutant.NO2);
                return no2Map != null ? no2Map.get(dayHour) : 0.0;

            default:
                logger.warn("Unknown pollution exposure type: {}", exposureType);
                return 0.0;
        }
    }

    private void calculateActivityExposures(Trip trip) {
        float[] hourOccupied = new float[24*7];
        float[] activityExposurePM25ByHour = new float[24*7];
        float[] activityExposureNo2ByHour = new float[24*7];
        float[] activityNoiseExposureByHour = new float[24*7];
        double activityGreenExposure = 0.;
        double activityExposurePM25 = 0.;
        double activityExposureNo2 = 0.;
        double activityNoiseExposure = 0.;

        double activityDurationInMinutes = trip.getActivityDuration();

        int dayCode = trip.getDepartureDay().getDayCode();
        double startDayHour = (trip.getDepartureTimeInMinutes() + trip.getMatsimTravelTime()/60.) / 60.;
        double endDayHour = (startDayHour + activityDurationInMinutes/60.);


        PersonHealth siloPerson =  ((PersonHealth)dataContainer.getHouseholdDataManager().getPersonFromId(trip.getPerson()));
        double sportweekmMETh =  siloPerson.getWeeklyMarginalMetHoursSport();

        for(double currentDayHour = startDayHour; currentDayHour < endDayHour;) {
            //check if start hour is already next day, it could be that trip starts at 23:30, after travelling (e.g. 40 mins), activity start time is next day
            if(currentDayHour >= 24){
                dayCode++;
                currentDayHour = currentDayHour - 24;
                endDayHour = endDayHour - 24;
            }
            int exactDayHour = (int) currentDayHour;
            int nextDayHour = exactDayHour + 1;
            double durationInThisHour = Math.min(endDayHour, nextDayHour) - currentDayHour;

            int exactWeekHour = exactDayHour + 24 * dayCode;

            if(exactWeekHour > 167){
                break;
            }
            hourOccupied[exactWeekHour] = (float) durationInThisHour;

            String rpId = getReceiverPointId(trip.getTripDestinationType(), trip.getTripDestinationMicroId());
            ActivityLocation activityLocation = ((DataContainerHealth)dataContainer).getActivityLocations().get(rpId);

            if(activityLocation != null) {

                double locationIncrementalPM25 = getPollutionExposureValue(activityLocation, exactDayHour, "PM25");
                double locationIncrementalNO2 = getPollutionExposureValue(activityLocation, exactDayHour, "NO2");
                // Air pollution
                double exposurePM25 = PollutionExposure.getActivityExposurePm25_newvent(durationInThisHour * 60, sportweekmMETh, locationIncrementalPM25);
                double exposureNo2 = PollutionExposure.getActivityExposureNo2_newvent(durationInThisHour * 60, sportweekmMETh, locationIncrementalNO2);
                activityExposurePM25 += exposurePM25;
                activityExposureNo2 += exposureNo2;

                // Noise level
                if(!activityLocation.getNoiseLevel2TimeBin().isEmpty()){
                    double noiseExposure = activityLocation.getNoiseLevel2TimeBin().get(exactDayHour) * durationInThisHour;
                    activityNoiseExposureByHour[exactWeekHour] = (float) noiseExposure;
                    activityNoiseExposure += noiseExposure;
                }

                //Green ndvi
                activityGreenExposure += activityLocation.getNdvi() * durationInThisHour;
            }else{
                logger.warn("No receiver point info found for rpId: " + rpId + " tripId: " + trip.getTripId());
            }

            currentDayHour = nextDayHour;
        }

        trip.updateDepartureReturnInMinutes((int)(endDayHour*60));
        trip.setActivityExposureMap(Map.of(
                "pm2.5", (float) activityExposurePM25,
                "no2", (float) activityExposureNo2
        ));
        trip.setActivityNoiseExposure(activityNoiseExposure);
        trip.setActivityNdviExposure(activityGreenExposure);

        siloPerson.updateWeeklyActivityMinutes((float) activityDurationInMinutes);
        siloPerson.updateWeeklyTravelActivityHourOccupied(hourOccupied);
        siloPerson.updateWeeklyPollutionExposuresByHour(Map.of(
                "pm2.5", activityExposurePM25ByHour,
                "no2", activityExposureNo2ByHour
        ));
        siloPerson.updateWeeklyNoiseExposuresByHour(activityNoiseExposureByHour);
        siloPerson.updateWeeklyGreenExposures((float) activityGreenExposure);

    }

    private void calculatePersonHealthExposuresAtHome(Day day) {
        for(Person person : dataContainer.getHouseholdDataManager().getPersons()) {

            double minutesAtHome = 0.;
            float[] exposurePM25 = new float[24*7];
            float[] exposureNo2 = new float[24*7];
            float[] exposureNoise = new float[24*7];
            double ndviExposure = 0.;

            float[] hourOccupied = ((PersonHealth) person).getWeeklyTravelActivityHourOccupied();

            for(int dayHour = 0; dayHour < 24; dayHour++) {
                int weekHour = dayHour + 24 * day.getDayCode();
                float remainingHour = 1.f - hourOccupied[weekHour];
                minutesAtHome += remainingHour * 60;

                if (remainingHour <= 0.) {
                    continue;
                }


                String rpId = ("dd" + person.getHousehold().getDwellingId());
                ActivityLocation activityLocation = ((DataContainerHealth)dataContainer).getActivityLocations().get(rpId);


                if(activityLocation != null) {
                    // Air pollution
                    double locationIncrementalPM25 = getPollutionExposureValue(activityLocation, dayHour, "PM25");
                    double locationIncrementalNO2 = getPollutionExposureValue(activityLocation, dayHour, "NO2");

                    // new ventilation - fix undefined remainingHour variable
                    exposurePM25[weekHour] = (float) PollutionExposure.getHomeExposurePm25_newvent(60, dayHour, locationIncrementalPM25);
                    exposureNo2[weekHour] = (float) PollutionExposure.getHomeExposureNo2_newvent(60, dayHour, locationIncrementalNO2);

                    // Noise level
                    if(!activityLocation.getNoiseLevel2TimeBin().isEmpty()){
                        exposureNoise[weekHour] = activityLocation.getNoiseLevel2TimeBin().get(dayHour) * 1.0f;
                    }

                    // Green ndvi
                    ndviExposure += activityLocation.getNdvi() * 1.0;

                }else{
                    logger.warn("No receiver point info found for rpId: " + rpId + " personId: " + person.getId());
                }
            }


            ((PersonHealth) person).updateWeeklyHomeMinutes((float) minutesAtHome);
            ((PersonHealth) person).updateWeeklyPollutionExposuresByHour(Map.of(
                    "pm2.5", exposurePM25,
                    "no2", exposureNo2
            ));
            ((PersonHealth) person).updateWeeklyNoiseExposuresByHour(exposureNoise);
            ((PersonHealth) person).updateWeeklyGreenExposures((float) ndviExposure);

        }
    }

    private void calculatePersonHealthExposureMetrics() {
        for(Person person : dataContainer.getHouseholdDataManager().getPersons()) {
            float sumHour = 0.f;
            float sumNightHour = 0.f;
            float sumExposurePM25_normalized = 0.f;
            float sumExposureNo2_normalized = 0.f;
            float sumExposureNoise = 0.f;
            float sumExposureNoiseNight = 0.f;

            Map<String, float[]> weeklyPollutionExposures = ((PersonHealth) person).getWeeklyPollutionExposures();
            float[] weeklyNoiseExposureByHour = ((PersonHealth) person).getWeeklyNoiseExposureByHour();
            float[] hourOccupied = ((PersonHealth) person).getWeeklyTravelActivityHourOccupied();


            for (int weekHour = 0;  weekHour < hourOccupied.length; weekHour++) {
                int dayHour = weekHour % 24;

                sumHour += Math.max(1, hourOccupied[weekHour]);

                double min_ventilation_rate = 0.;
                if (dayHour <= 7  || dayHour > 23 ){
                    //"minimum"  ventilation rate = 0.27 (v_sleep)
                    min_ventilation_rate = 0.27;
                } else {
                    //"minimum"  ventilation rate = 0.61 (v_rest)
                    min_ventilation_rate = 0.61;
                }

                sumExposurePM25_normalized += (float) (weeklyPollutionExposures.get("pm2.5")[weekHour]/Math.max(1, hourOccupied[weekHour])/min_ventilation_rate);
                sumExposureNo2_normalized += (float) (weeklyPollutionExposures.get("no2")[weekHour]/Math.max(1, hourOccupied[weekHour])/min_ventilation_rate);


                float hourlyNoiseLevel = (float) NoiseMetrics.getHourlyNoiseLevel(dayHour, (weeklyNoiseExposureByHour[weekHour]/Math.max(1, hourOccupied[weekHour])));
                sumExposureNoise += hourlyNoiseLevel;

                if (dayHour <= 7  || dayHour > 23 ){
                    sumNightHour += Math.max(1, hourOccupied[weekHour]);
                    sumExposureNoiseNight += hourlyNoiseLevel;
                }
            }



            ((PersonHealth) person).setWeeklyExposureByPollutantNormalised(
                    Map.of(
                            "pm2.5", (float) (sumExposurePM25_normalized / 168.),
                            "no2", (float) (sumExposureNo2_normalized / 168.)
                    )
            );

            float Lden = (float) (10 * Math.log10(sumExposureNoise / sumHour));
            float Lnight = (float) (10 * Math.log10(sumExposureNoiseNight / sumNightHour));
            ((PersonHealth) person).setWeeklyNoiseExposuresNormalised (Lden);
            ((PersonHealthMEL) person).setNoiseHighAnnoyedPercentage((float) NoiseMetrics.getHighAnnoyedPercentage(Lden));
            ((PersonHealthMEL) person).setNoiseHighSleepDisturbancePercentage((float) NoiseMetrics.getHighSleepDisturbancePercentage(Lnight));

            ((PersonHealth) person).setWeeklyGreenExposuresNormalised(((PersonHealthMEL) person).getWeeklyNdviExposure() / sumHour);
        }
    }

    public void calculateHomeBasedExposureOnly(int year){
        latestMatsimYear = year;
        processNdviData(((HealthDataContainerImpl) dataContainer).getNetwork());

        //assemble person home exposure by day by hour
        for(Day day : Day.values()) {
            loadActivityLocationInfoFromFile(weekdays.contains(day) ? Day.thursday : day);
            for (Person person : dataContainer.getHouseholdDataManager().getPersons()) {

                double minutesAtHome = 0.;
                float[] exposurePM25 = new float[24*7];
                float[] exposureNo2 = new float[24*7];
                float[] exposureNoise = new float[24*7];
                double ndviExposure = 0.;

                for (int dayHour = 0; dayHour < 24; dayHour++) {
                    int weekHour = dayHour + 24 * day.getDayCode();
                    minutesAtHome += 60;

                    String rpId = ("dd" + person.getHousehold().getDwellingId());
                    ActivityLocation activityLocation = ((DataContainerHealth) dataContainer).getActivityLocations().get(rpId);


                    if (activityLocation != null) {
                        // Use helper method to get pollution exposure values safely
                        double locationIncrementalPM25 = getPollutionExposureValue(activityLocation, dayHour, "PM25");
                        double locationIncrementalNO2 = getPollutionExposureValue(activityLocation, dayHour, "NO2");

                        // new ventilation - fix undefined remainingHour variable
                        exposurePM25[weekHour] = (float) PollutionExposure.getHomeExposurePm25_newvent(60, dayHour, locationIncrementalPM25);
                        exposureNo2[weekHour] = (float) PollutionExposure.getHomeExposureNo2_newvent(60, dayHour, locationIncrementalNO2);

                        // Noise level
                        if(!activityLocation.getNoiseLevel2TimeBin().isEmpty()){
                            exposureNoise[weekHour] = activityLocation.getNoiseLevel2TimeBin().get(dayHour) * 1.0f;
                        }

                        // Green ndvi
                        ndviExposure += activityLocation.getNdvi() * 1.0;

                    }else{
                        logger.warn("No receiver point info found for rpId: " + rpId + " personId: " + person.getId());
                    }
                }


                ((PersonHealth) person).updateWeeklyHomeMinutes((float) minutesAtHome);
                ((PersonHealth) person).updateWeeklyPollutionExposuresByHour(Map.of(
                        "pm2.5", exposurePM25,
                        "no2", exposureNo2
                ));
                ((PersonHealth) person).updateWeeklyNoiseExposuresByHour(exposureNoise);
                ((PersonHealth) person).updateWeeklyGreenExposures((float) ndviExposure);

            }

            ((DataContainerHealth)dataContainer).getActivityLocations().values().forEach(ActivityLocation::reset);
            System.gc();
        }


        //normalized person's home exposure over a week
        for(Person person : dataContainer.getHouseholdDataManager().getPersons()) {
            float sumHour = 168.f;
            float sumNightHour = 56.f;
            float sumExposurePM25_normalized = 0.f;
            float sumExposureNo2_normalized = 0.f;
            float sumExposureNoise = 0.f;
            float sumExposureNoiseNight = 0.f;

            Map<String, float[]> weeklyPollutionExposures = ((PersonHealth) person).getWeeklyPollutionExposures();
            float[] weeklyNoiseExposureByHour = ((PersonHealth) person).getWeeklyNoiseExposureByHour();

            for (int weekHour = 0;  weekHour < 168; weekHour++) {
                int dayHour = weekHour % 24;

                double min_ventilation_rate = 0.;
                if (dayHour <= 7){
                    //"minimum"  ventilation rate = 0.27 (v_sleep)
                    min_ventilation_rate = 0.27;
                } else {
                    //"minimum"  ventilation rate = 0.61 (v_rest)
                    min_ventilation_rate = 0.61;
                }

                sumExposurePM25_normalized += (float) (weeklyPollutionExposures.get("pm2.5")[weekHour]/min_ventilation_rate);
                sumExposureNo2_normalized += (float) (weeklyPollutionExposures.get("no2")[weekHour]/min_ventilation_rate);


                float hourlyNoiseLevel = (float) NoiseMetrics.getHourlyNoiseLevel(dayHour, (weeklyNoiseExposureByHour[weekHour]));
                sumExposureNoise += hourlyNoiseLevel;

                if (dayHour <= 7){
                    sumExposureNoiseNight += hourlyNoiseLevel;
                }
            }



            ((PersonHealth) person).setWeeklyExposureByPollutantNormalised(
                    Map.of(
                            "pm2.5", (float) (sumExposurePM25_normalized / sumHour),
                            "no2", (float) (sumExposureNo2_normalized / sumHour)
                    )
            );

            float Lden = (float) (10 * Math.log10(sumExposureNoise / sumHour));
            float Lnight = (float) (10 * Math.log10(sumExposureNoiseNight / sumNightHour));
            ((PersonHealth) person).setWeeklyNoiseExposuresNormalised (Lden);
            ((PersonHealthMEL) person).setNoiseHighAnnoyedPercentage((float) NoiseMetrics.getHighAnnoyedPercentage(Lden));
            ((PersonHealthMEL) person).setNoiseHighSleepDisturbancePercentage((float) NoiseMetrics.getHighSleepDisturbancePercentage(Lnight));

            ((PersonHealth) person).setWeeklyGreenExposuresNormalised(((PersonHealthMEL) person).getWeeklyNdviExposure() / sumHour);
        }
    }

    private void preComputeRiskValues(Day day, Network network) {

        Day dayForHealthData = weekdays.contains(day) ? Day.thursday : day;
        List<String> modes = Arrays.asList("car", "bike", "walk");

        long startTime = System.nanoTime();
        AtomicInteger totalComputations = new AtomicInteger(0);
        AtomicInteger nonZeroRiskCount = new AtomicInteger(0);

        // Process modes in parallel to reduce overall computation time
        modes.parallelStream().forEach(mode -> {
            // Use a regular HashMap for thread-local computation, then put once into ConcurrentHashMap
            Map<Id<Link>, Map<Integer, Double>> linkHourRiskMap = new HashMap<>();

            // Get all relevant links upfront to avoid repeated filtering
            List<Link> relevantLinks = network.getLinks().values().stream()
                .filter(link -> {
                    LinkInfo linkInfo = ((HealthDataContainerImpl) dataContainer)
                            .getLinkInfoByDay(dayForHealthData)
                            .get(link.getId());
                    return linkInfo != null;
                })
                .collect(Collectors.toList());

            // Process links in parallel for each mode
            Map<Id<Link>, Map<Integer, Double>> concurrentResults = relevantLinks.parallelStream()
                .collect(Collectors.toConcurrentMap(
                    Link::getId,
                    link -> {
                        LinkInfo linkInfo = ((HealthDataContainerImpl) dataContainer)
                                .getLinkInfoByDay(dayForHealthData)
                                .get(link.getId());

                        // Pre-allocate the hour map with known size
                        Map<Integer, Double> hourRiskMap = new HashMap<>(24);

                        for (int hour = 0; hour < 24; hour++) {
                            double linkRisk = getLinkInjuryRisk2(mode, hour, linkInfo);
                            hourRiskMap.put(hour, linkRisk);
                            totalComputations.incrementAndGet();

                            if (linkRisk > 0) {
                                nonZeroRiskCount.incrementAndGet();
                            }
                        }

                        return hourRiskMap;
                    }
                ));

            // Single atomic operation to update the main map
            preComputedRisksByModeLinkHour.put(mode, concurrentResults);
        });

        long endTime = System.nanoTime();
        double computationTimeSeconds = (endTime - startTime) / 1e9;

        logger.info("Pre-computed {} risk values for {} ({} non-zero risk links)",
                   totalComputations.get(), day, nonZeroRiskCount.get()
                );
    }

    /**
     * Optimized method to get pre-computed risk value, eliminating the getRiskValue2 bottleneck.
     * Returns cached risk value instead of computing it repeatedly.
     */
    private double getPreComputedRiskValue(String mode, Id<Link> linkId, int hour) {
        return preComputedRisksByModeLinkHour
                .getOrDefault(mode, new ConcurrentHashMap<>())
                .getOrDefault(linkId, new ConcurrentHashMap<>())
                .getOrDefault(hour, 0.0);
    }

    private String getReceiverPointId(String tripDestinationType, int tripDestinationMicroId) {
        if("household".equals(tripDestinationType)){
            int ddId = dataContainer.getHouseholdDataManager().getHouseholdFromId(tripDestinationMicroId).getDwellingId();
            return "dd" + ddId;
        }else if ("vacantDwelling".equals(tripDestinationType)){
            return "dd" + tripDestinationMicroId;
        }else if ("job".equals(tripDestinationType)){
            JobMEL job = (JobMEL)dataContainer.getJobDataManager().getJobFromId(tripDestinationMicroId);
            if(job != null) {
                if("poi".equals(job.getMicrolocationType())) {
                    return "job" + job.getMicroBuildingId();
                }else if("zoneCentroid".equals(job.getMicrolocationType())) {
                    return "zone" + job.getMicroBuildingId();
                }
            }
        }else if ("poi".equals(tripDestinationType)){
            return "poi" + tripDestinationMicroId;
        }else if ("zoneCentroid".equals(tripDestinationType)){
            return "zone" + tripDestinationMicroId;
        }else if ("school".equals(tripDestinationType)){
            return "ss" + tripDestinationMicroId;
        }else {
            logger.warn("Unknown receiver point type: {}", tripDestinationType);
        }

        return null;
    }

    private double getLinkInjuryRisk2(String mode, int time, LinkInfo linkInfo){
        double linkInjuryRisk = 0.;
        switch (mode) {
            case "car":
                linkInjuryRisk =
                        getRiskValue2(linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime(),
                                AccidentType.CAR_ONEWAY, time) +
                                getRiskValue2(linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime(),
                                        AccidentType.CAR_TWOWAY, time);
                break;
            case "bike":
                linkInjuryRisk =
                        getRiskValue2(linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime(),
                                AccidentType.BIKE_MAJOR, time) +
                                getRiskValue2(linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime(),
                                        AccidentType.BIKE_MINOR, time);
                break;
            case "walk":
                linkInjuryRisk =
                        getRiskValue2(linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime(),
                                AccidentType.PED, time);
                break;
            default:
                throw new RuntimeException("Undefined mode " + mode);
        }
        return linkInjuryRisk;
    }

    private double getLinkInjuryRisk(Mode mode, int time, LinkInfo linkInfo){
        double linkInjuryRisk = 0.;
        switch (mode) {
            case autoDriver:
            case autoPassenger:
                linkInjuryRisk =
                        getRiskValue(linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime(),
                                AccidentType.CAR_ONEWAY, time) +
                                getRiskValue(linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime(),
                                        AccidentType.CAR_TWOWAY, time);
                break;
            case bicycle:
                linkInjuryRisk =
                        getRiskValue(linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime(),
                                AccidentType.BIKE_MAJOR, time) +
                        getRiskValue(linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime(),
                                AccidentType.BIKE_MINOR, time);
                break;
            case walk:
                linkInjuryRisk =
                        getRiskValue(linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime(),
                                AccidentType.PED, time);
                break;
            default:
                throw new RuntimeException("Undefined mode " + mode);
        }
        return linkInjuryRisk;
    }

    // Helper method to safely get values from OpenIntFloatHashMap
    private float getRiskValue(Map<AccidentType, OpenIntFloatHashMap> exposureMap,
                               AccidentType type, float time) {
        if (exposureMap == null) return 0f;
        OpenIntFloatHashMap timeMap = exposureMap.get(type);
        if (timeMap == null) return 0f;
        return timeMap.get((int)(time / 3600.));
    }

    private double getRiskValue2(Map<AccidentType, OpenIntFloatHashMap> exposureMap,
                                 AccidentType type, int time) {
        if (exposureMap == null) return 0f;
        OpenIntFloatHashMap timeMap = exposureMap.get(type);
        if (timeMap == null) return 0f;
        return timeMap.get(time);
    }

    private double[] getLinkSevereFatalInjuryRisk(Mode mode, int hour, LinkInfo linkInfo) {
        // Munich
        double FATAL_CAR_DRIVER = 0.077;
        double FATAL_BIKECAR_BIKE = 0.024;
        double FATAL_BIKEBIKE_BIKE = 0.051;
        double FATAL_PED_PED = 0.073;

        double severeInjuryRisk;
        double fatalityRisk;
        Map<AccidentType, OpenIntFloatHashMap> exposure = linkInfo.getSevereFatalCasualtyExposureByAccidentTypeByTime();

        switch (mode){
            case autoPassenger:
            case autoDriver:
                fatalityRisk = exposure.get(AccidentType.CAR).get(hour) * FATAL_CAR_DRIVER;
                severeInjuryRisk = exposure.get(AccidentType.CAR).get(hour) * (1-FATAL_CAR_DRIVER);
                break;
            case bicycle:
                fatalityRisk = exposure.get(AccidentType.BIKECAR).get(hour) * FATAL_BIKECAR_BIKE +
                        exposure.get(AccidentType.BIKEBIKE).get(hour) * FATAL_BIKEBIKE_BIKE;
                severeInjuryRisk = exposure.get(AccidentType.BIKECAR).get(hour) * (1-FATAL_BIKECAR_BIKE) +
                        exposure.get(AccidentType.BIKEBIKE).get(hour) * (1-FATAL_BIKEBIKE_BIKE);
                break;
            case walk:
                fatalityRisk = exposure.get(AccidentType.PED).get(hour) * FATAL_PED_PED;
                severeInjuryRisk = exposure.get(AccidentType.PED).get(hour) * (1-FATAL_PED_PED);
                break;
            default:
                throw new RuntimeException("Undefined mode " + mode);
        }

        return new double[]{severeInjuryRisk,fatalityRisk};
    }

    private void fillConfigWithWalkStandardValue(WalkConfigGroup walkConfigGroup) {
        // WALK ATTRIBUTES
        List<ToDoubleFunction<Link>> walkAttributes = new ArrayList<>();
        walkAttributes.add(l -> Math.max(Math.min(Gradient.getGradient(l),0.5),0.));
        walkAttributes.add(l -> JctStress.getStressProp(l,TransportMode.walk));
        walkAttributes.add(l -> Math.max(0.,0.81 - LinkAmbience.getVgviFactor(l)));
        walkAttributes.add(l -> Math.min(1.,((double) l.getAttributes().getAttribute("speedLimitMPH")) / 50.));

        // Walk config group
        walkConfigGroup.setAttributes(walkAttributes);
        walkConfigGroup.setWeights(HealthExposureModelMEL::calculateWalkWeights);

    }

    private void fillConfigWithBikeStandardValue(BicycleConfigGroup bicycleConfigGroup) {
        // BIKE ATTRIBUTES
        List<ToDoubleFunction<Link>> bikeAttributes = new ArrayList<>();
        bikeAttributes.add(l -> Math.max(Math.min(Gradient.getGradient(l),0.5),0.));
        bikeAttributes.add(l -> LinkStress.getStress(l, TransportMode.bike));
        bikeAttributes.add(l -> Math.max(0.,0.81 - LinkAmbience.getVgviFactor(l)));
        bikeAttributes.add(l -> Math.min(1.,((double) l.getAttributes().getAttribute("speedLimitMPH")) / 50.));

        // Bicycle config group
        bicycleConfigGroup.setAttributes(bikeAttributes);
        bicycleConfigGroup.setWeights(HealthExposureModelMEL::calculateBikeWeights);

    }


    public static double[] calculateActiveModeWeights(String mode, org.matsim.api.core.v01.population.Person person) {
        double grad = 0.0;
        double stressLink = 0.0;
        double vgvi = 0.0;
        double speed = 0.0;

        MitoGender gender = MitoGender.valueOf((String) person.getAttributes().getAttribute("sex"));
        int age = (int) person.getAttributes().getAttribute("age");
        Purpose purpose = (Purpose) person.getAttributes().getAttribute("purpose");
        CoefficientSet coeffs = CoefficientLookup.getCoefficients(purpose, mode);

        // Base coefficients
        grad += coeffs.grad;
        stressLink += coeffs.stressLink;
        vgvi += coeffs.vgvi;
        speed += coeffs.speed;

        if (age >= 16 && gender.equals(MitoGender.FEMALE)) {
            grad += coeffs.grad_f;
            stressLink += coeffs.stressLink_f;
            vgvi += coeffs.vgvi_f;
            speed += coeffs.speed_f;
        }

        if (age < 16) {
            grad += coeffs.grad_c;
            stressLink += coeffs.stressLink_c;
            vgvi += coeffs.vgvi_c;
            speed += coeffs.speed_c;
        }

        // Return aggregated coefficients
        return new double[] {grad, stressLink, vgvi, speed};
    }

    public static double[] calculateBikeWeights(org.matsim.api.core.v01.population.Person person) {
        return calculateActiveModeWeights("bicycle", person);
    }

    public static double[] calculateWalkWeights(org.matsim.api.core.v01.population.Person person) {
        return calculateActiveModeWeights("walk", person);
    }


    private Network extractModeSpecificNetwork(Network fullNetwork, Set<String> transportModes) {

        Network modeSpecificNetwork = NetworkUtils.createNetwork();

        new TransportModeNetworkFilter(fullNetwork).filter(modeSpecificNetwork, transportModes);
        NetworkUtils.runNetworkCleaner(modeSpecificNetwork);
        return modeSpecificNetwork;
    }

    public void processNdviData(Network network) {

        for(Link link : network.getLinks().values()){
            double ndvi = 0.;
            if(link.getAttributes().getAttribute("ndvi")!=null){
                ndvi = (double) link.getAttributes().getAttribute("ndvi");
            }
            ((DataContainerHealth)dataContainer).getLinkInfo().get(link.getId()).setNdvi(ndvi);
        }


        for (ActivityLocation locationInfo :  ((DataContainerHealth)dataContainer).getActivityLocations().values()){
            Link link = NetworkUtils.getNearestLink(network, CoordUtils.createCoord(locationInfo.getCoordinate()));

            if (link!=null){
                locationInfo.setNdvi((Double) link.getAttributes().getAttribute("ndvi"));
            }
        }
    }


}

