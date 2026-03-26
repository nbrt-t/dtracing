package com.nbrt.dtracing.tracecollector;

import com.nbrt.dtracing.common.sbe.MessageHeaderDecoder;
import com.nbrt.dtracing.common.sbe.TraceSpanDecoder;
import io.aeron.Aeron;
import io.aeron.Subscription;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.agrona.concurrent.SleepingMillisIdleStrategy;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone sidecar process that subscribes to the Aeron trace channel,
 * decodes {@code TraceSpan} SBE messages, and exports them as OTLP spans
 * to Tempo via gRPC.
 * <p>
 * Configuration via system properties:
 * <ul>
 *   <li>{@code aeron.dir}          — Aeron directory (default: /dev/shm/aeron/driver)</li>
 *   <li>{@code trace.channel}      — Aeron channel (default: aeron:ipc)</li>
 *   <li>{@code trace.stream.id}    — Aeron stream ID (default: 2001)</li>
 *   <li>{@code otlp.endpoint}      — OTLP gRPC endpoint (default: http://localhost:4317)</li>
 *   <li>{@code flush.interval.ms}  — Flush interval in ms (default: 1000)</li>
 * </ul>
 */
public class TraceCollectorLauncher {

    private static final PrintStream OUT = System.out;

    public static void main(String[] args) {
        String aeronDir = System.getProperty("aeron.dir", "/dev/shm/aeron/driver");
        String channel = System.getProperty("trace.channel", "aeron:ipc");
        int streamId = Integer.parseInt(System.getProperty("trace.stream.id", "2001"));
        String otlpEndpoint = System.getProperty("otlp.endpoint", "http://localhost:4317");
        long flushIntervalMs = Long.parseLong(System.getProperty("flush.interval.ms", "1000"));

        OUT.println("Trace Collector starting");
        OUT.println("  aeron dir      : " + aeronDir);
        OUT.println("  channel        : " + channel);
        OUT.println("  stream         : " + streamId);
        OUT.println("  OTLP endpoint  : " + otlpEndpoint);
        OUT.println("  flush interval : " + flushIntervalMs + " ms");

        var barrier = new ShutdownSignalBarrier();

        try (var otelExporter = new OtelTraceExporter(otlpEndpoint);
             var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
             var subscription = aeron.addSubscription(channel, streamId)) {

            var running = new AtomicBoolean(true);

            Thread pollerThread = Thread.ofPlatform()
                    .daemon(false)
                    .name("trace-collector-poller")
                    .start(() -> pollLoop(subscription, otelExporter, running, flushIntervalMs));

            OUT.println("Trace Collector started — waiting for shutdown signal");
            barrier.await();

            OUT.println("Trace Collector shutting down");
            running.set(false);
            pollerThread.interrupt();
            try {
                pollerThread.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            otelExporter.flush();
            OUT.println("Trace Collector stopped — exported " + otelExporter.exportCount() + " spans");
        }
    }

    private static void pollLoop(Subscription subscription, OtelTraceExporter exporter,
                                  AtomicBoolean running, long flushIntervalMs) {
        var headerDecoder = new MessageHeaderDecoder();
        var traceSpanDecoder = new TraceSpanDecoder();
        IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(1);
        long lastFlushTime = System.currentTimeMillis();

        while (running.get()) {
            int fragmentsRead = subscription.poll((buffer, offset, length, header) ->
                    onFragment(buffer, offset, headerDecoder, traceSpanDecoder, exporter), 64);
            idleStrategy.idle(fragmentsRead);

            // Periodic flush
            long now = System.currentTimeMillis();
            if (now - lastFlushTime >= flushIntervalMs) {
                exporter.flush();
                lastFlushTime = now;
            }
        }
    }

    private static void onFragment(DirectBuffer buffer, int offset,
                                    MessageHeaderDecoder headerDecoder,
                                    TraceSpanDecoder traceSpanDecoder,
                                    OtelTraceExporter exporter) {
        headerDecoder.wrap(buffer, offset);
        if (headerDecoder.templateId() != TraceSpanDecoder.TEMPLATE_ID) {
            return;
        }
        traceSpanDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        exporter.onTraceSpan(traceSpanDecoder);
    }
}