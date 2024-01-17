# Overview

This repository contains a Java application that can result in a message seemingly being "stuck" in a NATS work queue.
Details [here](https://github.com/nats-io/nats-server/discussions/4928).

To run the application open this project in IntelliJ then execute the `Main` run configuration (or just run the `main`
function in `Main.java`). This assumes you have a locally running NATS server on `localhost`, on the default port, with
a username and password of `nats`. To change these details see the first three constants defined in `Main.java`.

The application does the following:

1. Create a work queue stream called `TEST-STREAM-<date/time>`, with a mirror.
2. Publish a hundred messages to this stream.
3. Start a thread which polls NATS for a single message.
4. Whenever this thread gets a message, it starts a new "worker" thread (using a pre-created thread pool) to process
   the message.
5. The message is processed by the worker thread sleeping for 1.5 seconds then acknowledging the message back to NATS.
6. There are only ever a maximum of four worker threads. The poller thread will only poll when there's a worker
   available (roughly simulating how Kotlin coroutine channels work).
7. Three seconds after the main thread started the poller thread, it kills everything by unsubscribing the JetStream
   subscription and shutting down the thread pool. This means some messages have been retrieved from NATS and not yet
   acknowledged.
8. Steps 3-7 are repeated forever, waiting two seconds between each execution.
9. Eventually (say after a few minutes) the result is that a message is seemingly "stuck" in NATS, and never made
   available to the consumer, as shown below...

```
$ nats stream ls
╭────────────────────────────────────────────────────────────────────────────────────────────────────────────────╮
│                                                     Streams                                                    │
├────────────────────────────────────────┬─────────────┬─────────────────────┬──────────┬─────────┬──────────────┤
│ Name                                   │ Description │ Created             │ Messages │ Size    │ Last Message │
├────────────────────────────────────────┼─────────────┼─────────────────────┼──────────┼─────────┼──────────────┤
│ TEST-STREAM-2024-01-17-12-37-55        │             │ 2024-01-17 12:37:55 │ 1        │ 62 B    │ 1h39m51s     │
│ TEST-STREAM-2024-01-17-12-37-55-MIRROR │             │ 2024-01-17 12:37:55 │ 100      │ 6.1 KiB │ 1h39m51s     │
╰────────────────────────────────────────┴─────────────┴─────────────────────┴──────────┴─────────┴──────────────╯
```

```
$ nats consumer sub --no-ack TEST-STREAM-2024-01-17-12-37-55 TEST-STREAM-2024-01-17-12-37-55-C
nats: error: no message received: nats: timeout
```

```
$ nats consumer info TEST-STREAM-2024-01-17-12-37-55 TEST-STREAM-2024-01-17-12-37-55-C
Information for Consumer TEST-STREAM-2024-01-17-12-37-55 > TEST-STREAM-2024-01-17-12-37-55-C created 2024-01-17T12:37:55Z

Configuration:

                    Name: TEST-STREAM-2024-01-17-12-37-55-C
               Pull Mode: true
          Filter Subject: test.stream.2024-01-17-12-37-55
          Deliver Policy: All
              Ack Policy: Explicit
                Ack Wait: 30.00s
           Replay Policy: Instant
         Max Ack Pending: 1,000
       Max Waiting Pulls: 512

State:

  Last Delivered Message: Consumer sequence: 196 Stream sequence: 100 Last delivery: 2h8m33s ago
    Acknowledgment Floor: Consumer sequence: 196 Stream sequence: 100 Last Ack: 2h8m32s ago
        Outstanding Acks: 0 out of maximum 1,000
    Redelivered Messages: 0
    Unprocessed Messages: 0
           Waiting Pulls: 0 of maximum 512
```
