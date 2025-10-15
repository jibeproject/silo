package de.tum.bgu.msm.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.router.DijkstraFactory;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class brunswickNetworkCleaner {
    private static final Logger logger = LogManager.getLogger(brunswickNetworkCleaner.class);
    private static final double DEFAULT_FREESPEED = 50.0 / 3.6; // 50 km/h in m/s
    private static final double DEFAULT_CAPACITY = 1000.0; // veh/h
    private static final double DEFAULT_LANES = 1.0;

    public static void main(String[] args) {
        String inputPath = "D:/projects/jibe/Melbourne/input/mito/trafficAssignment/archive/network.xml";
        String outputPath = "D:/projects/jibe/Brunswick/input/mito/trafficAssignment/network.xml";

        // Allow command line arguments to override default paths
        if (args.length >= 1) inputPath = args[0];
        if (args.length >= 2) outputPath = args[1];

        try {
            logger.info("Loading network from: {}", inputPath);
            if (!Files.exists(Paths.get(inputPath))) {
                logger.error("Input file does not exist: {}", inputPath);
                System.exit(1);
            }

            // Load network
            Network network = NetworkUtils.readNetwork(inputPath);
            logger.info("Network loaded: {} nodes, {} links",
                    network.getNodes().size(), network.getLinks().size());

            // Clean network
            logger.info("Running network cleaner...");
            int nodesBefore = network.getNodes().size();
            int linksBefore = network.getLinks().size();

            NetworkCleaner cleaner = new NetworkCleaner();
            cleaner.run(network);

            int nodesAfter = network.getNodes().size();
            int linksAfter = network.getLinks().size();
            logger.info("Network cleaned: removed {} nodes and {} links",
                    nodesBefore - nodesAfter, linksBefore - linksAfter);

            // Connect problematic node pairs
            connectSpecificNodePairs(network);

            // Write fixed network
            logger.info("Writing fixed network to: {}", outputPath);
            NetworkUtils.writeNetwork(network, outputPath);

            logger.info("Network fixing complete.");
        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void connectSpecificNodePairs(Network network) {
        // Define problematic node pairs
        List<String[]> nodePairsToConnect = Arrays.asList(
                new String[]{"300144", "291487"},
                new String[]{"300441", "296741"},
                new String[]{"300144", "302328"},
                new String[]{"299947", "294380"},
                new String[]{"300441", "295529"},
                new String[]{"299947", "297348"},
                new String[]{"300144", "303350"},
                new String[]{"300020", "301803"},
                new String[]{"300020", "297811"},
                new String[]{"298313", "302601"},
                new String[]{"299226", "302601"},
                new String[]{"300761", "302601"},
                new String[]{"299947", "301969"},
                new String[]{"303415", "302601"},
                new String[]{"292816", "302601"}
        );

        int linksAdded = 0;

        // Force-add direct links for all problematic pairs
        logger.info("Forcing direct connections for all problematic node pairs");
        for (String[] pair : nodePairsToConnect) {
            String fromNodeId = pair[0];
            String toNodeId = pair[1];

            Node fromNode = network.getNodes().get(Id.createNodeId(fromNodeId));
            Node toNode = network.getNodes().get(Id.createNodeId(toNodeId));

            if (fromNode == null || toNode == null) {
                logger.warn("Node pair {}-{} contains at least one node that doesn't exist in the network",
                        fromNodeId, toNodeId);
                continue;
            }

            // Always add bidirectional links to ensure connectivity
            createLink(network, fromNode, toNode, fromNodeId + "-" + toNodeId);
            createLink(network, toNode, fromNode, toNodeId + "-" + fromNodeId);
            linksAdded += 2;
            logger.info("Added bidirectional link between nodes {} and {}", fromNodeId, toNodeId);
        }

        logger.info("Added {} links to connect specified node pairs", linksAdded);
    }


    private static void createLink(Network network, Node fromNode, Node toNode, String id) {
        // Calculate link length based on node coordinates
        double length = NetworkUtils.getEuclideanDistance(fromNode.getCoord(), toNode.getCoord());

        // Create the link with default parameters
        Link link = network.getFactory().createLink(Id.createLinkId("connector_" + id), fromNode, toNode);
        link.setLength(length);
        link.setFreespeed(DEFAULT_FREESPEED);
        link.setCapacity(DEFAULT_CAPACITY);
        link.setNumberOfLanes(DEFAULT_LANES);
        link.setAllowedModes(new HashSet<>(Arrays.asList(TransportMode.car)));

        // Find a source link to copy attributes from
        Link sourceLink = findSourceLinkForAttributes(network);

        // Copy attributes from source link
        if (sourceLink != null) {
            logger.info("Copying attributes from link {} to new link connector_{}", sourceLink.getId(), id);
            sourceLink.getAttributes().getAsMap().forEach((key, value) -> {
                link.getAttributes().putAttribute(key, value);
            });
        } else {
            // If no source link found, set default values for critical attributes
            logger.warn("No source link found for attribute copying. Setting default values for new link connector_{}", id);
            link.getAttributes().putAttribute("ndvi", 0.2); // Default NDVI value
            link.getAttributes().putAttribute("gradient", 0.0);
            link.getAttributes().putAttribute("stressLink", 0.5);
            // Add other critical attributes with default values
        }

        // Add to network
        network.addLink(link);
    }

    /**
     * Find a suitable source link that has all required attributes
     */
    private static Link findSourceLinkForAttributes(Network network) {
        // First try: find link with ndvi attribute
        for (Link link : network.getLinks().values()) {
            if (link.getAttributes().getAttribute("ndvi") != null) {
                return link;
            }
        }

        // Second try: take any link (better than nothing)
        if (!network.getLinks().isEmpty()) {
            return network.getLinks().values().iterator().next();
        }

        return null;
    }
}