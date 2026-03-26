package com.nbrt.dtracing.simulator;

import com.nbrt.dtracing.common.sbe.CcyPair;
import com.nbrt.dtracing.common.sbe.FxFeedDeltaEncoder;
import com.nbrt.dtracing.common.sbe.MessageHeaderEncoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.locks.LockSupport;

/**
 * Replays a single ECN CSV file to its target market-data-handler UDP port.
 * Each row is SBE-encoded as an {@code FxFeedDelta} message and sent as one datagram.
 * Pacing honours the timestamp deltas between rows, scaled by {@code speedMultiplier}.
 */
public class FeedReplayTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(FeedReplayTask.class);

    private static final int BUF_SIZE = MessageHeaderEncoder.ENCODED_LENGTH + FxFeedDeltaEncoder.BLOCK_LENGTH;

    private final String ecn;
    private final Path csvFile;
    private final InetSocketAddress target;
    private final double speedMultiplier;

    public FeedReplayTask(String ecn, Path csvFile, InetSocketAddress target, double speedMultiplier) {
        this.ecn = ecn;
        this.csvFile = csvFile;
        this.target = target;
        this.speedMultiplier = speedMultiplier;
    }

    @Override
    public void run() {
        var buf = ByteBuffer.allocate(BUF_SIZE);
        var directBuffer = new UnsafeBuffer(buf);
        var headerEncoder = new MessageHeaderEncoder();
        var deltaEncoder = new FxFeedDeltaEncoder();

        headerEncoder.wrap(directBuffer, 0)
                .blockLength(FxFeedDeltaEncoder.BLOCK_LENGTH)
                .templateId(FxFeedDeltaEncoder.TEMPLATE_ID)
                .schemaId(FxFeedDeltaEncoder.SCHEMA_ID)
                .version(FxFeedDeltaEncoder.SCHEMA_VERSION);

        long csvBaseTimestamp = -1;
        long wallBaseNanos = -1;
        long prevCsvTimestamp = -1;
        int rowCount = 0;

        try (var socket = new DatagramSocket();
             BufferedReader reader = Files.newBufferedReader(csvFile)) {

            var packet = new DatagramPacket(buf.array(), BUF_SIZE, target);
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("sequence_number")) {
                    continue;
                }

                String[] fields = line.split(",", -1);
                if (fields.length < 8) {
                    log.warn("[{}] Skipping malformed row: {}", ecn, line);
                    continue;
                }

                long sequenceNumber = Long.parseUnsignedLong(fields[0].strip());
                // fields[1] = ecn (used for routing, not encoded)
                CcyPair ccyPair       = CcyPair.valueOf(fields[2].strip());
                long csvTimestamp      = Long.parseLong(fields[3].strip());
                long bidMantissa      = decimalToMantissa(fields[4].strip());
                int  bidSize          = Integer.parseInt(fields[5].strip());
                long askMantissa      = decimalToMantissa(fields[6].strip());
                int  askSize          = Integer.parseInt(fields[7].strip());

                // Anchor first row to current wall-clock time
                if (csvBaseTimestamp < 0) {
                    csvBaseTimestamp = csvTimestamp;
                    wallBaseNanos = epochNanosNow();
                }

                // Rebase timestamp: preserve CSV deltas, anchor to wall-clock
                long rebasedTimestamp = wallBaseNanos + (csvTimestamp - csvBaseTimestamp);

                // Pace: sleep for the timestamp delta scaled by speed multiplier
                if (prevCsvTimestamp >= 0 && speedMultiplier > 0) {
                    long deltaNs = csvTimestamp - prevCsvTimestamp;
                    if (deltaNs > 0) {
                        long sleepNs = (long) (deltaNs / speedMultiplier);
                        LockSupport.parkNanos(sleepNs);
                    }
                }
                prevCsvTimestamp = csvTimestamp;

                // Encode SBE message (header already written once, reuse it)
                deltaEncoder.wrap(directBuffer, MessageHeaderEncoder.ENCODED_LENGTH);
                deltaEncoder.sequenceNumber(sequenceNumber);
                deltaEncoder.ccyPair(ccyPair);
                deltaEncoder.timestamp(rebasedTimestamp);
                deltaEncoder.askPrice().mantissa(askMantissa);
                deltaEncoder.askSize(askSize);
                deltaEncoder.bidPrice().mantissa(bidMantissa);
                deltaEncoder.bidSize(bidSize);

                socket.send(packet);
                rowCount++;
            }

            log.info("[{}] Replay complete — {} datagrams sent to {}", ecn, rowCount, target);

        } catch (IOException e) {
            log.error("[{}] I/O error replaying {}", ecn, csvFile, e);
        }
    }

    /**
     * Returns current wall-clock time as nanoseconds since Unix epoch.
     */
    private static long epochNanosNow() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    /**
     * Converts a decimal price string (e.g. "1.08760") to a Decimal5 mantissa (e.g. 108760).
     */
    static long decimalToMantissa(String price) {
        // Use BigDecimal-free integer arithmetic to avoid allocation:
        // split on '.', pad/truncate fractional part to exactly 5 digits
        int dot = price.indexOf('.');
        if (dot < 0) {
            return Long.parseLong(price) * 100_000L;
        }
        String intPart = price.substring(0, dot);
        String fracPart = price.substring(dot + 1);
        // Pad or truncate to 5 digits
        if (fracPart.length() > 5) {
            fracPart = fracPart.substring(0, 5);
        } else {
            fracPart = fracPart + "00000".substring(fracPart.length());
        }
        long mantissa = Long.parseLong(intPart) * 100_000L + Long.parseLong(fracPart);
        return price.charAt(0) == '-' ? -mantissa : mantissa;
    }
}