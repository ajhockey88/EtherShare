package com.ethershare;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Puts the PC's packets back in order. The PC deals its packets across several streams and
 * numbers them, so one stream's packet can arrive before an earlier one on another stream;
 * this holds it until its turn.
 */
final class Reorder {
    interface Sink {
        void write(byte[] b, int off, int len) throws IOException;
    }

    private final Sink sink;
    private final HashMap<Integer, byte[]> pending = new HashMap<>();
    private int next;

    Reorder(Sink sink) { this.sink = sink; }

    /** pkt may be reused by the caller afterwards: a packet that has to wait is copied. */
    synchronized void deliver(int seq, byte[] pkt, int len) throws IOException {
        int ahead = seq - next;
        if (ahead < 0) return; // already delivered
        if (ahead > 0) {
            if (pending.size() > 65536) throw new IOException("the PC's streams fell out of step");
            pending.put(seq, Arrays.copyOf(pkt, len));
            return;
        }
        sink.write(pkt, 0, len);
        next++;
        byte[] p;
        while ((p = pending.remove(next)) != null) {
            sink.write(p, 0, p.length);
            next++;
        }
    }
}
