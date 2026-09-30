package io.auctionhouse.observability;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceContextTest {
    @Test void onlyTraceContextRoundTripsAcrossDurableBoundary() {
        var original=SpanContext.create("0123456789abcdef0123456789abcdef","0123456789abcdef",
                TraceFlags.getSampled(),TraceState.builder().put("vendor","value").build());
        try(var scope=Span.wrap(original).makeCurrent()) {
            var saved=TraceContext.capture();
            assertEquals("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",saved.traceparent());
            assertEquals("vendor=value",saved.tracestate());
            var restored=Span.fromContext(TraceContext.restore(saved.traceparent(),saved.tracestate())).getSpanContext();
            assertTrue(restored.isRemote());
            assertEquals(original.getTraceId(),restored.getTraceId());
            assertEquals(original.getSpanId(),restored.getSpanId());
            var carrier=new HashMap<String,String>();TraceContext.inject(carrier,HashMap::put);
            assertEquals(java.util.Set.of("traceparent","tracestate"),carrier.keySet());
        }
    }
    @Test void missingOrMalformedContextStartsANewTraceInsteadOfInventingIdentity() {
        assertFalse(Span.fromContext(TraceContext.restore(null,null)).getSpanContext().isValid());
        assertFalse(Span.fromContext(TraceContext.restore("invalid","vendor=value")).getSpanContext().isValid());
        try(var scope=Context.root().makeCurrent()) {assertNull(TraceContext.capture().traceparent());}
    }
}
