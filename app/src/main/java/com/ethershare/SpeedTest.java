package com.ethershare;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A quick speed test. It picks the nearest of several public speed test servers (the one that
 * answers a connection fastest), measures ping to it, then runs several parallel downloads from
 * it and uploads to Tele2's public server (speedtest.tele2.net), each for a few seconds. Only
 * runs when tapped. Results are kept separately for the cable and for Wi-Fi, to compare.
 *
 * It speaks HTTPS over IPv4 sockets itself: a far-away server or an IPv6 address that doesn't
 * answer used to stall Java's HTTP client for longer than the test, which read 0. Plain HTTP
 * (port 80) didn't get through on Anthony's PC at all, so it's HTTPS only.
 */
final class SpeedTest {
    /** host/path of a large file to download; Linode's are for exactly this. */
    private static final String[] DOWN = {
            "speedtest.tele2.net/100MB.zip",
            "speedtest.newark.linode.com/100MB-newark.bin",
            "speedtest.atlanta.linode.com/100MB-atlanta.bin",
            "speedtest.dallas.linode.com/100MB-dallas.bin",
            "speedtest.fremont.linode.com/100MB-fremont.bin",
            "speedtest.toronto1.linode.com/100MB-toronto1.bin",
            "speedtest.london.linode.com/100MB-london.bin",
            "speedtest.frankfurt.linode.com/100MB-frankfurt.bin",
            "speedtest.singapore.linode.com/100MB-singapore.bin",
            "speedtest.tokyo2.linode.com/100MB-tokyo2.bin",
            "speedtest.syd1.linode.com/100MB-syd1.bin",
    };
    private static final String UP = "speedtest.tele2.net/upload.php";
    private static final int PORT = 443;
    private static final int STREAMS = 6;
    /** Each phase measures for this long, starting a second after the first bytes move. */
    private static final long MEASURE_MS = 6000;
    /** A phase gives up when nothing has moved by then. */
    private static final long FIRST_BYTE_MS = 8000;

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
                    Target down = nearest();
                    r.pingMs = ping(down);
                    r.downMbps = transfer(down, true);
                    r.upMbps = transfer(new Target(UP, ipv4(host(UP))), false);
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

    /** A server: its host/path, and the IPv4 address to reach it at. */
    private static final class Target {
        final String url;
        final InetAddress ip;

        Target(String url, InetAddress ip) {
            this.url = url;
            this.ip = ip;
        }
    }

    private static String host(String url) { return url.substring(0, url.indexOf('/')); }

    private static String path(String url) { return url.substring(url.indexOf('/')); }

    private static InetAddress ipv4(String host) throws IOException {
        for (InetAddress a : InetAddress.getAllByName(host)) {
            if (a instanceof Inet4Address) return a;
        }
        throw new IOException(host + " has no IPv4 address");
    }

    /** The download server that answers fastest, tried all at once. */
    private Target nearest() throws Exception {
        progress("Ping", 0);
        final Target[] best = new Target[1];
        final long[] bestNs = {Long.MAX_VALUE};
        final String[] why = new String[1];
        Thread[] probes = new Thread[DOWN.length];
        for (int i = 0; i < DOWN.length; i++) {
            final String url = DOWN[i];
            probes[i] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Target t = new Target(url, ipv4(host(url)));
                        // A full HTTPS set-up, so a server without working HTTPS drops out here.
                        long start = SystemClock.elapsedRealtimeNanos();
                        open(t).close();
                        long ns = SystemClock.elapsedRealtimeNanos() - start;
                        synchronized (best) {
                            if (ns < bestNs[0]) {
                                bestNs[0] = ns;
                                best[0] = t;
                            }
                        }
                    } catch (IOException e) {
                        // Gone or unreachable: one of the others will do.
                        synchronized (best) {
                            if (why[0] == null) why[0] = host(url) + ": " + e.getMessage();
                        }
                    }
                }
            });
            probes[i].start();
        }
        for (Thread t : probes) t.join(6000);
        synchronized (best) {
            if (best[0] == null) throw new IOException("couldn't reach any speed test server (" + why[0] + ")");
            return best[0];
        }
    }

    /** Time to open a TCP connection: one round trip. */
    private static long connectNs(InetAddress ip) throws IOException {
        Socket so = new Socket();
        long t = SystemClock.elapsedRealtimeNanos();
        try {
            so.connect(new InetSocketAddress(ip, PORT), 3000);
            return SystemClock.elapsedRealtimeNanos() - t;
        } finally {
            so.close();
        }
    }

    /** The middle of five connection set-ups to the server, in ms. */
    private double ping(Target t) throws IOException {
        double[] times = new double[5];
        for (int i = 0; i < times.length; i++) {
            times[i] = connectNs(t.ip) / 1e6;
            progress("Ping", times[i]);
        }
        Arrays.sort(times);
        return times[times.length / 2];
    }

    /** Several streams at once; the speed counts from a second after the first bytes. */
    private double transfer(final Target target, final boolean download) throws Exception {
        final AtomicLong bytes = new AtomicLong();
        final long begin = SystemClock.elapsedRealtime();
        final long[] end = {begin + FIRST_BYTE_MS + 1000 + MEASURE_MS};
        final Exception[] error = new Exception[1];
        final String phase = download ? "Download" : "Upload";
        Thread[] streams = new Thread[STREAMS];
        for (int i = 0; i < STREAMS; i++) {
            streams[i] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        while (SystemClock.elapsedRealtime() < end[0]) {
                            if (download) downloadOnce(target, bytes, end);
                            else uploadOnce(target, bytes, end);
                        }
                    } catch (Exception e) {
                        error[0] = e;
                    }
                }
            });
            streams[i].start();
        }
        long from = 0, fromAt = 0, firstAt = 0;
        double mbps = 0;
        try {
            while (true) {
                SystemClock.sleep(250);
                long now = SystemClock.elapsedRealtime();
                long b = bytes.get();
                if (firstAt == 0) {
                    if (b > 0) {
                        firstAt = now;
                        end[0] = now + 1000 + MEASURE_MS;
                    } else if (now - begin > FIRST_BYTE_MS) {
                        throw error[0] != null ? error[0]
                                : new IOException("the " + phase.toLowerCase() + " didn't start; the speed test server isn't answering");
                    }
                } else if (fromAt == 0 && now >= firstAt + 1000) {
                    from = b;
                    fromAt = now;
                } else if (fromAt != 0) {
                    mbps = (b - from) * 8 / 1e6 / ((now - fromAt) / 1000.0);
                    progress(phase, mbps);
                }
                if (now >= end[0]) break;
            }
        } finally {
            end[0] = 0; // stops the streams
            for (Thread t : streams) t.join(3000);
        }
        return mbps;
    }

    /** A TLS connection to the server, checked against its name like a browser does. */
    private static Socket open(Target t) throws IOException {
        Socket so = new Socket();
        so.connect(new InetSocketAddress(t.ip, PORT), 4000);
        so.setSoTimeout(5000);
        String host = host(t.url);
        SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(so, host, PORT, true);
        SSLParameters p = tls.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS"); // checks the certificate is for host
        tls.setSSLParameters(p);
        tls.startHandshake();
        return tls;
    }

    private static void downloadOnce(Target t, AtomicLong bytes, long[] end) throws IOException {
        Socket so = open(t);
        try {
            OutputStream out = so.getOutputStream();
            out.write(("GET " + path(t.url) + " HTTP/1.1\r\nHost: " + host(t.url)
                    + "\r\nUser-Agent: EtherShare\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            out.flush();
            InputStream in = so.getInputStream();
            readHeaders(in);
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                bytes.addAndGet(n);
                if (SystemClock.elapsedRealtime() >= end[0]) break;
            }
        } finally {
            so.close();
        }
    }

    private static void uploadOnce(Target t, AtomicLong bytes, long[] end) throws IOException {
        int size = 25 * 1000 * 1000;
        Socket so = open(t);
        try {
            OutputStream out = so.getOutputStream();
            out.write(("POST " + path(t.url) + " HTTP/1.1\r\nHost: " + host(t.url)
                    + "\r\nUser-Agent: EtherShare\r\nContent-Type: application/octet-stream\r\nContent-Length: " + size
                    + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            byte[] b = new byte[64 * 1024];
            new Random().nextBytes(b);
            int sent = 0;
            while (sent < size && SystemClock.elapsedRealtime() < end[0]) {
                int n = Math.min(b.length, size - sent);
                out.write(b, 0, n);
                sent += n;
                bytes.addAndGet(n);
            }
            if (sent == size) {
                out.flush();
                readHeaders(so.getInputStream());
            }
        } finally {
            so.close();
        }
    }

    /** Reads the response headers, and fails unless the status is 200. */
    private static void readHeaders(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            head.append((char) c);
            int n = head.length();
            if (n >= 4 && head.charAt(n - 1) == '\n' && head.charAt(n - 2) == '\r'
                    && head.charAt(n - 3) == '\n' && head.charAt(n - 4) == '\r') break;
            if (n > 16384) break;
        }
        String status = head.length() > 0 ? head.toString().split("\r\n", 2)[0] : "no answer";
        if (!status.contains(" 200")) throw new IOException("the speed test server said " + status);
    }

    private void progress(final String phase, final double value) {
        main.post(new Runnable() {
            @Override public void run() { listener.onProgress(phase, value); }
        });
    }
}
