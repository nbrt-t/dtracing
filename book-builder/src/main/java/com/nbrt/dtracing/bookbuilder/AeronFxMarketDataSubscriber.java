package com.nbrt.dtracing.bookbuilder;

import com.nbrt.dtracing.common.sbe.FxMarketDataDecoder;
import com.nbrt.dtracing.common.sbe.MessageHeaderDecoder;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@code FxMarketData} messages published by MarketDataHandler instances
 * over Aeron IPC. Polls on a dedicated platform thread using a sleeping idle strategy.
 */
@Component
public class AeronFxMarketDataSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AeronFxMarketDataSubscriber.class);

    private final AeronSubscriberProperties properties;
    private final FxMarketDataHandler handler;

    private Aeron aeron;
    private Subscription subscription;
    private Thread pollerThread;
    private volatile boolean running;

    public AeronFxMarketDataSubscriber(AeronSubscriberProperties properties, FxMarketDataHandler handler) {
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
                .name("aeron-market-data-subscriber")
                .start(this::pollLoop);

        log.info("Aeron FxMarketData subscriber started: channel={} stream={} dir={}",
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
        log.info("Aeron FxMarketData subscriber stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        var headerDecoder = new MessageHeaderDecoder();
        var marketDataDecoder = new FxMarketDataDecoder();
        IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(1);

        while (running) {
            int fragmentsRead = subscription.poll((buffer, offset, length, header) ->
                    onFragment(buffer, offset, headerDecoder, marketDataDecoder), 10);
            idleStrategy.idle(fragmentsRead);
        }
    }

    private void onFragment(DirectBuffer buffer, int offset,
                            MessageHeaderDecoder headerDecoder,
                            FxMarketDataDecoder marketDataDecoder) {
        headerDecoder.wrap(buffer, offset);

        if (headerDecoder.templateId() != FxMarketDataDecoder.TEMPLATE_ID) {
            return;
        }

        marketDataDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        handler.onMarketData(marketDataDecoder);
    }
}