package org.example;

import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.*;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;

public class Main {
    private static final String NATS_SERVER = "localhost";
    private static final String NATS_USERNAME = "nats";
    private static final String NATS_PASSWORD = "nats";
    private static final String TEST_RUN_ID = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());
    private static final String STREAM_NAME = "TEST-STREAM-" + TEST_RUN_ID;
    private static final String MIRROR_STREAM_NAME = STREAM_NAME + "-MIRROR";
    private static final String CONSUMER_SUBJECT = "test.stream." + TEST_RUN_ID.toLowerCase();
    private static final String CONSUMER_NAME = STREAM_NAME + "-C";

    public static void main(String[] args) throws Exception {
        try {
            Logging.log("Application starting...");

            final var errorMode = !Arrays.asList(args).contains("--no-err");
            Logging.log("Error mode = " + errorMode);

            createAndPopulateStream();

            for (int i = 1; ; i++) {
                Logging.log("===== Starting run " + i + " =====");
                execute(errorMode ? 4000 : Long.MAX_VALUE);
                Logging.log("===== Run " + i + " ended =====");
                System.out.println();
                Thread.sleep(2000);
            }
        } catch (Throwable e) {
            Logging.log("Application ended unexpectedly");
            e.printStackTrace();
        }
    }

    private static void execute(long sleepBeforeShutDown) throws Exception {
        try (final var natsConnection = Nats.connect(
                Options.builder()
                        .server("localhost")
                        .userInfo("nats", "nats")
                        .build()
        )) {
            final var natsConsumer = new NatsConsumer(
                    natsConnection.jetStream(),
                    STREAM_NAME,
                    CONSUMER_NAME,
                    CONSUMER_SUBJECT
            );
            natsConsumer.start();

            Thread.sleep(sleepBeforeShutDown);

            natsConsumer.shutDown();
        }
    }

    private static void createAndPopulateStream() throws Exception {
        try (final var natsConnection = Nats.connect(
                Options.builder()
                        .server(NATS_SERVER)
                        .userInfo(NATS_USERNAME, NATS_PASSWORD)
                        .build()
        )) {
            final var jetStreamManagement = natsConnection.jetStreamManagement();

            // Create the mirror
            jetStreamManagement.addStream(
                    StreamConfiguration.builder()
                            .name(MIRROR_STREAM_NAME)
                            .subjects(CONSUMER_SUBJECT)
                            .retentionPolicy(RetentionPolicy.Limits)
                            .maxAge(Duration.ofDays(1))
                            .replicas(1)
                            .storageType(StorageType.File)
                            .discardPolicy(DiscardPolicy.Old)
                            .allowDirect(true)
                            .build()
            );

            // Create the main stream
            jetStreamManagement.addStream(
                    StreamConfiguration.builder()
                            .name(STREAM_NAME)
                            .retentionPolicy(RetentionPolicy.WorkQueue)
                            .replicas(1)
                            .storageType(StorageType.File)
                            .discardPolicy(DiscardPolicy.Old)
                            .allowDirect(true)
                            .mirrorDirect(true)
                            .mirror(
                                    Mirror.builder()
                                            .name(MIRROR_STREAM_NAME)
                                            .build()
                            )
                            .build()
            );

            // Publish messages to the stream
            final var jetStream = natsConnection.jetStream();
            for (int i = 0; i < 100; i++) {
                jetStream.publish(CONSUMER_SUBJECT, String.valueOf(i).getBytes());
            }
        }
    }
}