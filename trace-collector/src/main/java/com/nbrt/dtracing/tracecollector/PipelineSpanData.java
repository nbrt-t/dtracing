package com.nbrt.dtracing.tracecollector;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

import java.util.List;

/**
 * Immutable {@link SpanData} implementation that carries exact trace/span IDs
 * and nanosecond timestamps from our SBE {@code TraceSpan} messages.
 * <p>
 * Feeds directly into the OTLP exporter without going through the SDK's
 * span lifecycle — no allocation beyond this object itself.
 */
record PipelineSpanData(
        SpanContext spanContext,
        SpanContext parentSpanContext,
        Resource resource,
        InstrumentationScopeInfo instrumentationScopeInfo,
        String name,
        long startEpochNanos,
        long endEpochNanos,
        Attributes attributes
) implements SpanData {

    static PipelineSpanData create(
            long traceId, long spanId, long parentSpanId,
            String name, long startEpochNanos, long endEpochNanos,
            Attributes attributes, Resource resource,
            InstrumentationScopeInfo scopeInfo) {

        String traceIdHex = String.format("%032x", traceId);
        String spanIdHex = String.format("%016x", spanId);

        SpanContext ctx = SpanContext.create(
                traceIdHex, spanIdHex, TraceFlags.getSampled(), TraceState.getDefault());

        SpanContext parentCtx;
        if (parentSpanId == 0) {
            parentCtx = SpanContext.getInvalid();
        } else {
            String parentSpanIdHex = String.format("%016x", parentSpanId);
            parentCtx = SpanContext.create(
                    traceIdHex, parentSpanIdHex, TraceFlags.getSampled(), TraceState.getDefault());
        }

        return new PipelineSpanData(
                ctx, parentCtx, resource, scopeInfo,
                name, startEpochNanos, endEpochNanos, attributes);
    }

    @Override public SpanContext getSpanContext()                    { return spanContext; }
    @Override public SpanContext getParentSpanContext()              { return parentSpanContext; }
    @Override public Resource getResource()                         { return resource; }
    @Override public InstrumentationScopeInfo getInstrumentationScopeInfo() { return instrumentationScopeInfo; }
    @Override public String getName()                               { return name; }
    @Override public SpanKind getKind()                             { return SpanKind.INTERNAL; }
    @Override public long getStartEpochNanos()                      { return startEpochNanos; }
    @Override public long getEndEpochNanos()                        { return endEpochNanos; }
    @Override public Attributes getAttributes()                     { return attributes; }
    @Override public List<EventData> getEvents()                    { return List.of(); }
    @Override public List<LinkData> getLinks()                      { return List.of(); }
    @Override public StatusData getStatus()                         { return StatusData.ok(); }
    @Override public boolean hasEnded()                             { return true; }
    @Override public int getTotalRecordedEvents()                   { return 0; }
    @Override public int getTotalRecordedLinks()                    { return 0; }
    @Override public int getTotalAttributeCount()                   { return attributes.size(); }

    @SuppressWarnings("deprecation")
    @Override
    public InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
        return InstrumentationLibraryInfo.create(
                instrumentationScopeInfo.getName(),
                instrumentationScopeInfo.getVersion());
    }
}