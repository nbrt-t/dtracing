package com.nbrt.dtracing.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.net.InetSocketAddress;
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

        log.info("Starting replay of {} feed(s) at {}x speed", feeds.size(), properties.speedMultiplier());

        List<Thread> threads = new ArrayList<>();
        for (var feed : feeds) {
            var csvPath = Path.of(feed.file());
            if (!csvPath.toFile().exists()) {
                log.error("[{}] CSV file not found: {}", feed.ecn(), csvPath.toAbsolutePath());
                continue;
            }

            var target = new InetSocketAddress(feed.targetHost(), feed.targetPort());
            var task = new FeedReplayTask(feed.ecn(), csvPath, target, properties.speedMultiplier());

            var thread = Thread.ofVirtual()
                    .name("replay-" + feed.ecn().toLowerCase())
                    .start(task);
            threads.add(thread);

            log.info("[{}] Replaying {} → {}", feed.ecn(), csvPath, target);
        }

        for (var thread : threads) {
            thread.join();
        }

        log.info("All feeds replayed. Simulator shutting down.");
    }
}