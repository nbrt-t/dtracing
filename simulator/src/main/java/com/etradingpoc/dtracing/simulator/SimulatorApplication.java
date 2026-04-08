package com.etradingpoc.dtracing.simulator;

import io.aeron.Aeron;
import io.aeron.Publication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulatorApplication.class);

    private final SimulatorProperties properties;

    public SimulatorApplication(SimulatorProperties properties) {
        this.properties = properties;
    }

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        List<SimulatorProperties.FeedConfig> feeds = properties.feeds();
        if (feeds.isEmpty()) {
            log.warn("No feeds configured — nothing to replay. Set simulator.feeds[n].* in application.properties.");
            return;
        }

        log.info("Starting continuous replay of {} feed(s) cycling intensities={} via Aeron IPC dir={}",
                feeds.size(), properties.intensities(), properties.aeronDir());

        try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(properties.aeronDir()))) {
            List<Thread> threads = new ArrayList<>();

            for (var feed : feeds) {
                var csvPath = Path.of(feed.file());
                if (!csvPath.toFile().exists()) {
                    log.error("[{}] CSV file not found: {}", feed.ecn(), csvPath.toAbsolutePath());
                    continue;
                }

                Publication publication = aeron.addPublication("aeron:ipc", feed.streamId());
                var task = new FeedReplayTask(feed.ecn(), csvPath, publication, properties.intensities());

                var thread = Thread.ofVirtual()
                        .name("replay-" + feed.ecn().toLowerCase())
                        .start(task);
                threads.add(thread);

                log.info("[{}] Replaying {} → aeron:ipc stream={}", feed.ecn(), csvPath, feed.streamId());
            }

            Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
                log.info("Shutdown requested — interrupting {} replay thread(s)", threads.size());
                threads.forEach(Thread::interrupt);
                for (var t : threads) {
                    try { t.join(5_000); } catch (InterruptedException ignored) {}
                }
            }));

            for (var thread : threads) {
                thread.join();
            }
        }

        log.info("Simulator shutting down.");
    }
}
