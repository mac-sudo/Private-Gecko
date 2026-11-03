package ru.drtrip.maxprivategecko;

import java.io.Closeable;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns only this activity's temporary copies and cancellable document streams. */
final class TransferScope {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Closeable> streams = new HashSet<>();
    private final Set<File> paths = new HashSet<>();
    private volatile boolean cancelled;

    boolean isCancelled() { return cancelled; }
    void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled");
    }
    void execute(Runnable job) { executor.execute(job); }
    synchronized void own(File file) { paths.add(file); }
    private synchronized void add(Closeable stream) throws IOException {
        if (cancelled) { stream.close(); throw new IOException("Transfer cancelled"); }
        streams.add(stream);
    }
    private synchronized void remove(Closeable stream) { streams.remove(stream); }
    InputStream input(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Missing input stream");
        InputStream tracked = new FilterInputStream(stream) {
            @Override public int read(byte[] b, int o, int n) throws IOException { check(); return super.read(b, o, n); }
            @Override public int read() throws IOException { check(); return super.read(); }
            @Override public void close() throws IOException { try { super.close(); } finally { remove(this); } }
        };
        add(tracked); return tracked;
    }
    OutputStream output(OutputStream stream) throws IOException {
        if (stream == null) throw new IOException("Missing output stream");
        OutputStream tracked = new FilterOutputStream(stream) {
            @Override public void write(byte[] b, int o, int n) throws IOException { check(); out.write(b, o, n); }
            @Override public void write(int b) throws IOException { check(); out.write(b); }
            @Override public void close() throws IOException { try { super.close(); } finally { remove(this); } }
        };
        add(tracked); return tracked;
    }
    void cancel() {
        cancelled = true;
        executor.shutdownNow();
        final Closeable[] open;
        synchronized (this) { open = streams.toArray(new Closeable[0]); }
        // Provider close() may block; callers run this portion off the UI thread.
        for (Closeable stream : open) try { stream.close(); } catch (IOException ignored) { }
    }
    void markCancelled() { cancelled = true; executor.shutdownNow(); }
    boolean awaitStopped(long seconds) throws InterruptedException { return executor.awaitTermination(seconds, TimeUnit.SECONDS); }
    void purgeOwned(File cacheRoot) throws IOException {
        final File[] owned;
        synchronized (this) { owned = paths.toArray(new File[0]); }
        for (File file : owned) PrivateFiles.deleteWithin(cacheRoot, file);
    }
}
