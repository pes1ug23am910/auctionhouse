package io.auctionhouse.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NonblockingSseWriterTest {
    @Test void neverWritesWhenNotReadyAndResumesAfterCallback() {
        var output=new Output(); output.ready=false;
        var stops=new ArrayList<String>();
        var writer=new NonblockingSseWriter(output,()->true,stops::add,4,4096);
        assertTrue(writer.offer("hello",true));
        assertEquals(5,writer.bufferedBytes());
        assertEquals(0,output.bytes.size());
        output.ready=true; writer.onWritePossible();
        assertEquals("hello",output.text());
        assertEquals(0,writer.bufferedBytes());
        assertFalse(writer.pending());
        assertTrue(stops.isEmpty());
    }
    @Test void byteAndFrameBoundsCloseAndReleaseQueuedMemory() {
        for (boolean bytes:new boolean[]{false,true}) {
            var output=new Output(); output.ready=false;
            var stops=new ArrayList<String>();
            var writer=new NonblockingSseWriter(output,()->true,stops::add,bytes?10:1,bytes?4:4096);
            assertTrue(writer.offer("one",true));
            assertFalse(writer.offer("two",true));
            assertEquals(java.util.List.of("overflow"),stops);
            assertEquals(0,writer.bufferedBytes());
            assertEquals(0,writer.bufferedFrames());
            output.ready=true; writer.onWritePossible();
            assertEquals(0,output.bytes.size());
        }
    }
    @Test void revocationBetweenQueuedFramesPreventsTheNextPayload() {
        var output=new Output(); output.ready=false;
        var live=new AtomicBoolean(true);
        var checks=new AtomicInteger();
        var stops=new ArrayList<String>();
        var writer=new NonblockingSseWriter(output,()->{checks.incrementAndGet();return live.get();},stops::add,4,4096);
        writer.offer("first",true); writer.offer("must-not-leak",true);
        output.afterWrite=()->live.set(false);
        output.ready=true; writer.onWritePossible();
        assertEquals("first",output.text());
        assertEquals(2,checks.get());
        assertEquals(java.util.List.of("authorization"),stops);
        assertEquals(0,writer.bufferedBytes());
    }
    @Test void writesAreChunkedAndReadinessIsCheckedBetweenChunks() {
        var output=new Output();
        var stops=new ArrayList<String>();
        var writer=new NonblockingSseWriter(output,()->true,stops::add,4,8192);
        output.afterWrite=()->output.ready=false;
        writer.offer("x".repeat(3000),true);
        assertEquals(1024,output.bytes.size());
        assertEquals(1976,writer.bufferedBytes());
        output.ready=true; writer.onWritePossible();
        assertEquals(2048,output.bytes.size());
        output.ready=true; writer.onWritePossible();
        assertEquals(3000,output.bytes.size());
        output.ready=true; writer.onWritePossible();
        assertFalse(writer.pending());
        assertTrue(stops.isEmpty());
    }
    @Test void stalledPendingOutputIsObservableWithoutBlockingAWriterThread() {
        var output=new Output(); output.ready=false;
        var writer=new NonblockingSseWriter(output,()->true,ignored->{},4,4096);
        writer.offer("queued",true);
        assertTrue(writer.stalled(System.nanoTime()+1_000_000,1));
        writer.stop("stall");
        assertFalse(writer.pending());
    }
    @Test void finalFlushAwaitingNativeReadinessStillCountsAsStalledWork() {
        var output=new Output();
        var writer=new NonblockingSseWriter(output,()->true,ignored->{},4,4096);
        output.afterFlush=()->output.ready=false;
        writer.offer("last frame",true);
        assertEquals(0,writer.bufferedBytes());
        assertTrue(writer.pending());
        assertTrue(writer.stalled(System.nanoTime()+1_000_000,1));
        output.ready=true; writer.onWritePossible();
        assertFalse(writer.pending());
    }
    @Test void aClosedOrFailedWriterInvokesCleanupOnlyOnce() {
        var output=new Output();
        var stops=new ArrayList<String>();
        var writer=new NonblockingSseWriter(output,()->false,stops::add,4,4096);
        assertFalse(writer.offer("secret",true));
        writer.stop("again"); writer.onError(new RuntimeException("fixture"));
        assertEquals(java.util.List.of("authorization"),stops);
        assertEquals(0,output.bytes.size());
    }
    private static final class Output extends ServletOutputStream {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        boolean ready=true;
        Runnable afterWrite=()->{};
        Runnable afterFlush=()->{};
        @Override public boolean isReady() { return ready; }
        @Override public void setWriteListener(WriteListener listener) { }
        @Override public void write(int value) {
            if (!ready) fail("blocking write attempted");
            bytes.write(value);
        }
        @Override public void write(byte[] value,int offset,int count) {
            if (!ready) fail("blocking write attempted");
            assertTrue(count<=1024,"bounded output chunk");
            bytes.write(value,offset,count);
            afterWrite.run();
        }
        @Override public void flush() { if (!ready) fail("flush while not ready"); afterFlush.run(); }
        String text() { return bytes.toString(StandardCharsets.UTF_8); }
    }
}
