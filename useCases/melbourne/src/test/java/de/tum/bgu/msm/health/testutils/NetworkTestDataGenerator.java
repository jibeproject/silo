package de.tum.bgu.msm.health.testutils;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.core.network.NetworkUtils;

import java.util.Random;

/**
 * Utility class for generating MATSim networks for testing purposes.
 * Provides reusable methods to create various types of test networks with different characteristics.
 */
public class NetworkTestDataGenerator {

    /**
     * Creates a simple test network with basic link attributes for accident model testing.
     */
    public static Network createSimpleTestNetwork(int linkCount) {
        Network network = NetworkUtils.createNetwork();
        NetworkFactory factory = network.getFactory();

        // First, create and add all nodes to the network
        for (int i = 1; i <= linkCount * 2; i++) {
            org.matsim.api.core.v01.Coord coord = new org.matsim.api.core.v01.Coord(i * 100.0, (i % 2) * 100.0);
            network.addNode(factory.createNode(Id.createNodeId("node_" + i), coord));
        }

        // Then create links using the existing nodes
        for (int i = 1; i <= linkCount; i++) {
            Link link = factory.createLink(Id.createLinkId("link_" + i),
                network.getNodes().get(Id.createNodeId("node_" + i)),
                network.getNodes().get(Id.createNodeId("node_" + (i + linkCount))));

            link.setLength(100.0 + i * 50);
            link.getAttributes().putAttribute("bike", (double) (i * 10));
            link.getAttributes().putAttribute("car", (double) (i * 100));
            link.getAttributes().putAttribute("speedLimitMPH", 30.0);

            network.addLink(link);
        }

        return network;
    }

    /**
     * Creates a large benchmark network for performance testing.
     */
    public static Network createBenchmarkNetwork(int linkCount) {
        Network network = NetworkUtils.createNetwork();
        NetworkFactory factory = network.getFactory();
        Random random = new Random(42);

        // First, create and add all nodes to the network
        for (int i = 1; i <= linkCount * 2; i++) {
            org.matsim.api.core.v01.Coord coord = new org.matsim.api.core.v01.Coord(
                random.nextDouble() * 1000.0, random.nextDouble() * 1000.0);
            network.addNode(factory.createNode(Id.createNodeId("node_" + i), coord));
        }

        // Then create links using the existing nodes
        for (int i = 1; i <= linkCount; i++) {
            Link link = factory.createLink(
                Id.createLinkId("benchmark_link_" + i),
                network.getNodes().get(Id.createNodeId("node_" + i)),
                network.getNodes().get(Id.createNodeId("node_" + (i + linkCount)))
            );

            link.setLength(50.0 + random.nextDouble() * 500.0);

            if (random.nextDouble() < 0.6) {
                link.getAttributes().putAttribute("bike", random.nextDouble() * 100.0);
            }
            if (random.nextDouble() < 0.8) {
                link.getAttributes().putAttribute("car", random.nextDouble() * 5000.0);
            }

            link.getAttributes().putAttribute("speedLimitMPH", 20.0 + random.nextInt(60));
            link.getAttributes().putAttribute("bike_stress", random.nextDouble() * 5.0);

            network.addLink(link);
        }

        return network;
    }

    /**
     * Creates a network with mixed demand patterns for testing early exit conditions.
     */
    public static Network createNetworkWithMixedDemand(int linkCount) {
        Network network = NetworkUtils.createNetwork();
        NetworkFactory factory = network.getFactory();

        // First, create and add all nodes to the network
        for (int i = 1; i <= linkCount * 2; i++) {
            org.matsim.api.core.v01.Coord coord = new org.matsim.api.core.v01.Coord(i * 50.0, (i % 2) * 100.0);
            network.addNode(factory.createNode(Id.createNodeId("mixed_node_" + i), coord));
        }

        // Then create links using the existing nodes
        for (int i = 1; i <= linkCount; i++) {
            Link link = factory.createLink(
                Id.createLinkId("mixed_link_" + i),
                network.getNodes().get(Id.createNodeId("mixed_node_" + i)),
                network.getNodes().get(Id.createNodeId("mixed_node_" + (i + linkCount)))
            );

            link.setLength(100.0);

            if (i % 4 == 0) {
                link.getAttributes().putAttribute("bike", 50.0);
            } else if (i % 4 == 1) {
                link.getAttributes().putAttribute("car", 1000.0);
            } else if (i % 4 == 2) {
                link.getAttributes().putAttribute("bike", 25.0);
                link.getAttributes().putAttribute("car", 500.0);
            }

            network.addLink(link);
        }

        return network;
    }

    /**
     * Creates a link with all possible demand types for comprehensive testing.
     */
    public static Link createLinkWithAllDemandTypes() {
        NetworkFactory factory = NetworkUtils.createNetwork().getFactory();
        Link link = factory.createLink(Id.createLinkId("complete_link"),
            factory.createNode(Id.createNodeId("node_start"), null),
            factory.createNode(Id.createNodeId("node_end"), null));

        link.setLength(200.0);
        link.getAttributes().putAttribute("bike", 75.0);
        link.getAttributes().putAttribute("car", 1500.0);
        link.getAttributes().putAttribute("speedLimitMPH", 40.0);
        link.getAttributes().putAttribute("bike_stress", 2.5);

        return link;
    }

    /**
     * Creates a link with specific demand type for targeted testing.
     */
    public static Link createLinkWithDemand(String demandType, double value) {
        NetworkFactory factory = NetworkUtils.createNetwork().getFactory();
        Link link = factory.createLink(Id.createLinkId("demand_link"),
            factory.createNode(Id.createNodeId("demand_start"), null),
            factory.createNode(Id.createNodeId("demand_end"), null));

        link.setLength(150.0);
        link.getAttributes().putAttribute(demandType, value);

        return link;
    }

    /**
     * Creates a pedestrian-only link for walk demand testing.
     */
    public static Link createWalkOnlyLink(double length) {
        NetworkFactory factory = NetworkUtils.createNetwork().getFactory();
        Link link = factory.createLink(Id.createLinkId("walk_link"),
            factory.createNode(Id.createNodeId("walk_start"), null),
            factory.createNode(Id.createNodeId("walk_end"), null));

        link.setLength(length);

        return link;
    }

    /**
     * Creates an empty link with no demand attributes for edge case testing.
     */
    public static Link createEmptyLink() {
        NetworkFactory factory = NetworkUtils.createNetwork().getFactory();
        Link link = factory.createLink(Id.createLinkId("empty_link"),
            factory.createNode(Id.createNodeId("empty_start"), null),
            factory.createNode(Id.createNodeId("empty_end"), null));

        link.setLength(100.0);

        return link;
    }

    /**
     * Creates a link with extreme demand values for stress testing.
     */
    public static Link createLinkWithExtremeDemand() {
        NetworkFactory factory = NetworkUtils.createNetwork().getFactory();
        Link link = factory.createLink(Id.createLinkId("extreme_link"),
            factory.createNode(Id.createNodeId("extreme_start"), null),
            factory.createNode(Id.createNodeId("extreme_end"), null));

        link.setLength(1000.0);
        link.getAttributes().putAttribute("bike", 10000.0);
        link.getAttributes().putAttribute("car", 100000.0);
        link.getAttributes().putAttribute("speedLimitMPH", 100.0);

        return link;
    }

    /**
     * Creates a realistic urban network segment for integration testing.
     */
    public static Network createRealisticUrbanNetwork() {
        Network network = NetworkUtils.createNetwork();
        NetworkFactory factory = network.getFactory();

        // Main arterial roads
        for (int i = 1; i <= 5; i++) {
            Link link = factory.createLink(Id.createLinkId("arterial_" + i),
                factory.createNode(Id.createNodeId("arterial_node_" + i), null),
                factory.createNode(Id.createNodeId("arterial_node_" + (i + 1)), null));

            link.setLength(500.0);
            link.getAttributes().putAttribute("car", 3000.0);
            link.getAttributes().putAttribute("bike", 50.0);
            link.getAttributes().putAttribute("speedLimitMPH", 50.0);
            link.getAttributes().putAttribute("bike_stress", 4.0);

            network.addLink(link);
        }

        // Residential streets
        for (int i = 1; i <= 10; i++) {
            Link link = factory.createLink(Id.createLinkId("residential_" + i),
                factory.createNode(Id.createNodeId("res_node_" + i), null),
                factory.createNode(Id.createNodeId("res_node_" + (i + 10)), null));

            link.setLength(200.0);
            link.getAttributes().putAttribute("car", 500.0);
            link.getAttributes().putAttribute("bike", 20.0);
            link.getAttributes().putAttribute("speedLimitMPH", 25.0);
            link.getAttributes().putAttribute("bike_stress", 2.0);

            network.addLink(link);
        }

        return network;
    }
}
