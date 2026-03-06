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
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.utils.collections.QuadTree;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class brunswickNetworkCleaner {
    private static final Logger logger = LogManager.getLogger(brunswickNetworkCleaner.class);
    private static final double DEFAULT_FREESPEED = 50.0 / 3.6; // 50 km/h in m/s
    private static final double DEFAULT_CAPACITY = 1000.0; // veh/h
    private static final double DEFAULT_LANES = 1.0;

    // Bounding box coordinates
    private static final double MIN_X = 318323.4494275984;
    private static final double MIN_Y = 5815727.9856401;
    private static final double MAX_X = 322784.1825332494;
    private static final double MAX_Y = 5820474.0276485;

    // Common transport modes to check connectivity
    private static final List<String> TRANSPORT_MODES = Arrays.asList(
            TransportMode.car, TransportMode.walk, TransportMode.bike);

    public static void main(String[] args) {
        String inputPath = "D:/projects/jibe/melbourne/input/mito/trafficAssignment/network.xml";
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

            // Filter network to bounding box
            logger.info("Filtering network to bounding box: ({}, {}) - ({}, {})",
                    MIN_X, MIN_Y, MAX_X, MAX_Y);
            Network filteredNetwork = filterNetworkByBoundingBox(network);
            logger.info("Network filtered: {} nodes, {} links remaining",
                    filteredNetwork.getNodes().size(), filteredNetwork.getLinks().size());

            // Clean network
            logger.info("Running network cleaner...");
            int nodesBefore = filteredNetwork.getNodes().size();
            int linksBefore = filteredNetwork.getLinks().size();

            NetworkCleaner cleaner = new NetworkCleaner();
            cleaner.run(filteredNetwork);

            int nodesAfter = filteredNetwork.getNodes().size();
            int linksAfter = filteredNetwork.getLinks().size();
            logger.info("Network cleaned: removed {} nodes and {} links",
                    nodesBefore - nodesAfter, linksBefore - linksAfter);

            // Find and repair connectivity issues
            logger.info("Identifying and repairing connectivity issues...");
            repairNetworkConnectivity(filteredNetwork);

            // Write fixed network
            logger.info("Writing fixed network to: {}", outputPath);
            NetworkUtils.writeNetwork(filteredNetwork, outputPath);

            logger.info("Network fixing complete.");
        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Creates a new filtered network containing only nodes within the bounding box and their connecting links
     */
    private static Network filterNetworkByBoundingBox(Network fullNetwork) {
        // Create new empty network with the same attributes
        Network filteredNetwork = NetworkUtils.createNetwork();
        filteredNetwork.setName(fullNetwork.getName() + "_filtered");

        // First pass: identify and copy nodes within the bounding box
        for (Node node : fullNetwork.getNodes().values()) {
            Coord coord = node.getCoord();
            if (coord.getX() >= MIN_X && coord.getX() <= MAX_X &&
                coord.getY() >= MIN_Y && coord.getY() <= MAX_Y) {
                Node newNode = filteredNetwork.getFactory().createNode(node.getId(), node.getCoord());
                // Copy node attributes if any
                node.getAttributes().getAsMap().forEach((key, value) ->
                    newNode.getAttributes().putAttribute(key, value));
                filteredNetwork.addNode(newNode);
            }
        }

        logger.info("Added {} nodes within the bounding box", filteredNetwork.getNodes().size());

        // Second pass: copy links connecting nodes within the bounding box
        for (Link link : fullNetwork.getLinks().values()) {
            Node fromNode = filteredNetwork.getNodes().get(link.getFromNode().getId());
            Node toNode = filteredNetwork.getNodes().get(link.getToNode().getId());

            if (fromNode != null && toNode != null) {
                Link newLink = filteredNetwork.getFactory().createLink(link.getId(), fromNode, toNode);

                // Copy link attributes
                newLink.setLength(link.getLength());
                newLink.setFreespeed(link.getFreespeed());
                newLink.setCapacity(link.getCapacity());
                newLink.setNumberOfLanes(link.getNumberOfLanes());
                newLink.setAllowedModes(new HashSet<>(link.getAllowedModes()));

                // Copy additional attributes
                link.getAttributes().getAsMap().forEach((key, value) ->
                    newLink.getAttributes().putAttribute(key, value));

                filteredNetwork.addLink(newLink);
            }
        }

        logger.info("Added {} links connecting nodes within the bounding box",
                filteredNetwork.getLinks().size());

        return filteredNetwork;
    }

    /**
     * Identifies connectivity issues in the network and repairs them by adding links
     */
    private static void repairNetworkConnectivity(Network network) {
        // Build node quadtree for spatial queries
        QuadTree<Node> nodeQuadTree = buildNodeQuadTree(network);

        // Collect attribute template links for each transport mode
        Map<String, Link> templateLinks = findTemplateLinks(network);

        // For each mode, check connectivity and fix issues
        for (String mode : TRANSPORT_MODES) {
            logger.info("Checking connectivity for mode: {}", mode);

            // Create a mode-specific network view
            Network modeNetwork = NetworkUtils.createNetwork();
            for (Node node : network.getNodes().values()) {
                modeNetwork.addNode(node);
            }

            // Only add links that allow this mode
            for (Link link : network.getLinks().values()) {
                if (link.getAllowedModes().contains(mode)) {
                    Node fromNode = modeNetwork.getNodes().get(link.getFromNode().getId());
                    Node toNode = modeNetwork.getNodes().get(link.getToNode().getId());
                    Link newLink = modeNetwork.getFactory().createLink(
                            link.getId(), fromNode, toNode);
                    // Copy essential attributes
                    newLink.setLength(link.getLength());
                    newLink.setFreespeed(link.getFreespeed());
                    newLink.setCapacity(link.getCapacity());
                    newLink.setNumberOfLanes(link.getNumberOfLanes());
                    newLink.setAllowedModes(new HashSet<>(link.getAllowedModes()));
                    modeNetwork.addLink(newLink);
                }
            }

            // Find connected components in the mode network
            List<Set<Id<Node>>> components = findConnectedComponents(modeNetwork);
            logger.info("Found {} connected components for mode {}", components.size(), mode);

            // If more than one component, we need to connect them
            if (components.size() > 1) {
                connectComponents(network, components, nodeQuadTree, mode, templateLinks.get(mode));
            }
        }

        // Final pass to ensure all isolated nodes have at least one connection
        connectIsolatedNodes(network, nodeQuadTree, templateLinks.get(TransportMode.car));
    }

    /**
     * Builds a QuadTree containing all nodes for spatial queries
     */
    private static QuadTree<Node> buildNodeQuadTree(Network network) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        // Find the bounds
        for (Node node : network.getNodes().values()) {
            Coord coord = node.getCoord();
            minX = Math.min(minX, coord.getX());
            minY = Math.min(minY, coord.getY());
            maxX = Math.max(maxX, coord.getX());
            maxY = Math.max(maxY, coord.getY());
        }

        // Add small buffer to avoid precision issues
        minX -= 1.0;
        minY -= 1.0;
        maxX += 1.0;
        maxY += 1.0;

        // Create and populate quadtree
        QuadTree<Node> quadTree = new QuadTree<>(minX, minY, maxX, maxY);
        for (Node node : network.getNodes().values()) {
            quadTree.put(node.getCoord().getX(), node.getCoord().getY(), node);
        }

        return quadTree;
    }

    /**
     * Find template links for each mode to use as attribute sources
     */
    private static Map<String, Link> findTemplateLinks(Network network) {
        Map<String, Link> templates = new HashMap<>();

        // For each mode, find a link that allows that mode and has all attributes
        for (String mode : TRANSPORT_MODES) {
            // First try: find a link with ndvi and mode
            for (Link link : network.getLinks().values()) {
                if (link.getAllowedModes().contains(mode) &&
                    link.getAttributes().getAttribute("ndvi") != null) {
                    templates.put(mode, link);
                    break;
                }
            }

            // Second try: find any link with the mode
            if (!templates.containsKey(mode)) {
                for (Link link : network.getLinks().values()) {
                    if (link.getAllowedModes().contains(mode)) {
                        templates.put(mode, link);
                        break;
                    }
                }
            }

            // Last resort: use any link
            if (!templates.containsKey(mode) && !network.getLinks().isEmpty()) {
                Link anyLink = network.getLinks().values().iterator().next();
                templates.put(mode, anyLink);
            }
        }

        return templates;
    }

    /**
     * Finds connected components in the network using BFS
     */
    private static List<Set<Id<Node>>> findConnectedComponents(Network network) {
        Set<Id<Node>> unvisited = new HashSet<>(network.getNodes().keySet());
        List<Set<Id<Node>>> components = new ArrayList<>();

        while (!unvisited.isEmpty()) {
            Id<Node> startNodeId = unvisited.iterator().next();
            Set<Id<Node>> component = new HashSet<>();
            Queue<Id<Node>> queue = new LinkedList<>();

            queue.add(startNodeId);
            component.add(startNodeId);
            unvisited.remove(startNodeId);

            while (!queue.isEmpty()) {
                Id<Node> nodeId = queue.poll();
                Node node = network.getNodes().get(nodeId);

                // Find all outgoing and incoming links for this node
                network.getLinks().values().stream()
                    .filter(link -> link.getFromNode().getId().equals(nodeId) ||
                                   link.getToNode().getId().equals(nodeId))
                    .forEach(link -> {
                        Id<Node> neighborId = link.getFromNode().getId().equals(nodeId) ?
                                             link.getToNode().getId() : link.getFromNode().getId();

                        if (unvisited.contains(neighborId)) {
                            queue.add(neighborId);
                            component.add(neighborId);
                            unvisited.remove(neighborId);
                        }
                    });
            }

            components.add(component);
        }

        return components;
    }

    /**
     * Connect separate components by adding links between their closest nodes
     */
    private static void connectComponents(Network network, List<Set<Id<Node>>> components,
                                         QuadTree<Node> nodeQuadTree, String mode, Link templateLink) {
        int linksAdded = 0;
        // Sort components by size (largest first) to connect smaller components to larger ones
        components.sort(Comparator.comparing(Set::size, Comparator.reverseOrder()));

        // The first component is the largest
        Set<Id<Node>> mainComponent = components.get(0);
        Set<Id<Node>> mainComponentNodes = mainComponent.stream()
            .map(network.getNodes()::get)
            .map(Node::getId)
            .collect(Collectors.toSet());

        // Connect all other components to the main component
        for (int i = 1; i < components.size(); i++) {
            Set<Id<Node>> component = components.get(i);

            // Find the closest pair of nodes between this component and the main component
            double minDistance = Double.MAX_VALUE;
            Node closestMainNode = null;
            Node closestComponentNode = null;

            for (Id<Node> nodeId : component) {
                Node node = network.getNodes().get(nodeId);
                // Find closest node in main component
                Node closestNode = findClosestNode(nodeQuadTree, node.getCoord(),
                                                 mainComponentNodes, 5000.0);

                if (closestNode != null) {
                    double distance = NetworkUtils.getEuclideanDistance(node.getCoord(), closestNode.getCoord());
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestMainNode = closestNode;
                        closestComponentNode = node;
                    }
                }
            }

            // Connect these nodes with bidirectional links
            if (closestMainNode != null && closestComponentNode != null) {
                // Add a bidirectional connection
                String connectorId = closestComponentNode.getId() + "-" + closestMainNode.getId();
                createLink(network, closestComponentNode, closestMainNode, connectorId, mode, templateLink);
                createLink(network, closestMainNode, closestComponentNode, closestMainNode.getId() + "-" +
                          closestComponentNode.getId(), mode, templateLink);

                linksAdded += 2;
                logger.info("Connected component {} to main component via nodes {} and {} (distance: {:.1f}m)",
                           i, closestComponentNode.getId(), closestMainNode.getId(), minDistance);

                // Add this component to the main component for future connections
                mainComponentNodes.addAll(component.stream()
                    .map(network.getNodes()::get)
                    .map(Node::getId)
                    .collect(Collectors.toSet()));
            }
        }

        logger.info("Added {} links to connect {} components for mode {}",
                   linksAdded, components.size(), mode);
    }

    /**
     * Find the closest node from a quadtree within a maximum distance
     */
    private static Node findClosestNode(QuadTree<Node> nodeQuadTree, Coord coord,
                                       Set<Id<Node>> validNodeIds, double maxDistance) {
        Collection<Node> nearbyNodes = nodeQuadTree.getDisk(coord.getX(), coord.getY(), maxDistance);
        return nearbyNodes.stream()
            .filter(node -> validNodeIds.contains(node.getId()))
            .min(Comparator.comparingDouble(node ->
                NetworkUtils.getEuclideanDistance(coord, node.getCoord())))
            .orElse(null);
    }

    /**
     * Connect any remaining isolated nodes to their nearest neighbors
     */
    private static void connectIsolatedNodes(Network network, QuadTree<Node> nodeQuadTree, Link templateLink) {
        int linksAdded = 0;

        // Find nodes with no connections
        List<Node> isolatedNodes = network.getNodes().values().stream()
            .filter(node -> getNodeDegree(network, node) == 0)
            .collect(Collectors.toList());

        logger.info("Found {} isolated nodes to connect", isolatedNodes.size());

        // Find non-isolated nodes for potential connections
        List<Node> connectedNodes = network.getNodes().values().stream()
            .filter(node -> getNodeDegree(network, node) > 0)
            .collect(Collectors.toList());

        if (connectedNodes.isEmpty()) {
            logger.warn("No connected nodes found to link isolated nodes to");
            return;
        }

        for (Node isolatedNode : isolatedNodes) {
            // Find closest connected node (with at least one link)
            Node closestNode = null;
            double minDistance = Double.MAX_VALUE;

            // Search for the closest connected node
            for (Node connectedNode : connectedNodes) {
                double distance = NetworkUtils.getEuclideanDistance(
                    isolatedNode.getCoord(), connectedNode.getCoord());
                if (distance < minDistance) {
                    minDistance = distance;
                    closestNode = connectedNode;
                }
            }

            if (closestNode != null) {
                // Create bidirectional connection
                String connectorId = isolatedNode.getId() + "-" + closestNode.getId();
                createLink(network, isolatedNode, closestNode, connectorId,
                          TransportMode.car, templateLink);
                createLink(network, closestNode, isolatedNode,
                          closestNode.getId() + "-" + isolatedNode.getId(),
                          TransportMode.car, templateLink);

                linksAdded += 2;

                if (linksAdded % 10 == 0) {
                    logger.info("Connected {} of {} isolated nodes so far", linksAdded/2, isolatedNodes.size());
                }
            }
        }

        logger.info("Added {} links to connect {} isolated nodes", linksAdded, isolatedNodes.size());
    }

    /**
     * Calculate node degree (number of incoming + outgoing links)
     */
    private static int getNodeDegree(Network network, Node node) {
        int degree = 0;
        for (Link link : network.getLinks().values()) {
            if (link.getFromNode().equals(node) || link.getToNode().equals(node)) {
                degree++;
            }
        }
        return degree;
    }

    /**
     * Create a new link with attributes copied from template link
     */
    private static void createLink(Network network, Node fromNode, Node toNode, String id,
                                  String mode, Link templateLink) {
        // Calculate link length based on node coordinates
        double length = NetworkUtils.getEuclideanDistance(fromNode.getCoord(), toNode.getCoord());

        // Create a unique link ID that includes the mode to avoid collisions between modes
        String uniqueId = "connector_" + mode + "_" + id;

        // Check if link already exists to avoid conflicts
        Id<Link> linkId = Id.createLinkId(uniqueId);
        if (network.getLinks().containsKey(linkId)) {
            logger.warn("Link with ID {} already exists, skipping creation", uniqueId);
            return;
        }

        // Create the link with default parameters
        Link link = network.getFactory().createLink(linkId, fromNode, toNode);
        link.setLength(length);
        link.setFreespeed(DEFAULT_FREESPEED);
        link.setCapacity(DEFAULT_CAPACITY);
        link.setNumberOfLanes(DEFAULT_LANES);

        // Set allowed mode
        Set<String> modes = new HashSet<>();
        modes.add(mode);
        link.setAllowedModes(modes);

        // Copy attributes from template link
        if (templateLink != null) {
            templateLink.getAttributes().getAsMap().forEach((key, value) -> {
                link.getAttributes().putAttribute(key, value);
            });
        } else {
            // If no template link, set default values for critical attributes
            link.getAttributes().putAttribute("ndvi", 0.2); // Default NDVI value
            link.getAttributes().putAttribute("gradient", 0.0);
            link.getAttributes().putAttribute("stressLink", 0.5);
        }

        // Add to network
        network.addLink(link);
    }
}
