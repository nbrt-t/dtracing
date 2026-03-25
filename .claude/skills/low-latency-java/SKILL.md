---
name: low-latency-java
description: Enforces low-latency Java coding practices for hot-path FX pricing code. Use when writing or reviewing code on the critical data path (market data handling, order book updates, pricing calculations, encoding/decoding).
---

# Low-Latency Java Guidelines

Apply these rules when writing or reviewing code on the hot path (market data ingestion, book building, pricing, SBE encode/decode). Code off the hot path (startup, configuration, admin endpoints) does not need to follow these constraints.

## Memory & GC

- **Zero allocation on the hot path.** No object creation, boxing, varargs, or iterator allocation in the receive/process/send loop. Pre-allocate all buffers, flyweights, and working state at startup.
- **Use primitive types.** Prefer `long`, `int`, `double` over `Long`, `Integer`, `Double`. Never auto-box on the hot path.
- **Reuse flyweight decoders/encoders.** SBE decoders and encoders are designed to be wrapped repeatedly over the same `DirectBuffer`—never allocate a new decoder per message.
- **Avoid `String` creation.** Use enum ordinals, integer codes, or fixed-length byte arrays instead. If logging is required, guard with `if (log.isDebugEnabled())` and use `{}` placeholders—never concatenate.
- **Prefer `DirectBuffer` / `UnsafeBuffer` over `byte[]` copies.** Access data in place; do not copy into intermediate arrays.
- **Use object pools or thread-local recycling** for any unavoidable allocations.

## Data Structures

- **Use Agrona collections** (`Int2ObjectHashMap`, `Long2ObjectHashMap`, `ObjectHashSet`) instead of `java.util` collections. They avoid boxing and are open-addressing, cache-friendly.
- **Size collections at init.** Pre-size maps/sets to expected capacity to avoid rehashing.
- **Avoid `HashMap`, `ArrayList`, `LinkedList`** on the hot path. These allocate `Entry`/`Node` objects per operation.
- **Prefer arrays or flat structs** over pointer-chasing object graphs.

## Threading & Concurrency

- **Single-threaded hot path.** Each pipeline stage should own its thread (or virtual thread) and never share mutable state. Communicate via Aeron channels, not locks or concurrent collections.
- **No locks, `synchronized`, or `CAS` loops** on the critical path. If coordination is needed, use single-writer patterns or Aeron publications.
- **Busy-spin or `IdleStrategy`** for polling loops—never `Thread.sleep()` or `Object.wait()` on latency-sensitive threads.
- **Pin hot-path threads to cores** via Agrona `ThreadFactory` or OS-level affinity where supported.

## I/O & Networking

- **Non-allocating I/O.** Reuse `DatagramPacket` and buffer arrays across receives. The current `UdpFeedReceiver` already follows this pattern.
- **Batch decode when possible.** If multiple messages arrive in one datagram, decode them in a loop over the buffer without re-wrapping the header each time.
- **Use Aeron for inter-process messaging.** It provides zero-copy, wait-free publication and subscription.

## SBE Codec Usage

- **Wrap, don't copy.** Decoders are flyweights over the underlying buffer—read fields directly; do not extract into POJOs on the hot path.
- **Encode in place.** Wrap the encoder on an outbound `UnsafeBuffer`, write fields, and publish the buffer directly.
- **Field access order matters.** Access SBE fields in schema-declared order for sequential memory access. Random-order access defeats cache prefetch.
- **Use the generated `ENCODED_LENGTH` constant** to pre-validate buffer space before encoding.

## Control Flow

- **No exceptions for control flow.** Exceptions allocate stack traces. Validate inputs before processing; use return codes or sentinel values for error conditions.
- **Avoid polymorphic dispatch on the hot path.** Virtual method calls through interfaces defeat JIT inlining. Prefer concrete types, `switch` on enum ordinals, or manually inlined logic.
- **Branch prediction: common case first.** Structure `if/else` so the fast/expected path is the `if` branch.

## Logging & Observability

- **Guard all logging with level checks.** Even `log.debug("...")` evaluates arguments before the framework filters it.
- **Never log on every message.** Use sampling (`if (seq % 10_000 == 0)`) or rate-limiting for hot-path diagnostics.
- **Micrometer counters/timers are fine** but record via pre-looked-up `Timer`/`Counter` references, not `registry.timer("name")` per call.

## Benchmarking

- **Use JMH** for micro-benchmarks of hot-path methods.
- **Measure at the 99.9th percentile**, not averages. Latency distributions matter more than throughput.
- **Profile with async-profiler or JFR** to find hidden allocations and lock contention.