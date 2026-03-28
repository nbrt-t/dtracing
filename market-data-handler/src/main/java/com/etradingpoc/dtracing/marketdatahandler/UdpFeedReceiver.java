package com.etradingpoc.dtracing.marketdatahandler;

import com.etradingpoc.dtracing.common.sbe.FxFeedDeltaDecoder;
import com.etradingpoc.dtracing.common.sbe.MessageHeaderDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;

@Component
public class UdpFeedReceiver implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(UdpFeedReceiver.class);

    private final UdpFeedProperties properties;
    private final MarketDataDeltaHandler handler;

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread receiverThread;

    public UdpFeedReceiver(UdpFeedProperties properties, MarketDataDeltaHandler handler) {
        this.properties = properties;
        this.handler = handler;
    }

    @Override
    public void start() {
        running = true;
        receiverThread = Thread.ofPlatform()
                .daemon(false)
                .name("udp-feed-receiver-" + properties.ecn().toLowerCase())
                .start(this::receiveLoop);
        log.info("UDP feed receiver [{}] starting on {}:{}{}", properties.ecn(),
                properties.bindAddress(), properties.port(),
                properties.isMulticast() ? " multicast=" + properties.multicastGroup() : "");
    }

    @Override
    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (receiverThread != null) {
            receiverThread.interrupt();
        }
        log.info("UDP feed receiver [{}] stopped", properties.ecn());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void receiveLoop() {
        var buf = new byte[properties.bufferSize()];
        var directBuffer = new UnsafeBuffer(buf);
        var headerDecoder = new MessageHeaderDecoder();
        var deltaDecoder = new FxFeedDeltaDecoder();
        long expectedSeq = -1;

        try {
            socket = openSocket();
            var packet = new DatagramPacket(buf, buf.length);

            while (running) {
                socket.receive(packet);
                headerDecoder.wrap(directBuffer, 0);
                deltaDecoder.wrapAndApplyHeader(directBuffer, 0, headerDecoder);

                long seq = deltaDecoder.sequenceNumber();
                if (expectedSeq >= 0 && seq != expectedSeq) {
                    if (log.isWarnEnabled()) {
                        log.warn("Sequence gap detected: expected={} received={} missed={}",
                                expectedSeq, seq, seq - expectedSeq);
                    }
                }
                expectedSeq = seq + 1;

                handler.onDelta(deltaDecoder);
            }
        } catch (SocketException e) {
            if (running) {
                log.error("Socket error in UDP feed receiver", e);
            }
        } catch (Exception e) {
            log.error("Unexpected error in UDP feed receiver", e);
        }
    }

    private DatagramSocket openSocket() throws Exception {
        if (properties.isMulticast()) {
            var multicastSocket = new MulticastSocket(properties.port());
            var group = InetAddress.getByName(properties.multicastGroup());
            var networkInterface = NetworkInterface.getByInetAddress(
                    InetAddress.getByName(properties.bindAddress()));
            multicastSocket.joinGroup(new InetSocketAddress(group, properties.port()), networkInterface);
            log.info("Joined multicast group {} on interface {}", properties.multicastGroup(), networkInterface);
            return multicastSocket;
        }
        return new DatagramSocket(properties.port(), InetAddress.getByName(properties.bindAddress()));
    }
}