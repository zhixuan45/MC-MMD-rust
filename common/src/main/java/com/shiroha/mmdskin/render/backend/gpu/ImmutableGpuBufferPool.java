package com.shiroha.mmdskin.render.backend.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Render-thread leases for immutable content, scoped to the GL context and buffer semantics. */
public final class ImmutableGpuBufferPool {
    private static final long BUDGET = Math.min(8192, Math.max(0, Long.getLong("mmdskin.staticBufferBudgetMiB", 256))) * 1024L * 1024L;
    private static final Map<Key, List<Entry>> ENTRIES = new HashMap<>();
    private static long bytes;
    private record Key(Object context, String kind, int length, String digest) {}
    private static final class Entry {
        final Key key;
        final ByteBuffer data;
        final int buffer;
        final boolean pooled;
        final LinkedHashSet<Lease> leases = new LinkedHashSet<>();
        Entry(Key key, ByteBuffer data, int buffer, boolean pooled) {
            this.key = key; this.data = data; this.buffer = buffer; this.pooled = pooled;
        }
    }

    private ImmutableGpuBufferPool() {}

    public static Lease acquire(String kind, ByteBuffer input) {
        ByteBuffer source = input.duplicate();
        Key key = new Key(GL.getCapabilities(), kind, source.remaining(), digest(source));
        List<Entry> bucket = ENTRIES.get(key);
        if (bucket != null && !"false".equalsIgnoreCase(System.getProperty("mmdskin.shareStaticBuffers"))) for (Entry entry : bucket) {
            if (entry.data.equals(source)) return new Lease(entry);
        }
        ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining()).order(ByteOrder.nativeOrder());
        copy.put(source).flip();
        int previous = GL46C.glGetInteger(GL46C.GL_COPY_WRITE_BUFFER_BINDING);
        int buffer = GL46C.glGenBuffers();
        try {
            GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, buffer);
            GL46C.glBufferData(GL46C.GL_COPY_WRITE_BUFFER, copy, GL46C.GL_STATIC_DRAW);
            boolean pooled = !"false".equalsIgnoreCase(System.getProperty("mmdskin.shareStaticBuffers"))
                    && bytes + copy.remaining() <= BUDGET;
            Entry entry = new Entry(key, copy.asReadOnlyBuffer().order(ByteOrder.nativeOrder()), buffer, pooled);
            if (pooled) {
                ENTRIES.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
                bytes += copy.remaining();
            }
            return new Lease(entry);
        } catch (RuntimeException | Error e) {
            GL46C.glDeleteBuffers(buffer);
            throw e;
        } finally {
            GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, previous);
        }
    }

    private static String digest(ByteBuffer data) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(data.duplicate());
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static final class Lease implements AutoCloseable {
        private Entry entry;
        private Lease(Entry entry) { this.entry = entry; entry.leases.add(this); }
        public int buffer() { return entry.buffer; }
        public ByteBuffer data() { return entry.data.duplicate().order(ByteOrder.nativeOrder()); }
        /** Charge shared memory exactly once across live instances, transferring ownership on close. */
        public long accountedBytes() {
            return entry != null && entry.leases.iterator().next() == this ? entry.data.remaining() : 0;
        }
        @Override public void close() {
            if (entry == null) return;
            Entry old = entry;
            if (GL.getCapabilities() != old.key.context()) throw new IllegalStateException("Wrong GL context for buffer release");
            old.leases.remove(this); entry = null;
            if (!old.leases.isEmpty()) return;
            if (old.pooled) {
                List<Entry> bucket = ENTRIES.get(old.key);
                bucket.remove(old);
                if (bucket.isEmpty()) ENTRIES.remove(old.key);
                bytes -= old.data.remaining();
            }
            // GL retains storage for commands already in flight.
            GL46C.glDeleteBuffers(old.buffer);
        }
    }
}
