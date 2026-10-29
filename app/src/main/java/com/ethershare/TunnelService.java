package com.ethershare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cable connection. A VPN catches every packet the Thor's apps send and passes it, as is,
 * down the USB cable through ADB to EtherShare on the PC, which sends it out over the PC's
 * own connection and passes the replies back. Nothing here needs root.
 *
 * The PC's program runs "adb reverse localabstract:ethershare tcp:PORT", so connecting to the
 * abstract socket "ethershare" here reaches it. ADB moves each connection stop-and-wait, so
 * one alone is slow: EtherShare opens STREAMS of them and deals the packets across them,
 * numbered so the far side can put them back in order. See desktop/relay.go for the PC's
 * side and the exact format.
 */
public class TunnelService extends VpnService {
    static final String ACTION_START = "com.ethershare.START";
    static final String ACTION_STOP = "com.ethershare.STOP";
    static final String EXTRA_PC = "pc";

    static final int IDLE = 0, CONNECTING = 1, CONNECTED = 2;

    private static final String SOCKET = "ethershare";
    private static final byte[] MAGIC = {'E', 'T', 'H', 'S'};
    private static final int VERSION = 2;
    /** ADB connections used at once. Older PC programs (version 1) take only one. */
    private static final int STREAMS = 8;
    /** Big packets mean far fewer of them, which is what decides the speed over ADB. */
    static final int MTU = 16384;
    private static final int SHELL_UID = 2000, ROOT_UID = 0;

    // What the screens show. Written by the service, read on the main thread.
    static volatile int state = IDLE;
    static volatile String pcName;
    /** Why the last connection ended or failed; null after the user disconnected. */
    static volatile String lastError;
    static volatile long connectedAt;
    static final AtomicLong bytesDown = new AtomicLong(), bytesUp = new AtomicLong();
    /** Download and upload speed for each of the last HISTORY seconds, in bytes/s, oldest first. */
    static final int HISTORY = 120;
    static final float[] downHistory = new float[HISTORY], upHistory = new float[HISTORY];
    static volatile float downNow, upNow, downPeak;
    /** Bytes moved since this connection started. */
    static volatile long sessionBytes;

    interface Listener { void onTunnelChanged(); }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void addListener(Listener l) { listeners.add(l); }

    static void removeListener(Listener l) { listeners.remove(l); }

    private static void changed() {
        main.post(new Runnable() {
            @Override public void run() {
                for (Listener l : listeners) l.onTunnelChanged();
            }
        });
    }

    /** Starts the cable connection. The VPN permission must already be granted. */
    static void start(Context context, String pc) {
        Intent i = new Intent(context, TunnelService.class).setAction(ACTION_START);
        if (pc != null) i.putExtra(EXTRA_PC, pc);
        context.startForegroundService(i);
    }

    static void stop(Context context) {
        if (state == IDLE) return;
        context.startService(new Intent(context, TunnelService.class).setAction(ACTION_STOP));
    }

    private volatile LocalSocket[] sockets = new LocalSocket[0];
    private ParcelFileDescriptor tun;
    private Thread worker;
    private volatile boolean stopping;
    private long sessionStartBytes;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            disconnect(null);
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification());
        if (state != IDLE) return START_NOT_STICKY; // already connecting or connected
        final String hint = intent != null ? intent.getStringExtra(EXTRA_PC) : null;
        state = CONNECTING;
        lastError = null;
        stopping = false;
        changed();
        worker = new Thread(new Runnable() {
            @Override public void run() { connect(hint); }
        }, "EtherShare tunnel");
        worker.start();
        return START_NOT_STICKY;
    }

    /** One ADB connection to the PC, after the hello and welcome. */
    private static final class Stream {
        final LocalSocket socket;
        final DataInputStream in;
        final OutputStream out;
        String pcName;

        Stream(LocalSocket socket) throws IOException {
            this.socket = socket;
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 256 * 1024));
            out = socket.getOutputStream();
        }
    }

    private void connect(String hint) {
        try {
            long session = new SecureRandom().nextLong();
            int version = VERSION;
            Stream first;
            try {
                first = openStream(VERSION, session, 0, STREAMS);
            } catch (IOException e) {
                // A PC program from before 1.1 hangs up on version 2: talk to it the old way.
                // (If the PC isn't there at all, this fails the same way and says so.)
                if (stopping || e instanceof NotAdbException) throw e;
                version = 1;
                first = openStream(1, 0, 0, 1);
            }
            Stream[] streams = new Stream[version == 1 ? 1 : STREAMS];
            streams[0] = first;
            for (int i = 1; i < streams.length; i++) streams[i] = openStream(VERSION, session, i, STREAMS);
            pcName = first.pcName.length() > 0 ? first.pcName : (hint != null ? hint : "your PC");

            tun = buildVpn().establish();
            if (tun == null) throw new IOException("Android hasn't allowed EtherShare's VPN yet. Open EtherShare and tap Connect");
            if (stopping) throw new IOException("stopped");

            connectedAt = System.currentTimeMillis();
            sessionStartBytes = bytesDown.get() + bytesUp.get();
            sessionBytes = 0;
            downPeak = 0;
            state = CONNECTED;
            Haptics.play(this, Haptics.CONNECTED);
            changed();
            main.post(ticker);
            registerReceiver(usbState, new IntentFilter("android.hardware.usb.action.USB_STATE"));

            startPumps(streams, version >= 2);
        } catch (IOException e) {
            if (!stopping) {
                String msg = e.getMessage();
                if (msg == null || msg.contains("Connection refused") || msg.contains("No such file")) {
                    msg = "Your PC isn't answering. Open EtherShare on your PC and plug in the cable";
                }
                disconnect(msg);
            }
        } catch (RuntimeException e) {
            if (!stopping) disconnect("Couldn't start the VPN: " + e.getMessage());
        }
    }

    private static final class NotAdbException extends IOException {
        NotAdbException() { super("something other than the PC answered; unplug and plug in again"); }
    }

    /** Connects one stream and trades hello and welcome; version 1 sends no session. */
    private Stream openStream(int version, long session, int index, int count) throws IOException {
        LocalSocket s = new LocalSocket();
        LocalSocket[] now = Arrays.copyOf(sockets, sockets.length + 1);
        now[now.length - 1] = s;
        sockets = now; // so disconnect() closes it
        if (stopping) throw new IOException("stopped");
        s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
        // Only adb may be on the other end; any app could open a socket with this name.
        Credentials peer = s.getPeerCredentials();
        if (peer == null || (peer.getUid() != SHELL_UID && peer.getUid() != ROOT_UID)) {
            throw new NotAdbException();
        }
        s.setSoTimeout(10000);
        Stream st = new Stream(s);
        ByteBuffer hello = ByteBuffer.allocate(15).put(MAGIC).put((byte) version);
        if (version >= 2) hello.putLong(session).put((byte) index).put((byte) count);
        st.out.write(hello.array(), 0, hello.position());
        st.out.flush();
        byte[] head = new byte[6];
        st.in.readFully(head);
        for (int i = 0; i < 4; i++) {
            if (head[i] != MAGIC[i]) throw new IOException("the PC didn't answer like EtherShare");
        }
        if (head[4] != version) throw new IOException("EtherShare on the PC is a different version; update both");
        byte[] name = new byte[head[5] & 0xff];
        st.in.readFully(name);
        st.pcName = new String(name, "UTF-8");
        s.setSoTimeout(0);
        return st;
    }

    /** Runs the packets both ways until a stream or the VPN closes, then disconnects. */
    private void startPumps(final Stream[] streams, final boolean numbered) {
        final FileOutputStream vpnOut = new FileOutputStream(tun.getFileDescriptor());
        final Reorder down = new Reorder(new Reorder.Sink() {
            @Override public void write(byte[] b, int off, int len) throws IOException { vpnOut.write(b, off, len); }
        });
        for (int i = 1; i < streams.length; i++) {
            final Stream st = streams[i];
            new Thread(new Runnable() {
                @Override public void run() { pumpDown(st, numbered, down); }
            }, "EtherShare down " + i).start();
        }
        final ArrayBlockingQueue<byte[]>[] queues = queues(numbered ? streams.length : 0);
        for (int i = 0; i < queues.length; i++) {
            final Stream st = streams[i];
            final ArrayBlockingQueue<byte[]> q = queues[i];
            new Thread(new Runnable() {
                @Override public void run() { sendLoop(st, q); }
            }, "EtherShare up " + i).start();
        }
        new Thread(new Runnable() {
            @Override public void run() { pumpUp(streams[0], queues); }
        }, "EtherShare VPN").start();
        pumpDown(streams[0], numbered, down);
    }

    @SuppressWarnings("unchecked")
    private static ArrayBlockingQueue<byte[]>[] queues(int n) {
        ArrayBlockingQueue<byte[]>[] q = new ArrayBlockingQueue[n];
        for (int i = 0; i < n; i++) q[i] = new ArrayBlockingQueue<>(256);
        return q;
    }

    /**
     * Thor -> PC: each packet from the VPN. Version 1 (no queues) writes it straight to the one
     * stream as length + packet; otherwise it gets a number and goes to the streams in turn.
     */
    private void pumpUp(Stream only, ArrayBlockingQueue<byte[]>[] queues) {
        byte[] buf = new byte[MTU + 6];
        int head = queues.length > 0 ? 6 : 2;
        int seq = 0, next = 0;
        try {
            FileInputStream in = new FileInputStream(tun.getFileDescriptor());
            while (!stopping) {
                int n = in.read(buf, head, MTU);
                if (n <= 0) continue;
                buf[0] = (byte) (n >> 8);
                buf[1] = (byte) n;
                bytesUp.addAndGet(n);
                if (queues.length == 0) {
                    only.out.write(buf, 0, n + 2);
                    continue;
                }
                buf[2] = (byte) (seq >> 24);
                buf[3] = (byte) (seq >> 16);
                buf[4] = (byte) (seq >> 8);
                buf[5] = (byte) seq;
                seq++;
                byte[] frame = Arrays.copyOf(buf, n + 6);
                // In turn, skipping a stream that's backed up while another has room.
                boolean sent = false;
                for (int k = 0; k < queues.length && !sent; k++) {
                    if (queues[(next + k) % queues.length].offer(frame)) {
                        next = (next + k + 1) % queues.length;
                        sent = true;
                    }
                }
                if (!sent) {
                    while (!stopping && !queues[next].offer(frame, 500, TimeUnit.MILLISECONDS)) { }
                    next = (next + 1) % queues.length;
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            // The socket or VPN closed.
        }
        if (!stopping) disconnect("The PC connection ended. Is the cable plugged in and EtherShare open on the PC?");
    }

    /** Writes one stream's frames, flushing whenever its queue runs dry. */
    private void sendLoop(Stream st, ArrayBlockingQueue<byte[]> q) {
        try {
            BufferedOutputStream out = new BufferedOutputStream(st.out, 256 * 1024);
            while (!stopping) {
                byte[] f = q.poll(500, TimeUnit.MILLISECONDS);
                if (f == null) continue;
                out.write(f);
                if (q.isEmpty()) out.flush();
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            // The socket closed.
        }
        if (!stopping) disconnect("The PC connection ended. Is the cable plugged in and EtherShare open on the PC?");
    }

    /** PC -> Thor: one packet per write into the VPN, in the PC's order. */
    private void pumpDown(Stream st, boolean numbered, Reorder down) {
        byte[] buf = new byte[65536];
        try {
            FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
            while (!stopping) {
                int n = st.in.readUnsignedShort();
                if (n == 0) continue;
                int seq = numbered ? st.in.readInt() : 0;
                st.in.readFully(buf, 0, n);
                bytesDown.addAndGet(n);
                if (numbered) down.deliver(seq, buf, n);
                else out.write(buf, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            // Unplugged, or the PC program closed.
        }
        if (!stopping) disconnect("The PC connection ended. Is the cable plugged in and EtherShare open on the PC?");
    }

    private Builder buildVpn() {
        Prefs prefs = new Prefs(this);
        Builder b = new Builder()
                .setSession("EtherShare")
                .setMtu(MTU)
                .addAddress("10.88.0.2", 32)
                .addRoute("0.0.0.0", 0)
                // IPv6 too, so nothing slips out over Wi-Fi. When the PC has no IPv6, the
                // relay refuses it at once and apps fall back to IPv4.
                .addAddress("fd00:0:0:88::2", 128)
                .addRoute("::", 0)
                .setBlocking(true)
                .setMetered(false);
        for (String dns : Prefs.DNS_SERVERS[prefs.dnsIndex()]) b.addDnsServer(dns);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, LauncherActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        b.setConfigureIntent(open);
        return b;
    }

    /** Ends the connection. reason = null when the user chose to. */
    private synchronized void disconnect(String reason) {
        if (stopping && state == IDLE) return;
        boolean wasConnected = state == CONNECTED;
        stopping = true;
        main.removeCallbacks(ticker);
        try {
            unregisterReceiver(usbState);
        } catch (IllegalArgumentException ignored) {
        }
        for (LocalSocket so : sockets) {
            try {
                so.close();
            } catch (IOException ignored) {
            }
        }
        try {
            if (tun != null) tun.close();
        } catch (IOException ignored) {
        }
        sockets = new LocalSocket[0];
        tun = null;
        lastError = reason;
        state = IDLE;
        downNow = upNow = 0;
        if (wasConnected) Haptics.play(this, Haptics.DISCONNECTED);
        changed();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Unplugging the cable: end at once rather than waiting for the socket to notice. */
    private final BroadcastReceiver usbState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!intent.getBooleanExtra("connected", true)) {
                main.post(new Runnable() {
                    @Override public void run() { disconnect("The cable was unplugged"); }
                });
            }
        }
    };

    @Override
    public void onRevoke() {
        // Another VPN took over, or the user turned EtherShare off in Android's settings.
        disconnect("Android turned the VPN off (another VPN may have started)");
    }

    @Override
    public void onDestroy() {
        if (state != IDLE) disconnect(null);
        super.onDestroy();
    }

    // ---- Speeds, once a second ------------------------------------------------------------

    private long lastDown, lastUp, lastTick;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            long d = bytesDown.get(), u = bytesUp.get();
            if (lastTick != 0) {
                float secs = Math.max(0.2f, (now - lastTick) / 1000f);
                System.arraycopy(downHistory, 1, downHistory, 0, HISTORY - 1);
                System.arraycopy(upHistory, 1, upHistory, 0, HISTORY - 1);
                downHistory[HISTORY - 1] = (d - lastDown) / secs;
                upHistory[HISTORY - 1] = (u - lastUp) / secs;
                // Traffic comes in bursts, so one second on its own jumps between 0 and the
                // top speed: the numbers show the last few seconds' average instead.
                downNow = average(downHistory);
                upNow = average(upHistory);
                downPeak = Math.max(downPeak, downHistory[HISTORY - 1]);
            }
            sessionBytes = d + u - sessionStartBytes;
            lastDown = d;
            lastUp = u;
            lastTick = now;
            if (new Prefs(TunnelService.this).notifySpeed()) {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
            }
            changed();
            main.postDelayed(this, 1000);
        }
    };

    private static final int SMOOTH = 3;

    private static float average(float[] history) {
        float sum = 0;
        for (int i = HISTORY - SMOOTH; i < HISTORY; i++) sum += history[i];
        return sum / SMOOTH;
    }

    // ---- Notification ---------------------------------------------------------------------

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL = "tunnel";

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Cable connection", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Shows while the Thor is using your PC's internet");
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        String title, text;
        if (state == CONNECTED) {
            title = "Using " + pcName + "'s internet";
            text = new Prefs(this).notifySpeed()
                    ? "↓ " + Format.mbps(downNow) + " Mbps   ↑ " + Format.mbps(upNow) + " Mbps   · " + Format.size(sessionBytes)
                    : "Over the USB cable";
        } else {
            title = "Connecting to your PC…";
            text = "Over the USB cable";
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, LauncherActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TunnelService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Disconnect", stop).build())
                .build();
    }
}
