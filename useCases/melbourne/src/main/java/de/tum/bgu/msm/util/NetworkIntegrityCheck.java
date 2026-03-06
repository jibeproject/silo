package de.tum.bgu.msm.util;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Comprehensive network integrity checker for MATSim networks.
 *
 * Checks:
 *  - missing endpoint nodes (structural)
 *  - geometric plausibility of link length vs 2D Euclidean distance
 *  - undirected connected components (for connectivity)
 *
 * Outputs CSV files suitable for GIS:
 *  - <basename>_links_problematic.csv
 *  - <basename>_components.csv
 *  - <basename>_nodes_components.csv
 */
public class NetworkIntegrityCheck {

    // Tunable thresholds
    private static final double TOO_SHORT_EPSILON = 1e-6;  // how much shorter than Euclidean counts as "too short"
    private static final double TOO_LONG_FACTOR = 5.0;     // "too long" if length > FACTOR * Euclidean

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java NetworkIntegrityAndGeometryCheck " +
                    "path/to/network.xml[.gz]");
            System.exit(1);
        }

        String networkFile = args[0];
        String yyyymmdd_hhmm = new java.text.SimpleDateFormat("yyyyMMdd_HHmm").format(new Date());
        String outputPrefix = args[0] + "_integrity_check_"+yyyymmdd_hhmm;  // base name for output CSVs
        Path outputBase = Paths.get(outputPrefix);

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkFile);
        Network network = scenario.getNetwork();

        System.out.println("Loaded network: "
                + network.getNodes().size() + " nodes, "
                + network.getLinks().size() + " links.");

        // 1. Structural check: endpoints
        checkMissingEndpoints(network);

        // 2. Geometric checks: length vs 2D Euclidean
        String problematicLinksCsv = outputBase.toString() + "_links_problematic.csv";
        geometricChecks(network, problematicLinksCsv);

        // 3. Connectivity: undirected connected components
        String componentsCsv = outputBase.toString() + "_components.csv";
        String nodesComponentsCsv = outputBase.toString() + "_nodes_components.csv";
        connectivityChecks(network, componentsCsv, nodesComponentsCsv);

        System.out.println("Done. Outputs:");
        System.out.println("  Problematic links CSV:   " + problematicLinksCsv);
        System.out.println("  Components summary CSV:  " + componentsCsv);
        System.out.println("  Nodes components CSV:    " + nodesComponentsCsv);
    }

    /**
     * Checks that each link has non-null from/to nodes.
     */
    private static void checkMissingEndpoints(Network network) {
        int missingEndpoints = 0;
        for (Link link : network.getLinks().values()) {
            if (link.getFromNode() == null || link.getToNode() == null) {
                System.out.println("Link " + link.getId()
                        + " has missing from/to node: from=" + link.getFromNode()
                        + ", to=" + link.getToNode());
                missingEndpoints++;
            }
        }
        System.out.println("Structural check: links with missing endpoint nodes: " + missingEndpoints);
    }

    /**
     * Geometric plausibility checks.
     *
     * Writes CSV of links that are suspiciously short or long relative to
     * the 2D Euclidean distance between their endpoints.
     */
    private static void geometricChecks(Network network, String outputCsv) {
        int countTooShort = 0;
        int countTooLong = 0;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputCsv))) {
            // Header
            writer.write("linkId,fromNodeId,toNodeId,length,euclidean2D,ratio,lengthMinusEuclidean,fromX,fromY,toX,toY");
            writer.newLine();

            for (Link link : network.getLinks().values()) {
                Node from = link.getFromNode();
                Node to = link.getToNode();
                Coord fromCoord = from.getCoord();
                Coord toCoord = to.getCoord();

                double x1 = fromCoord.getX();
                double y1 = fromCoord.getY();
                double x2 = toCoord.getX();
                double y2 = toCoord.getY();

                double dx = x2 - x1;
                double dy = y2 - y1;
                double euclidean2D = Math.sqrt(dx * dx + dy * dy);
                double length = link.getLength();

                if (euclidean2D == 0.0) {
                    // Degenerate case; skip for now
                    continue;
                }

                boolean tooShort = length + TOO_SHORT_EPSILON < euclidean2D;
                boolean tooLong = length > TOO_LONG_FACTOR * euclidean2D;

                if (tooShort || tooLong) {
                    if (tooShort) countTooShort++;
                    if (tooLong) countTooLong++;

                    double ratio = length / euclidean2D;
                    double diff = length - euclidean2D;

                    String line = String.format(Locale.US,
                            "%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                            link.getId().toString(),
                            from.getId().toString(),
                            to.getId().toString(),
                            length,
                            euclidean2D,
                            ratio,
                            diff,
                            x1, y1, x2, y2
                    );
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            System.err.println("Error writing problematic links CSV: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("Geometric check:");
        System.out.println("  Links with length < 2D Euclidean: " + countTooShort);
        System.out.println("  Links with length > " + TOO_LONG_FACTOR + " * 2D Euclidean: " + countTooLong);
    }

    /**
     * Connectivity checks: computes undirected connected components.
     *
     * Writes:
     *  - componentsCsv: summary of component sizes
     *  - nodesComponentsCsv: nodeId, componentId, x, y
     */
    private static void connectivityChecks(Network network,
                                           String componentsCsv,
                                           String nodesComponentsCsv) {
        Map<Node, List<Node>> adj = new HashMap<>();
        for (Node n : network.getNodes().values()) {
            adj.put(n, new ArrayList<>());
        }
        for (Link link : network.getLinks().values()) {
            Node from = link.getFromNode();
            Node to = link.getToNode();
            // undirected adjacency
            adj.get(from).add(to);
            adj.get(to).add(from);
        }

        // Compute connected components
        Map<Node, Integer> nodeToComponent = new HashMap<>();
        List<Integer> componentSizes = new ArrayList<>();

        int compId = 0;
        for (Node start : network.getNodes().values()) {
            if (!nodeToComponent.containsKey(start)) {
                int size = 0;
                Deque<Node> stack = new ArrayDeque<>();
                stack.push(start);
                nodeToComponent.put(start, compId);

                while (!stack.isEmpty()) {
                    Node current = stack.pop();
                    size++;
                    for (Node neigh : adj.get(current)) {
                        if (!nodeToComponent.containsKey(neigh)) {
                            nodeToComponent.put(neigh, compId);
                            stack.push(neigh);
                        }
                    }
                }
                componentSizes.add(size);
                compId++;
            }
        }

        System.out.println("Connectivity check:");
        System.out.println("  Connected components (undirected): " + componentSizes.size());

        // Sort component sizes descending for console summary
        List<Integer> sortedSizes = new ArrayList<>(componentSizes);
        sortedSizes.sort(Comparator.reverseOrder());
        for (int i = 0; i < Math.min(10, sortedSizes.size()); i++) {
            System.out.println("  Component " + i + " size: " + sortedSizes.get(i));
        }

        // Write components summary CSV
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(componentsCsv))) {
            writer.write("componentId,size");
            writer.newLine();
            for (int i = 0; i < componentSizes.size(); i++) {
                writer.write(i + "," + componentSizes.get(i));
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing components CSV: " + e.getMessage());
            e.printStackTrace();
        }

        // Write per-node component assignment CSV (for GIS join)
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(nodesComponentsCsv))) {
            writer.write("nodeId,componentId,x,y");
            writer.newLine();
            for (Node node : network.getNodes().values()) {
                int cid = nodeToComponent.get(node);
                Coord c = node.getCoord();
                String line = String.format(Locale.US,
                        "%s,%d,%.6f,%.6f",
                        node.getId().toString(),
                        cid,
                        c.getX(),
                        c.getY()
                );
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing nodes/components CSV: " + e.getMessage());
            e.printStackTrace();
        }
    }
}