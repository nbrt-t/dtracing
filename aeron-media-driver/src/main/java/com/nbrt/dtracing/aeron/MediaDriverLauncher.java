package com.nbrt.dtracing.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.ShutdownSignalBarrier;

import java.io.PrintStream;

/**
 * Standalone Aeron Media Driver launcher.
 * <p>
 * All pipeline components connect to this shared driver via the common
 * Aeron directory (default {@code /dev/shm/aeron}).
 * <p>
 * Configuration via system properties:
 * <ul>
 *   <li>{@code aeron.dir}             — Aeron directory path (default: /dev/shm/aeron)</li>
 *   <li>{@code aeron.threading.mode}  — SHARED | SHARED_NETWORK | DEDICATED (default: DEDICATED)</li>
 *   <li>{@code aeron.term.buffer.length} — term buffer length in bytes (default: 64KB)</li>
 *   <li>{@code aeron.dir.delete.on.start} — clean stale driver files on start (default: true)</li>
 * </ul>
 */
public class MediaDriverLauncher {

    private static final PrintStream OUT = System.out;

    public static void main(String[] args) {
        var aeronDir = System.getProperty("aeron.dir", "/dev/shm/aeron/driver");
        var threadingMode = ThreadingMode.valueOf(
                System.getProperty("aeron.threading.mode", "DEDICATED"));
        var termBufferLength = Integer.parseInt(
                System.getProperty("aeron.term.buffer.length", String.valueOf(64 * 1024)));
        var deleteOnStart = Boolean.parseBoolean(
                System.getProperty("aeron.dir.delete.on.start", "true"));

        var ctx = new MediaDriver.Context()
                .aeronDirectoryName(aeronDir)
                .threadingMode(threadingMode)
                .termBufferSparseFile(false)
                .publicationTermBufferLength(termBufferLength)
                .ipcTermBufferLength(termBufferLength)
                .dirDeleteOnStart(deleteOnStart)
                .dirDeleteOnShutdown(true);

        var barrier = new ShutdownSignalBarrier();

        try (var driver = MediaDriver.launch(ctx)) {
            OUT.println("Aeron Media Driver started");
            OUT.println("  directory      : " + ctx.aeronDirectoryName());
            OUT.println("  threading      : " + ctx.threadingMode());
            OUT.println("  term buffer    : " + ctx.publicationTermBufferLength() + " bytes");
            OUT.println("  delete on start: " + deleteOnStart);

            barrier.await();
            OUT.println("Aeron Media Driver shutting down");
        }
    }
}