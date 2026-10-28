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
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cable connection. A VPN catches every packet the Thor's apps send and passes it, as is,
 * down the USB cable through ADB to EtherShare on the PC, which sends it out over the PC's
 * own connection and passes the replies back. Nothing here needs root.
 *
 * The PC's program runs "adb reverse localabstract:ethershare tcp:PORT", so connecting to the
 * abstract socket "ethershare" here reaches it. Each packet travels as a 2-byte length and
 * the packet; see desktop/relay.go for the PC's side.
 */
public class TunnelService extends VpnService {
    static final String ACTION_START = "com.ethershare.START";
    static final String ACTION_STOP = "com.ethershare.STOP";
    static final String EXTRA_PC = "pc";

    static final int IDLE = 0, CONNECTING = 1, CONNECTED = 2;

    private static final String SOCKET = "ethershare";
    private static final byte[] MAGIC = {'E', 'T', 'H', 'S'};
    private static final int VERSION = 1;
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

    private LocalSocket socket;
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

    private void connect(String hint) {
        try {
            LocalSocket s = new LocalSocket();
            socket = s;
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            // Only adb may be on the other end; any app could open a socket with this name.
            Credentials peer = s.getPeerCredentials();
            if (peer == null || (peer.getUid() != SHELL_UID && peer.getUid() != ROOT_UID)) {
                throw new IOException("something other than the PC answered; unplug and plug in again");
            }
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(new byte[] {MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], VERSION});
            out.flush();
            DataInputStream din = new DataInputStream(new BufferedInputStream(in, 256 * 1024));
            byte[] head = new byte[6];
            din.readFully(head);
            for (int i = 0; i < 4; i++) {
                if (head[i] != MAGIC[i]) throw new IOException("the PC didn't answer like EtherShare");
            }
            if (head[4] != VERSION) throw new IOException("EtherShare on the PC is a different version; update both");
            byte[] name = new byte[head[5] & 0xff];
            din.readFully(name);
            pcName = name.length > 0 ? new String(name, "UTF-8") : (hint != null ? hint : "your PC");
            s.setSoTimeout(0);

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

            Thread upPump = new Thread(new Runnable() {
                @Override public void run() { pumpUp(); }
            }, "EtherShare up");
            upPump.start();
            pumpDown(din);
            disconnect("The PC connection ended. Is the cable plugged in and EtherShare open on the PC?");
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

    /** Thor -> PC: each packet from the VPN, prefixed by its length, in one write. */
    private void pumpUp() {
        byte[] buf = new byte[MTU + 2];
        try {
            FileInputStream in = new FileInputStream(tun.getFileDescriptor());
            OutputStream out = socket.getOutputStream();
            while (!stopping) {
                int n = in.read(buf, 2, MTU);
                if (n <= 0) continue;
                buf[0] = (byte) (n >> 8);
                buf[1] = (byte) n;
                out.write(buf, 0, n + 2);
                bytesUp.addAndGet(n);
            }
        } catch (IOException | RuntimeException e) {
            // The socket or VPN closed: pumpDown notices and ends the connection.
        }
        try {
            if (socket != null) socket.shutdownInput();
        } catch (IOException ignored) {
        }
    }

    /** PC -> Thor: one packet per write into the VPN. */
    private void pumpDown(DataInputStream in) {
        byte[] buf = new byte[65536];
        try {
            FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
            while (!stopping) {
                int n = in.readUnsignedShort();
                if (n == 0) continue;
                in.readFully(buf, 0, n);
                out.write(buf, 0, n);
                bytesDown.addAndGet(n);
            }
        } catch (IOException | RuntimeException e) {
            // Unplugged, or the PC program closed.
        }
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
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        try {
            if (tun != null) tun.close();
        } catch (IOException ignored) {
        }
        socket = null;
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
                downNow = (d - lastDown) / secs;
                upNow = (u - lastUp) / secs;
                System.arraycopy(downHistory, 1, downHistory, 0, HISTORY - 1);
                System.arraycopy(upHistory, 1, upHistory, 0, HISTORY - 1);
                downHistory[HISTORY - 1] = downNow;
                upHistory[HISTORY - 1] = upNow;
                downPeak = Math.max(downPeak, downNow);
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
