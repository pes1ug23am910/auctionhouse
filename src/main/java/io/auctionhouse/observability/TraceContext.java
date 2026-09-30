package io.auctionhouse.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;

/** Only W3C trace context crosses the durable outbox boundary; baggage is excluded. */
public final class TraceContext {
    private TraceContext() {}
    private static final W3CTraceContextPropagator W3C=W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String,String>> GETTER=new TextMapGetter<>() {
        public Iterable<String> keys(Map<String,String> carrier) {return carrier.keySet();}
        public String get(Map<String,String> carrier,String key) {return carrier==null?null:carrier.get(key);}
    };
    public record Headers(String traceparent,String tracestate) {}
    public static Tracer tracer() {return GlobalOpenTelemetry.getTracer("io.auctionhouse.outbox","1.0");}
    public static Headers capture() {
        var carrier=new HashMap<String,String>();
        inject(carrier,Map::put);
        return new Headers(carrier.get("traceparent"),carrier.get("tracestate"));
    }
    public static Context restore(String traceparent,String tracestate) {
        var carrier=new HashMap<String,String>();
        if(traceparent!=null) carrier.put("traceparent",traceparent);
        if(tracestate!=null) carrier.put("tracestate",tracestate);
        return W3C.extract(Context.root(),carrier,GETTER);
    }
    public static <T> void inject(T carrier,TextMapSetter<T> setter) {
        W3C.inject(Context.current(),carrier,setter);
    }
}
