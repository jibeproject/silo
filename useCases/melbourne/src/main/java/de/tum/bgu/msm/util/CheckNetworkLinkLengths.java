package de.tum.bgu.msm.util;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public class CheckNetworkLinkLengths {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java CheckNetworkLinkLengthsToCsv ");
            System.exit(1);
        }

        String networkFile = args[0];
        String csvFile = networkFile + ".lengthCheck.csv";

        // Load network using MATSim
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkFile);
        Network network = scenario.getNetwork();

        System.out.println("Loaded network with "
                + network.getNodes().size() + " nodes and "
                + network.getLinks().size() + " links.");

        int countProblematic = 0;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile))) {
            // CSV header
            writer.write("linkId,fromNodeId,toNodeId,length,euclidean,diff,fromX,fromY,toX,toY");
            writer.newLine();

            for (Link link : network.getLinks().values()) {
                Node fromNode = link.getFromNode();
                Node toNode = link.getToNode();

                Coord fromCoord = fromNode.getCoord();
                Coord toCoord = toNode.getCoord();

                double euclidean = CoordUtils.calcEuclideanDistance(
                        fromCoord,
                        toCoord
                );
                double length = link.getLength();

                if (length < euclidean) {
                    countProblematic++;

                    double diff = euclidean - length;

                    // Simple CSV line; if you have IDs with commas, you’d need proper escaping
                    String line = String.format("%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                            link.getId().toString(),
                            fromNode.getId().toString(),
                            toNode.getId().toString(),
                            length,
                            euclidean,
                            diff,
                            fromCoord.getX(),
                            fromCoord.getY(),
                            toCoord.getX(),
                            toCoord.getY()
                    );
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            System.err.println("Error writing CSV: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }

        System.out.println("Total links with length < Euclidean distance: " + countProblematic);
        System.out.println("Details written to: " + csvFile);
    }
}