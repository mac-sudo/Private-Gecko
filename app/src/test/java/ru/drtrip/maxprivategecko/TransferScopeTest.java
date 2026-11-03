package ru.drtrip.maxprivategecko;

import org.junit.Test;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class TransferScopeTest {
    @Test public void cancelledScopeCannotWriteOrAcceptNewStreams() throws Exception {
        TransferScope scope=new TransferScope(); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        OutputStream output=scope.output(bytes); output.write(1); scope.cancel();
        try { output.write(2); fail(); } catch(IOException expected) { }
        try { scope.input(new ByteArrayInputStream(new byte[]{1})); fail(); } catch(IOException expected) { }
        assertArrayEquals(new byte[]{1},bytes.toByteArray());
    }
    @Test public void cancellationClosesAndUnblocksActiveRead() throws Exception {
        TransferScope scope=new TransferScope(); CountDownLatch reading=new CountDownLatch(1), closed=new CountDownLatch(1);
        AtomicBoolean streamClosed=new AtomicBoolean();
        InputStream provider=new InputStream(){
            public int read() throws IOException { reading.countDown(); try{closed.await();}catch(InterruptedException e){throw new IOException(e);} return -1; }
            public void close(){streamClosed.set(true);closed.countDown();}
        };
        InputStream input=scope.input(provider);
        scope.execute(()->{try(InputStream in=input){in.read();}catch(IOException ignored){}});
        assertTrue(reading.await(2,TimeUnit.SECONDS)); scope.cancel();
        assertTrue(scope.awaitStopped(2)); assertTrue(streamClosed.get());
    }
    @Test public void closedStreamsAreNotClosedTwiceOnCancellation() throws Exception {
        TransferScope scope=new TransferScope(); final int[] closes={0};
        InputStream input=scope.input(new ByteArrayInputStream(new byte[]{1}){public void close(){closes[0]++;}});
        input.close(); scope.cancel(); assertEquals(1,closes[0]);
    }
}
