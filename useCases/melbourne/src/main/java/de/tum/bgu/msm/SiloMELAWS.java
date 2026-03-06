package de.tum.bgu.msm;

import de.tum.bgu.msm.container.ModelContainer;
import de.tum.bgu.msm.health.DataBuilderHealth;
import de.tum.bgu.msm.health.HealthDataContainerImpl;
import de.tum.bgu.msm.io.output.MultiFileResultsMonitor;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.utils.SiloUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest;
import software.amazon.awssdk.services.ec2.model.StopInstancesResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Implements SILO for the Great Melbourne
 *
 * @author Qin Zhang*/


public class SiloMELAWS {

    private final static Logger logger = LogManager.getLogger(SiloMELAWS.class);
    private final static String region = "ap-southeast-2";// Replace with your AWS region (e.g., “us-east-1")
    private final static String instanceId = "i-0b6a96bdbf7b41d16"; // Replace with your instance id


    public static void main(String[] args) throws IOException {
        SiloUtil.captureLog(Level.INFO, "Started SILO land use model for Great Melbourne region");
        SiloUtil.captureLog(Level.INFO, "Scenario properties: " + args[0]);
        Properties properties = SiloUtil.siloInitialization(args[0]);

        // Extract scenario name and construct dynamic paths
        String scenarioName = properties.main.scenarioName;
        String workingDir = System.getProperty("user.dir");
        String cityName = Paths.get(workingDir).getFileName().toString();

        String outputDir = workingDir + "/scenOutput/" + scenarioName + "/";
        String folderName = cityName + "/simulationResults/" + scenarioName + "/";

        logger.info("Working directory: {}", workingDir);
        logger.info("City name: {}", cityName);
        logger.info("Scenario name: {}", scenarioName);
        logger.info("Output directory: {}", outputDir);
        logger.info("S3 folder name: {}", folderName);

        Config config = null;
        if (args.length > 1 && args[1] != null) {
            config = ConfigUtils.loadConfig(args[1]);
        }
        HealthDataContainerImpl dataContainer = DataBuilderHealth.getModelDataForMelbourne(properties, config);
        DataBuilderHealth.read(properties, dataContainer, config);
        ModelContainer modelContainer = ModelBuilderMEL.getModelContainerForMelbourne(dataContainer, properties, config);

        SiloModel model = new SiloModel(properties, dataContainer, modelContainer);
        //model.addResultMonitor(new ResultsMonitorMuc(dataContainer, properties));
        model.addResultMonitor(new MultiFileResultsMonitor(dataContainer, properties));
        //model.addResultMonitor(new HouseholdSatisfactionMonitor(dataContainer, properties, modelContainer));
        //model.addResultMonitor(new ModalSharesResultMonitor(dataContainer, properties));
        model.runModel();
        logger.info("Finished SILO.");

        // Upload files to S3
        //Note that: File size larger than 5 GB is not allowed for single PUT uploading in AWS S3. It is possible to upload it via terminal
        //TODO: now we copy scenOutput to S3 via terminal, later can improve it by adapting the code to implement Multipart upload
        //uploadToS3(outputDir, bucketName, folderName, region);
        // Stop the EC2 instance
        stopEC2Instance();
    }

    public static void uploadToS3(String outputDir, String bucketName, String folderName, String region) {
        S3Client s3Client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(InstanceProfileCredentialsProvider.create())
                .build();
        File folder = new File(outputDir);        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("The specified directory does not exist or is not a directory: " + outputDir);
            return;
        }
        uploadDirectoryRecursively(folder, bucketName, folderName, s3Client, outputDir);
        s3Client.close();
    }


    private static void uploadDirectoryRecursively(File folder, String bucketName, String folderName, S3Client s3Client, String baseOutputDir) {
        for (File file : Objects.requireNonNull(folder.listFiles())) {
            if (file.isFile()) {
                String keyName = folderName + (folderName.endsWith("/") ? "" : "/") + file.getPath()
                        .replace(baseOutputDir, "");
                keyName = keyName.startsWith("/") ? keyName.substring(1) : keyName;                System.out.println("Uploading file: " + keyName);
                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(keyName)
                        .build();
                s3Client.putObject(putObjectRequest, Path.of(file.getAbsolutePath()));
                System.out.println("Uploaded " + keyName + " to bucket " + bucketName);
            } else if (file.isDirectory()) {
                uploadDirectoryRecursively(file, bucketName, folderName, s3Client, baseOutputDir);
            }
        }
    }

    private static void stopEC2Instance() {
        Ec2Client ec2Client = Ec2Client.builder()
                .region(Region.of(SiloMELAWS.region))
                .credentialsProvider(InstanceProfileCredentialsProvider.create())
                .build();
        StopInstancesRequest stopRequest = StopInstancesRequest.builder()
                .instanceIds(SiloMELAWS.instanceId)
                .build();
        StopInstancesResponse response = ec2Client.stopInstances(stopRequest);
        System.out.println("Stopping instance: " + SiloMELAWS.instanceId);
        System.out.println("State: " + response.stoppingInstances());
        ec2Client.close();
    }
}
