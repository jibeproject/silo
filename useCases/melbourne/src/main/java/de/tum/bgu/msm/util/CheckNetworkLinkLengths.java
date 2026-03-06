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
        String yyyymmmddhhmm = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        String csvFile = networkFile + ".lengthCheck." + yyyymmmddhhmm + ".csv";

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

                double x1 = fromCoord.getX();
                double y1 = fromCoord.getY();
                double x2 = toCoord.getX();
                double y2 = toCoord.getY();

                double dx = x2 - x1;
                double dy = y2 - y1;
                double manual = Math.sqrt(dx * dx + dy * dy);
                double euclidean = CoordUtils.calcEuclideanDistance(fromCoord, toCoord);
                double length = link.getLength();

                // DEBUG ONLY for your suspicious link
                if (link.getId().toString().equals("646063out")) {
                    System.out.printf(
                            "DEBUG link %s:%n" +
                                    "  fromNode=%s toNode=%s%n" +
                                    "  x1=%.3f y1=%.3f x2=%.3f y2=%.3f%n" +
                                    "  dx=%.3f dy=%.3f%n" +
                                    "  manual=%.6f matsim=%.6f length=%.6f%n",
                            link.getId(),
                            fromNode.getId(), toNode.getId(),
                            x1, y1, x2, y2,
                            dx, dy,
                            manual, euclidean, length
                    );
                }
                System.out.printf("fromCoord.hasZ=%s, toCoord.hasZ=%s%n",
                        fromCoord.hasZ(), toCoord.hasZ());
                System.out.println("fromCoord = " + fromCoord);
                System.out.println("toCoord   = " + toCoord);
                if (length < euclidean) {
                    countProblematic++;

                    double diff = euclidean - length;

                    String line = String.format(
                            "%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                            link.getId().toString(),
                            fromNode.getId().toString(),
                            toNode.getId().toString(),
                            length,
                            euclidean,
                            diff,
                            x1, y1, x2, y2
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