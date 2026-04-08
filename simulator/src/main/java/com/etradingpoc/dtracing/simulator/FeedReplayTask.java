package com.etradingpoc.dtracing.simulator;

import com.etradingpoc.dtracing.common.sbe.CcyPair;
import com.etradingpoc.dtracing.common.sbe.FxFeedDeltaEncoder;
import com.etradingpoc.dtracing.common.sbe.MessageHeaderEncoder;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Replays a single ECN CSV file as {@code FxFeedDelta} SBE messages over Aeron IPC,
 * looping continuously and cycling through the configured intensity (speed multiplier)
 * list on each pass until the thread is interrupted.
 */
public class FeedReplayTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(FeedReplayTask.class);

    private static final int BUF_SIZE = MessageHeaderEncoder.ENCODED_LENGTH + FxFeedDeltaEncoder.BLOCK_LENGTH;
    private static final long LOG_SAMPLE_INTERVAL = 10_000;

    private final String ecn;
    private final Path csvFile;
    private final Publication publication;
    private final List<Double> intensities;

    public FeedReplayTask(String ecn, Path csvFile, Publication publication, List<Double> intensities) {
        this.ecn = ecn;
        this.csvFile = csvFile;
        this.publication = publication;
        this.intensities = intensities;
    }

    @Override
    public void run() {
        var directBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUF_SIZE));
        var headerEncoder = new MessageHeaderEncoder();
        var deltaEncoder = new FxFeedDeltaEncoder();

        headerEncoder.wrap(directBuffer, 0)
                .blockLength(FxFeedDeltaEncoder.BLOCK_LENGTH)
                .templateId(FxFeedDeltaEncoder.TEMPLATE_ID)
                .schemaId(FxFeedDeltaEncoder.SCHEMA_ID)
                .version(FxFeedDeltaEncoder.SCHEMA_VERSION);

        long sequenceCounter = epochNanosNow() ^ ((long) ecn.hashCode() << 32);
        int pass = 0;

        while (!Thread.currentThread().isInterrupted()) {
            double speed = intensities.get(pass % intensities.size());
            log.info("[{}] Starting pass {} at {}x speed", ecn, pass + 1, speed);
            sequenceCounter = replayOnce(directBuffer, deltaEncoder, sequenceCounter, speed);
            pass++;
        }

        log.info("[{}] Replay stopped after {} pass(es)", ecn, pass);
    }

    /**
     * Replays the CSV file once at the given speed multiplier.
     * Returns the updated monotonic sequence counter for continuity across passes.
     */
    private long replayOnce(UnsafeBuffer directBuffer, FxFeedDeltaEncoder deltaEncoder,
                            long sequenceCounter, double speedMultiplier) {
        long prevCsvTimestamp = -1;
        int rowCount = 0;
        long dropCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(csvFile)) {
            String line;

            while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("sequence_number")) {
                    continue;
                }

                String[] fields = line.split(",", -1);
                if (fields.length < 8) {
                    log.warn("[{}] Skipping malformed row: {}", ecn, line);
                    continue;
                }

                CcyPair ccyPair  = CcyPair.valueOf(fields[2].strip());
                long csvTimestamp = Long.parseLong(fields[3].strip());
                long bidMantissa = decimalToMantissa(fields[4].strip());
                int  bidSize     = Integer.parseInt(fields[5].strip());
                long askMantissa = decimalToMantissa(fields[6].strip());
                int  askSize     = Integer.parseInt(fields[7].strip());

                if (prevCsvTimestamp >= 0 && speedMultiplier > 0) {
                    long deltaNs = csvTimestamp - prevCsvTimestamp;
                    if (deltaNs > 0) {
                        long sleepNs = (long) (deltaNs / speedMultiplier);
                        LockSupport.parkNanos(sleepNs);
                        if (Thread.currentThread().isInterrupted()) break;
                    }
                }
                prevCsvTimestamp = csvTimestamp;

                // Capture after the sleep so feedTimestamp reflects actual send time,
                // not the pre-sleep ideal. On WSL2/coarse-timer hosts parkNanos overshoots
                // significantly, making pre-sleep timestamps appear 10–100ms stale.
                long rebasedTimestamp = epochNanosNow();

                deltaEncoder.wrap(directBuffer, MessageHeaderEncoder.ENCODED_LENGTH);
                deltaEncoder.sequenceNumber(++sequenceCounter);
                deltaEncoder.ccyPair(ccyPair);
                deltaEncoder.timestamp(rebasedTimestamp);
                deltaEncoder.askPrice().mantissa(askMantissa);
                deltaEncoder.askSize(askSize);
                deltaEncoder.bidPrice().mantissa(bidMantissa);
                deltaEncoder.bidSize(bidSize);

                long result = publication.offer(directBuffer, 0, BUF_SIZE);
                if (result >= 0) {
                    rowCount++;
                } else {
                    dropCount++;
                    if (log.isDebugEnabled() && dropCount % LOG_SAMPLE_INTERVAL == 0) {
                        log.debug("[{}] Aeron back-pressure: result={} dropped={}", ecn, result, dropCount);
                    }
                }

                if (log.isDebugEnabled() && rowCount % LOG_SAMPLE_INTERVAL == 0) {
                    log.debug("[{}] seq={} {} bid={}/{} ask={}/{} ts={}",
                            ecn, sequenceCounter, ccyPair,
                            bidMantissa, bidSize, askMantissa, askSize, rebasedTimestamp);
                }
            }

            log.info("[{}] Pass complete at {}x — {} offered, {} dropped", ecn, speedMultiplier, rowCount, dropCount);

        } catch (IOException e) {
            log.error("[{}] I/O error replaying {}", ecn, csvFile, e);
        }

        return sequenceCounter;
    }

    private static long epochNanosNow() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    /**
     * Converts a decimal price string (e.g. "1.08760") to a Decimal5 mantissa (e.g. 108760).
     */
    static long decimalToMantissa(String price) {
        int dot = price.indexOf('.');
        if (dot < 0) {
            return Long.parseLong(price) * 100_000L;
        }
        String intPart = price.substring(0, dot);
        String fracPart = price.substring(dot + 1);
        if (fracPart.length() > 5) {
            fracPart = fracPart.substring(0, 5);
        } else {
            fracPart = fracPart + "00000".substring(fracPart.length());
        }
        long mantissa = Long.parseLong(intPart) * 100_000L + Long.parseLong(fracPart);
        return price.charAt(0) == '-' ? -mantissa : mantissa;
    }
}
