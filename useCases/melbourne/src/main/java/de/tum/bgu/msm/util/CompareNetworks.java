package de.tum.bgu.msm.util;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class CompareNetworks {

    public static void main(String[] args) {
        File original = new File("input/mito/trafficAssignment/network_pre_2026-02-27.xml");
        File updated  = new File("input/mito/trafficAssignment/network.xml");

        Scenario sOrig = loadScenario(original);
        Scenario sNew  = loadScenario(updated);

        System.out.println("Original links: " + sOrig.getNetwork().getLinks().size());
        System.out.println("Updated  links: " + sNew.getNetwork().getLinks().size());
        System.out.println("Original nodes: " + sOrig.getNetwork().getNodes().size());
        System.out.println("Updated  nodes: " + sNew.getNetwork().getNodes().size());

        // Check IDs
        Set<String> origIds = new HashSet<>();
        for (Link l : sOrig.getNetwork().getLinks().values()) {
            origIds.add(l.getId().toString());
        }

        Set<String> newIds = new HashSet<>();
        for (Link l : sNew.getNetwork().getLinks().values()) {
            newIds.add(l.getId().toString());
        }

        Set<String> onlyInOrig = new HashSet<>(origIds);
        onlyInOrig.removeAll(newIds);

        Set<String> onlyInNew = new HashSet<>(newIds);
        onlyInNew.removeAll(origIds);

        System.out.println("Links only in original: " + onlyInOrig.size());
        System.out.println("Links only in updated : " + onlyInNew.size());
    }

    private static Scenario loadScenario(File networkFile) {
        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(networkFile.getAbsolutePath());
        return ScenarioUtils.loadScenario(config);
    }
}