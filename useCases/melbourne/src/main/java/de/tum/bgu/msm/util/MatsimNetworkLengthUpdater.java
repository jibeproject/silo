package de.tum.bgu.msm.util;

import org.geotools.api.data.DataStore;
import org.geotools.api.data.DataStoreFinder;
import org.geotools.api.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class MatsimNetworkLengthUpdater {

    public static void main(String[] args) throws Exception {
        // Use absolute paths for now (you can change to relative later if you like)
        File matsimNetworkXml = new File("input/mito/trafficAssignment/network.xml");
        File gpkgFile         = new File("input/mito/trafficAssignment/network2way.gpkg");
        File outputNetworkXml = new File("input/mito/trafficAssignment/network_updated.xml");

        String layerName = "links";   // table/layer name in the GeoPackage
        String idAttr    = "linkID";  // attribute that matches MATSim link id

        Map<String, Double> lengthById =
                readLengthsFromGeometry(gpkgFile, layerName, idAttr);

        System.out.println("Loaded " + lengthById.size() + " geometries from GeoPackage.");

        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(matsimNetworkXml.getAbsolutePath());
        Scenario scenario = ScenarioUtils.loadScenario(config);

        int updated = 0;
        int missing = 0;

        for (Link link : scenario.getNetwork().getLinks().values()) {
            String id = link.getId().toString();
            Double newLength = lengthById.get(id);
            if (newLength != null) {
                link.setLength(newLength);
                updated++;
            } else {
                missing++;
            }
        }

        System.out.println("Updated " + updated + " links; "
                + missing + " links had no matching geometry length.");

        new NetworkWriter(scenario.getNetwork()).write(outputNetworkXml.getAbsolutePath());
        System.out.println("Updated network written to: " + outputNetworkXml.getAbsolutePath());
    }

    private static Map<String, Double> readLengthsFromGeometry(
            File gpkgFile,
            String layerName,
            String idAttribute) throws Exception {

        Map<String, Object> params = new HashMap<>();
        params.put("dbtype", "geopkg");
        params.put("database", gpkgFile.getAbsolutePath());

        DataStore dataStore = DataStoreFinder.getDataStore(params);
        if (dataStore == null) {
            throw new IllegalStateException("Could not open GeoPackage: " + gpkgFile.getAbsolutePath());
        }

        try {
            // Determine layer name if not provided
            if (layerName == null || layerName.isEmpty()) {
                String[] typeNames = dataStore.getTypeNames();
                if (typeNames == null || typeNames.length == 0) {
                    throw new IllegalStateException("No feature types found in GeoPackage: "
                            + gpkgFile.getAbsolutePath());
                }
                layerName = typeNames[0];
                System.out.println("Using first layer from GeoPackage: " + layerName);
            }

            // We don't use org.opengis types; keep it generic
            FeatureSource<?, ?> source = dataStore.getFeatureSource(layerName);

            @SuppressWarnings("unchecked")
            FeatureCollection<?, ?> collection =
                    (FeatureCollection<?, ?>) source.getFeatures();

            Map<String, Double> lengthById = new HashMap<>();

            FeatureIterator<?> it = null;
            try {
                it = collection.features();
                while (it.hasNext()) {
                    Object feature = it.next();

                    Object idVal;
                    Object geomVal;

                    try {
                        // Use reflection to call getAttribute(String) and getDefaultGeometry()
                        Class<?> clazz = feature.getClass();

                        var getAttr = clazz.getMethod("getAttribute", String.class);
                        idVal = getAttr.invoke(feature, idAttribute);

                        var getGeom = clazz.getMethod("getDefaultGeometry");
                        geomVal = getGeom.invoke(feature);
                    } catch (ReflectiveOperationException e) {
                        // If feature implementation is unexpected, skip this feature
                        continue;
                    }

                    if (idVal == null || geomVal == null) {
                        continue;
                    }

                    if (!(geomVal instanceof Geometry geom)) {
                        continue;
                    }

                    String linkId = idVal.toString();
                    double length = geom.getLength(); // units = CRS units

                    lengthById.put(linkId, length);
                }
            } finally {
                if (it != null) {
                    it.close();
                }
            }

            return lengthById;

        } finally {
            dataStore.dispose();
        }
    }
}