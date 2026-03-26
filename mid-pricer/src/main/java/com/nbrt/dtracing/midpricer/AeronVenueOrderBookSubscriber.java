package com.nbrt.dtracing.midpricer;

import com.nbrt.dtracing.common.sbe.MessageHeaderDecoder;
import com.nbrt.dtracing.common.sbe.VenueOrderBookDecoder;
import io.aeron.Aeron;
import io.aeron.Subscription;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@code VenueOrderBook} messages published by the BookBuilder
 * over Aeron IPC. Polls on a dedicated platform thread.
 */
@Component
public class AeronVenueOrderBookSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AeronVenueOrderBookSubscriber.class);

    private final AeronSubscriberProperties properties;
    private final VenueOrderBookHandler handler;

    private Aeron aeron;
    private Subscription subscription;
    private Thread pollerThread;
    private volatile boolean running;

    public AeronVenueOrderBookSubscriber(AeronSubscriberProperties properties, VenueOrderBookHandler handler) {
        this.properties = properties;
        this.handler = handler;
    }

    @Override
    public void start() {
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(properties.dir()));
        subscription = aeron.addSubscription(properties.channel(), properties.streamId());
        running = true;

        pollerThread = Thread.ofPlatform()
                .daemon(false)
                .name("aeron-venue-order-book-subscriber")
                .start(this::pollLoop);

        log.info("Aeron VenueOrderBook subscriber started: channel={} stream={} dir={}",
                properties.channel(), properties.streamId(), properties.dir());
    }

    @Override
    public void stop() {
        running = false;
        if (pollerThread != null) {
            pollerThread.interrupt();
        }
        if (subscription != null) {
            subscription.close();
        }
        if (aeron != null) {
            aeron.close();
        }
        log.info("Aeron VenueOrderBook subscriber stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        var headerDecoder = new MessageHeaderDecoder();
        var venueOrderBookDecoder = new VenueOrderBookDecoder();
        IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(1);

        while (running) {
            int fragmentsRead = subscription.poll((buffer, offset, length, header) ->
                    onFragment(buffer, offset, headerDecoder, venueOrderBookDecoder), 10);
            idleStrategy.idle(fragmentsRead);
        }
    }

    private void onFragment(DirectBuffer buffer, int offset,
                            MessageHeaderDecoder headerDecoder,
                            VenueOrderBookDecoder venueOrderBookDecoder) {
        headerDecoder.wrap(buffer, offset);

        if (headerDecoder.templateId() != VenueOrderBookDecoder.TEMPLATE_ID) {
            return;
        }

        venueOrderBookDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        handler.onVenueOrderBook(venueOrderBookDecoder);
    }
}