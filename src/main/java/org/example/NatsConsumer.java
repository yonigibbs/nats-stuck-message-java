package org.example;

import io.nats.client.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

class NatsConsumer {
    private static final int NUM_WORKERS = 4;
    private final JetStream jetStream;
    private final String streamName;
    private final String consumerName;
    private final String consumerSubject;
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final ExecutorService threadPool = Executors.newFixedThreadPool(
            // Add an extra thread for the poller
            NUM_WORKERS + 1,
            r -> {
                final var thread = Executors.defaultThreadFactory().newThread(r);
                thread.setName("nats-consumer-" + threadCounter.incrementAndGet());
                return thread;
            }
    );
    private JetStreamSubscription jetStreamSubscription;
    private final AtomicInteger freeWorkerThreadsCount = new AtomicInteger(NUM_WORKERS);
    private boolean cancelled = false;

    public NatsConsumer(
            JetStream jetStream,
            String streamName,
            String consumerName,
            String consumerSubject
    ) {
        this.jetStream = jetStream;
        this.streamName = streamName;
        this.consumerName = consumerName;
        this.consumerSubject = consumerSubject;
    }

    public void start() throws Exception {
        Logging.log("Starting NATS consumers...");
        jetStreamSubscription = jetStream.subscribe(
                consumerSubject,
                PullSubscribeOptions.builder()
                        .stream(streamName)
                        .durable(consumerName)
                        .build()
        );

        threadPool.execute(() -> {
            pollAndProcessMessages();
        });
    }

    private void pollAndProcessMessages() {
        while (!cancelled) {
            List<Message> messages;
            try {
                // Simulate (in a really hacky horrible way) Kotlin channels, i.e. wait for a worker to become available.
                while (freeWorkerThreadsCount.get() == 0) {
                    Thread.sleep(50);
                }
                messages = jetStreamSubscription.fetch(1, Duration.ofSeconds(5));
            } catch (IllegalStateException e) {
                Logging.log("JetStream subscription became inactive");
                break;
            } catch (Exception e) {
                Logging.log("Error fetching message from JetStream", e);
                break;
            }

            if (!messages.isEmpty()) {
                final var message = messages.get(0);
                threadPool.execute(() -> {
                    try {
                        freeWorkerThreadsCount.decrementAndGet();
                        Logging.log(
                                "Processing message " + message.metaData().streamSequence() + " from NATS (deliveredCount=" + message.metaData().deliveredCount() + ")."
                        );

                        // Simulate processing taking a bit of time
                        Thread.sleep(1500);

                        message.ack();

                        Logging.log("Acknowledged message " + message.metaData().streamSequence() + " from NATS.");
                    } catch (InterruptedException e) {
                        Logging.log("Message processing interrupted.");
                    } catch (Throwable e) {
                        Logging.log("Caught exception. Shutting down NATS consumer", e);
                    } finally {
                        freeWorkerThreadsCount.incrementAndGet();
                    }
                });
            }
        }

        shutDown();
    }

    public void shutDown() {
        if (!cancelled) {
            Logging.log("Shutting down...");
            cancelled = true;
            cleanUnsubscribe(jetStreamSubscription);
            threadPool.shutdown();
            Logging.log("Shut down");
        }
    }

    private void cleanUnsubscribe(Subscription subscription) {
        try {
            if (subscription.isActive()) subscription.unsubscribe();
        } catch (IllegalStateException e) {
            // Ignore: this subscription has already been unsubscribed.
        } catch (Throwable e) {
            // Log and continue.
            Logging.log("Error unsubscribing from NATS", e);
        }
    }
}
