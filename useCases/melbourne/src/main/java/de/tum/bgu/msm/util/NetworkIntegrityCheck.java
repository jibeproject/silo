package de.tum.bgu.msm.util;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;

public class NetworkIntegrityCheck {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java NetworkIntegrityCheck path/to/network.xml[.gz]");
            System.exit(1);
        }
        String networkFile = args[0];

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkFile);
        Network network = scenario.getNetwork();

        System.out.println("Nodes: " + network.getNodes().size() +
                ", Links: " + network.getLinks().size());

        int missingNodes = 0;
        for (Link link : network.getLinks().values()) {
            if (link.getFromNode() == null || link.getToNode() == null) {
                System.out.println("Link " + link.getId()
                        + " has missing from/to node: from=" + link.getFromNode()
                        + ", to=" + link.getToNode());
                missingNodes++;
            }
        }
        System.out.println("Links with missing endpoint nodes: " + missingNodes);
    }
}
