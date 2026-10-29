package com.ethershare;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** The bottom screen: the connection's status, speeds, setup steps and the speed test. */
public class MainActivity extends Activity implements TunnelService.Listener {
    private static final int BG = Color.BLACK, CARD = Color.rgb(0x11, 0x13, 0x18),
            SUB = Color.rgb(0x8a, 0x90, 0x9b), DIM = Color.rgb(0x5d, 0x63, 0x6e),
            GREEN = Color.rgb(0x7e, 0xe0, 0xa1), BLUE = Color.rgb(0x78, 0xc8, 0xff);

    private float d;
    private boolean started;
    private Button connectButton;
    private TextView statusIcon, statusTitle, statusSub, statusTime;
    private TextView downValue, upValue, usedValue;
    private LinearLayout tiles, setupCard, testCard;
    private TextView[] stepIcons = new TextView[3];
    private TextView testLine, testCable, testWifi, testRun;
    private GraphView graph;
    private View spacer;
    private String testProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;
        getWindow().getDecorView().setBackgroundColor(BG);

        FrameLayout frame = new FrameLayout(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding((int) (12 * d), (int) (72 * d), (int) (12 * d), (int) (8 * d));
        // Scrolls when the cards don't fit (the setup steps and the speed test, before connecting).
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        page.addView(statusCard());
        tiles = new LinearLayout(this);
        downValue = tile("↓  Download", BLUE);
        upValue = tile("↑  Upload", GREEN);
        usedValue = tile("📦  Used", SUB);
        page.addView(tiles, margins(ViewGroup.LayoutParams.WRAP_CONTENT));
        setupCard = setupCard();
        page.addView(setupCard, margins(ViewGroup.LayoutParams.WRAP_CONTENT));
        testCard = testCard();
        page.addView(testCard, margins(ViewGroup.LayoutParams.WRAP_CONTENT));
        graph = new GraphView(this, false);
        graph.setMinimumHeight((int) (110 * d));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        glp.bottomMargin = (int) (6 * d);
        page.addView(graph, glp);
        // Fills the rest when not connected, so the gamepad line stays at the bottom.
        spacer = new View(this);
        page.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView pad = plain("Ⓐ Connect    Ⓧ Speed test    Ⓨ Settings    Ⓑ Close", 11, DIM);
        pad.setGravity(Gravity.CENTER);
        page.addView(pad);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * d), Gravity.TOP);
        blp.setMargins((int) (10 * d), (int) (10 * d), (int) (10 * d), 0);
        frame.addView(menuBar(), blp);
        setContentView(frame);

        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    // ---- Menu pill --------------------------------------------------------------------------

    private LinearLayout menuBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(CARD);
        barBg.setStroke(Math.max(1, (int) (0.8f * d)), Color.rgb(0x22, 0x26, 0x2f));
        barBg.setCornerRadius(26 * d);
        bar.setBackground(barBg);
        bar.setElevation(14 * d);
        connectButton = barButton(null, "", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleConnection(); }
        });
        // The connect button stands out: it shows the connection's state.
        GradientDrawable modeBg = new GradientDrawable();
        modeBg.setColor(Color.rgb(0x16, 0x28, 0x3a));
        modeBg.setCornerRadius(22 * d);
        connectButton.setBackground(modeBg);
        connectButton.setTextColor(Color.rgb(0xbf, 0xe6, 0xff));
        bar.addView(connectButton, weight(1.25f));
        bar.addView(separator());
        bar.addView(barButton("⚡", "Speed test", new View.OnClickListener() {
            @Override public void onClick(View v) { runSpeedTest(); }
        }), weight(1));
        bar.addView(separator());
        bar.addView(barButton("⚙️", "Settings", new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        }), weight(1));
        bar.addView(separator());
        Button close = barButton("✕", "Close", new View.OnClickListener() {
            @Override public void onClick(View v) { close(); }
        });
        close.setTextColor(Color.rgb(0xff, 0xb3, 0xba));
        bar.addView(close, weight(0.8f));
        return bar;
    }

    private static LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
        lp.setMargins(2, 0, 2, 0);
        return lp;
    }

    /** The thin line between two buttons in the menu pill. */
    private View separator() {
        View v = new View(this);
        v.setBackgroundColor(Color.rgb(0x22, 0x26, 0x2f));
        v.setLayoutParams(new LinearLayout.LayoutParams(Math.max(1, (int) (0.8f * d)), (int) (20 * d)));
        return v;
    }

    /** A menu button: an emoji, then its label. */
    private Button barButton(String icon, String text, final View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(icon == null ? text : icon + "  " + text);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setTextSize(14);
        b.setTextColor(Color.rgb(0xd8, 0xdc, 0xe3));
        b.setPadding(0, 0, 0, 0);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(22 * d);
        b.setBackground(bg);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(MainActivity.this, Haptics.BAR);
                listener.onClick(v);
            }
        });
        return b;
    }

    // ---- Cards ------------------------------------------------------------------------------

    private LinearLayout.LayoutParams margins(int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.bottomMargin = (int) (8 * d);
        return lp;
    }

    private LinearLayout card(int orientation) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(orientation);
        c.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(12 * d);
        c.setBackground(bg);
        return c;
    }

    private TextView plain(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** A small rounded chip, the same as Settings' value chips. */
    private TextView chip(String s) {
        TextView t = plain(s, 14, Color.rgb(0x9f, 0xd2, 0xf5));
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(0x16, 0x22, 0x2d));
        bg.setCornerRadius(14 * d);
        t.setBackground(bg);
        return t;
    }

    private View statusCard() {
        LinearLayout c = card(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        statusIcon = plain("", 24, Color.WHITE);
        statusIcon.setGravity(Gravity.CENTER);
        c.addView(statusIcon, new LinearLayout.LayoutParams((int) (48 * d), (int) (48 * d)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        statusTitle = plain("", 19, Color.WHITE);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitle.setSingleLine(true);
        statusTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(statusTitle);
        statusSub = plain("", 13, SUB);
        statusSub.setMaxLines(2);
        statusSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(statusSub);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wlp.leftMargin = (int) (12 * d);
        c.addView(words, wlp);
        statusTime = chip("");
        c.addView(statusTime);
        LinearLayout.LayoutParams lp = margins(ViewGroup.LayoutParams.WRAP_CONTENT);
        c.setLayoutParams(lp);
        return c;
    }

    private void setStatusIcon(String emoji, int ring) {
        statusIcon.setText(emoji);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(40, Color.red(ring), Color.green(ring), Color.blue(ring)));
        bg.setStroke(Math.max(1, (int) (1.5f * d)), ring);
        statusIcon.setBackground(bg);
    }

    private TextView tile(String label, int color) {
        LinearLayout c = card(LinearLayout.VERTICAL);
        c.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        TextView l = plain(label, 12, color);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(l);
        TextView v = plain("–", 24, Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setSingleLine(true);
        c.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (tiles.getChildCount() > 0) lp.leftMargin = (int) (8 * d);
        tiles.addView(c, lp);
        return v;
    }

    private LinearLayout setupCard() {
        LinearLayout c = card(LinearLayout.VERTICAL);
        TextView t = plain("Get set up", 16, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, (int) (4 * d));
        c.addView(t);
        stepIcons[0] = step(c, "Turn on USB debugging", "In Developer options. The PC uses it to reach EtherShare",
                "Open", new View.OnClickListener() {
                    @Override public void onClick(View v) { openDeveloperOptions(); }
                });
        stepIcons[1] = step(c, "Open EtherShare on your PC",
                "Get it for Windows, Mac or Linux from github.com/ajhockey88/EtherShare", null, null);
        stepIcons[2] = step(c, "Plug the Thor into the PC",
                "With a USB-C cable that carries data. The PC starts the connection by itself", "Try now",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { connect(); }
                });
        return c;
    }

    private TextView step(LinearLayout card, String title, String sub, String action, final View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        TextView icon = plain("", 15, Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(Color.rgb(0x16, 0x19, 0x1f));
        ibg.setCornerRadius(8 * d);
        icon.setBackground(ibg);
        row.addView(icon, new LinearLayout.LayoutParams((int) (28 * d), (int) (28 * d)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(plain(title, 15, Color.WHITE));
        TextView s = plain(sub, 12, SUB);
        words.addView(s);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wlp.leftMargin = (int) (10 * d);
        wlp.rightMargin = (int) (8 * d);
        row.addView(words, wlp);
        if (action != null) {
            TextView a = chip(action);
            a.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.play(MainActivity.this, Haptics.BAR);
                    click.onClick(v);
                }
            });
            row.addView(a);
        }
        card.addView(row);
        return icon;
    }

    private LinearLayout testCard() {
        LinearLayout c = card(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView t = plain("⚡ Speed test", 16, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        words.addView(t);
        testLine = plain("", 12, SUB);
        words.addView(testLine);
        top.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        testRun = chip("Run");
        testRun.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(MainActivity.this, Haptics.BAR);
                runSpeedTest();
            }
        });
        top.addView(testRun);
        c.addView(top);
        testCable = resultRow(c);
        testWifi = resultRow(c);
        return c;
    }

    private TextView resultRow(LinearLayout card) {
        TextView t = plain("", 14, Color.rgb(0xc9, 0xce, 0xd8));
        t.setPadding(0, (int) (6 * d), 0, 0);
        card.addView(t);
        return t;
    }

    // ---- State ------------------------------------------------------------------------------

    private void refresh() {
        int state = TunnelService.state;
        boolean on = state == TunnelService.CONNECTED;
        if (on) {
            setStatusIcon("🔌", GREEN);
            statusTitle.setText("Connected to " + TunnelService.pcName);
            statusSub.setText("Using your PC's internet over the USB cable");
            statusTime.setText(Format.duration(System.currentTimeMillis() - TunnelService.connectedAt));
            statusTime.setVisibility(View.VISIBLE);
            connectButton.setText("⏹️  Disconnect");
        } else if (state == TunnelService.CONNECTING) {
            setStatusIcon("⏳", BLUE);
            statusTitle.setText("Connecting…");
            statusSub.setText("Reaching EtherShare on your PC");
            statusTime.setVisibility(View.GONE);
            connectButton.setText("⏳  Connecting");
        } else {
            setStatusIcon("💤", DIM);
            statusTitle.setText("Not connected");
            String err = TunnelService.lastError;
            statusSub.setText(err != null ? err : "Plug into your PC with EtherShare open on it");
            statusTime.setVisibility(View.GONE);
            connectButton.setText("🔌  Connect");
        }
        tiles.setVisibility(on ? View.VISIBLE : View.GONE);
        setupCard.setVisibility(on || state == TunnelService.CONNECTING ? View.GONE : View.VISIBLE);
        graph.setVisibility(on ? View.VISIBLE : View.GONE);
        spacer.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) {
            downValue.setText(Format.mbps(TunnelService.downNow) + " Mbps");
            upValue.setText(Format.mbps(TunnelService.upNow) + " Mbps");
            usedValue.setText(Format.size(TunnelService.sessionBytes));
            graph.invalidate();
        }
        boolean adb = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        stepIcons[0].setText(adb ? "✅" : "1");
        stepIcons[1].setText("2");
        stepIcons[2].setText("3");
        refreshTest();
    }

    private void refreshTest() {
        SharedPreferences sp = new Prefs(this).raw();
        boolean running = SpeedTest.isRunning();
        testRun.setText(running ? "Testing…" : "Run");
        testRun.setEnabled(!running);
        if (running && testProgress != null) {
            testLine.setText(testProgress);
        } else {
            testLine.setText(TunnelService.state == TunnelService.CONNECTED
                    ? "Tests the cable now. Test without it to compare with Wi-Fi"
                    : "Tests Wi-Fi now. Test again once connected to compare");
        }
        testCable.setText(resultText("🔌 Cable", SpeedTest.Result.load(sp, true)));
        testWifi.setText(resultText("📶 Wi-Fi", SpeedTest.Result.load(sp, false)));
    }

    private static String resultText(String name, SpeedTest.Result r) {
        if (r == null) return name + "    not tested yet";
        return name + "    ↓ " + fmt(r.downMbps) + "   ↑ " + fmt(r.upMbps) + " Mbps   ·   " + Math.round(r.pingMs) + " ms ping";
    }

    private static String fmt(double mbps) {
        return Format.mbps(mbps * 1e6 / 8);
    }

    @Override
    public void onTunnelChanged() { refresh(); }

    // ---- Actions ----------------------------------------------------------------------------

    private void toggleConnection() {
        if (TunnelService.state == TunnelService.IDLE) connect();
        else TunnelService.stop(this);
    }

    private void connect() {
        if (TunnelService.state != TunnelService.IDLE) return;
        startActivity(new Intent(this, ConnectActivity.class).putExtra(ConnectActivity.EXTRA_MANUAL, true));
    }

    private void runSpeedTest() {
        if (SpeedTest.isRunning()) return;
        testProgress = "Starting…";
        new SpeedTest(new SpeedTest.Listener() {
            @Override public void onProgress(String phase, double value) {
                testProgress = phase.equals("Ping") ? "Ping… " + Math.round(value) + " ms"
                        : phase + "… " + fmt(value) + " Mbps";
                refreshTest();
            }

            @Override public void onDone(SpeedTest.Result r) {
                r.save(new Prefs(MainActivity.this).raw());
                testProgress = null;
                Haptics.play(MainActivity.this, Haptics.SPEED_TEST);
                refreshTest();
            }

            @Override public void onFailed(String why) {
                testProgress = null;
                refreshTest();
                Toast.makeText(MainActivity.this, "Speed test failed: " + why, Toast.LENGTH_LONG).show();
            }
        }).start();
        refreshTest();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            return;
        } catch (RuntimeException ignored) {
        }
        Toast.makeText(this, "Developer options are hidden: tap Build number 7 times, then come back", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        } catch (RuntimeException ignored) {
        }
    }

    private void close() {
        GraphActivity.close();
        finishAndRemoveTask();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                Haptics.play(this, Haptics.BAR);
                toggleConnection();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                Haptics.play(this, Haptics.BAR);
                runSpeedTest();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_START:
                Haptics.play(this, Haptics.BAR);
                openSettings();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                close();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- Lifecycle --------------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        TunnelService.addListener(this);
        refresh();
        quietUpdateCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (new Prefs(this).keepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refresh(); // USB debugging may have just been turned on
    }

    @Override
    protected void onStop() {
        started = false;
        TunnelService.removeListener(this);
        super.onStop();
    }

    /** Opens the live graph on the top screen (or the bottom, with Swap screens). */
    static void openGraphScreen(Context context) {
        Display top = Displays.graphDisplay(context);
        if (top == null) return;
        Intent intent = new Intent(context, GraphActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(top.getDisplayId());
            context.startActivity(intent, options.toBundle());
        } catch (RuntimeException ignored) {
            // No second screen after all; the bottom screen works alone.
        }
    }

    // ---- Quiet update check -----------------------------------------------------------------

    /**
     * Checked each time EtherShare opens (a new screen after Close), and when it comes back after
     * an hour or more. Android keeps EtherShare's process around after Close, so this can't be
     * once per process.
     */
    private boolean updateChecked;
    private static long lastUpdateCheckAt;
    private static final long UPDATE_RECHECK_MS = 60 * 60 * 1000;
    private static final String SKIPPED_UPDATE = "skipped_update";

    /**
     * Looks for a newer release when EtherShare opens and says nothing unless there is one. "Not
     * now" stops it asking again about that version; Settings > Check for updates still offers it.
     */
    private void quietUpdateCheck() {
        long now = SystemClock.elapsedRealtime();
        if (updateChecked && lastUpdateCheckAt != 0 && now - lastUpdateCheckAt < UPDATE_RECHECK_MS) return;
        updateChecked = true;
        lastUpdateCheckAt = now;
        final SharedPreferences sp = new Prefs(this).raw();
        final boolean[] installing = {false};
        final Updater[] updater = new Updater[1];
        updater[0] = new Updater(this, new Updater.Listener() {
            @Override public void onUpdateStatus(String status, boolean done) {
                // Silent while checking (even offline); once the user picked Update, say how it goes.
                if (!installing[0] && !done && !status.equals("Checking for updates…")) {
                    installing[0] = true;
                    Toast.makeText(MainActivity.this, "Downloading the update…", Toast.LENGTH_SHORT).show();
                } else if (installing[0] && done) {
                    Toast.makeText(MainActivity.this, status, Toast.LENGTH_LONG).show();
                }
            }

            @Override public void onUpdateAvailable(final Updater.Release r) {
                if (!started || isFinishing() || r.version.equals(sp.getString(SKIPPED_UPDATE, null))) return;
                UpdateDialog.show(MainActivity.this, r, updater[0], new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        sp.edit().putString(SKIPPED_UPDATE, r.version).apply();
                    }
                });
            }
        });
        updater[0].check();
    }
}
