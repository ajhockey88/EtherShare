package com.ethershare;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A quick speed test against Cloudflare's speed test servers (the ones speed.cloudflare.com
 * uses): ping, then several parallel downloads, then uploads, each for a few seconds. Only runs
 * when tapped. Results are kept separately for the cable and for Wi-Fi, to compare the two.
 */
final class SpeedTest {
    private static final String DOWN = "https://speed.cloudflare.com/__down?bytes=";
    private static final String UP = "https://speed.cloudflare.com/__up";
    private static final int STREAMS = 4;
    private static final long PHASE_MS = 7000;

    interface Listener {
        /** phase: "Ping", "Download" or "Upload"; mbps so far (ms for ping). */
        void onProgress(String phase, double value);

        void onDone(Result r);

        void onFailed(String why);
    }

    static final class Result {
        double pingMs, downMbps, upMbps;
        boolean cable;
        long at;

        void save(SharedPreferences sp) {
            String k = cable ? "test_cable_" : "test_wifi_";
            sp.edit().putFloat(k + "ping", (float) pingMs).putFloat(k + "down", (float) downMbps)
                    .putFloat(k + "up", (float) upMbps).putLong(k + "at", at).apply();
        }

        /** The last saved result for the cable or Wi-Fi, or null. */
        static Result load(SharedPreferences sp, boolean cable) {
            String k = cable ? "test_cable_" : "test_wifi_";
            if (sp.getLong(k + "at", 0) == 0) return null;
            Result r = new Result();
            r.cable = cable;
            r.pingMs = sp.getFloat(k + "ping", 0);
            r.downMbps = sp.getFloat(k + "down", 0);
            r.upMbps = sp.getFloat(k + "up", 0);
            r.at = sp.getLong(k + "at", 0);
            return r;
        }
    }

    private static volatile boolean running;

    static boolean isRunning() { return running; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    SpeedTest(Listener listener) { this.listener = listener; }

    void start() {
        if (running) return;
        running = true;
        final boolean cable = TunnelService.state == TunnelService.CONNECTED;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Result r = new Result();
                    r.cable = cable;
                    r.pingMs = ping();
                    r.downMbps = transfer(true);
                    r.upMbps = transfer(false);
                    r.at = System.currentTimeMillis();
                    main.post(new Runnable() {
                        @Override public void run() { listener.onDone(r); }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            listener.onFailed(e.getMessage() != null ? e.getMessage() : e.toString());
                        }
                    });
                } finally {
                    running = false;
                }
            }
        }, "EtherShare speed test").start();
    }

    /** The middle of five small requests' round trips, after one to set up the connection. */
    private double ping() throws IOException {
        double[] times = new double[5];
        fetch(DOWN + "0");
        for (int i = 0; i < times.length; i++) {
            long t = SystemClock.elapsedRealtimeNanos();
            fetch(DOWN + "0");
            times[i] = (SystemClock.elapsedRealtimeNanos() - t) / 1e6;
            progress("Ping", times[i]);
        }
        Arrays.sort(times);
        return times[times.length / 2];
    }

    private static void fetch(String url) throws IOException {
        HttpURLConnection c = open(url);
        InputStream in = c.getInputStream();
        byte[] b = new byte[4096];
        while (in.read(b) > 0) {
            // drain
        }
        in.close();
    }

    /** Several streams at once for PHASE_MS; the speed counts from the end of the first second. */
    private double transfer(final boolean download) throws Exception {
        final AtomicLong bytes = new AtomicLong();
        final long end = SystemClock.elapsedRealtime() + PHASE_MS;
        final Exception[] error = new Exception[1];
        Thread[] streams = new Thread[STREAMS];
        for (int i = 0; i < STREAMS; i++) {
            streams[i] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        while (SystemClock.elapsedRealtime() < end) {
                            if (download) downloadOnce(bytes, end);
                            else uploadOnce(bytes, end);
                        }
                    } catch (Exception e) {
                        error[0] = e;
                    }
                }
            });
            streams[i].start();
        }
        String phase = download ? "Download" : "Upload";
        long warm = SystemClock.elapsedRealtime() + 1000, from = 0, fromAt = 0;
        double mbps = 0;
        while (SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(250);
            long now = SystemClock.elapsedRealtime();
            if (fromAt == 0 && now >= warm) {
                from = bytes.get();
                fromAt = now;
            } else if (fromAt != 0) {
                mbps = (bytes.get() - from) * 8 / 1e6 / ((now - fromAt) / 1000.0);
                progress(phase, mbps);
            }
        }
        for (Thread t : streams) t.join(3000);
        if (mbps == 0 && error[0] != null) throw error[0];
        return mbps;
    }

    private static void downloadOnce(AtomicLong bytes, long end) throws IOException {
        HttpURLConnection c = open(DOWN + (100 * 1000 * 1000));
        InputStream in = c.getInputStream();
        byte[] b = new byte[64 * 1024];
        try {
            int n;
            while ((n = in.read(b)) > 0) {
                bytes.addAndGet(n);
                if (SystemClock.elapsedRealtime() >= end) break;
            }
        } finally {
            c.disconnect();
        }
    }

    private static void uploadOnce(AtomicLong bytes, long end) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(UP).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/octet-stream");
        int size = 25 * 1000 * 1000;
        c.setFixedLengthStreamingMode(size);
        byte[] b = new byte[64 * 1024];
        new Random().nextBytes(b);
        try {
            OutputStream out = c.getOutputStream();
            int sent = 0;
            while (sent < size) {
                int n = Math.min(b.length, size - sent);
                out.write(b, 0, n);
                sent += n;
                bytes.addAndGet(n);
                if (SystemClock.elapsedRealtime() >= end) break;
            }
            if (sent < size) return; // time's up: drop the rest
            out.close();
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "EtherShare");
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " from the speed test server");
        return c;
    }

    private void progress(final String phase, final double value) {
        main.post(new Runnable() {
            @Override public void run() { listener.onProgress(phase, value); }
        });
    }
}
