package com.ethershare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** EtherShare settings: section tabs on the left, the chosen section's settings on the right. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private LinearLayout list;
    private float d;

    // Tabs down the left; the chosen section's settings on the right, as cards.
    private static final String[] TABS = {"🔌 Connection", "🌐 Network", "🖥️ Screens", "📳 Haptics", "🔄 Updates"};
    private static final int BG = Color.BLACK, SIDE = Color.rgb(0x0c, 0x0d, 0x11), CARD = Color.rgb(0x11, 0x13, 0x18),
            ACCENT = Color.rgb(0x78, 0xc8, 0xff), SUB = Color.rgb(0x8a, 0x90, 0x9b);
    /** The tab on show, kept when Settings rebuilds itself. */
    private static int tab;
    private LinearLayout page;
    private ScrollView scroll;
    private final TextView[] tabViews = new TextView[TABS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
                prefs = new Prefs(this);
        d = getResources().getDisplayMetrics().density;
        getWindow().getDecorView().setBackgroundColor(BG);

        LinearLayout split = new LinearLayout(this);
        split.setBackgroundColor(BG);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(SIDE);
        side.setPadding((int) (8 * d), (int) (14 * d), (int) (8 * d), (int) (12 * d));
        TextView title = new TextView(this);
        title.setText(("Settings"));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setPadding((int) (8 * d), 0, 0, (int) (14 * d));
        diagnosticsOnLongPress(title);
        side.addView(title);
        for (int i = 0; i < TABS.length; i++) {
            final int which = i;
            TextView t = new TextView(this);
            String label = TABS[i];
                t.setText(label);
            t.setTextSize(16);
            t.setSingleLine(true);
            t.setPadding((int) (12 * d), (int) (11 * d), (int) (8 * d), (int) (11 * d));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (tab == which) return;
                    Haptics.play(SettingsActivity.this, Haptics.BAR);
                    showTab(which);
                }
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.bottomMargin = (int) (4 * d);
            side.addView(t, tlp);
            tabViews[i] = t;
        }
        side.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        TextView version = new TextView(this);
        version.setText("EtherShare " + versionName());
        version.setTextSize(12);
        version.setTextColor(Color.rgb(0x5d, 0x63, 0x6e));
        version.setPadding((int) (8 * d), 0, 0, 0);
        side.addView(version);
        int sideW = (int) (getResources().getDisplayMetrics().widthPixels * 0.27f);
        split.addView(side, new LinearLayout.LayoutParams(sideW, ViewGroup.LayoutParams.MATCH_PARENT));
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(0x1a, 0x1d, 0x24));
        split.addView(line, new LinearLayout.LayoutParams(Math.max(1, (int) (0.8f * d)), ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding((int) (18 * d), (int) (16 * d), (int) (18 * d), (int) (16 * d));
        scroll.addView(page);
        split.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        FrameLayout root = new FrameLayout(this);
        root.addView(split);
        previewPill = new TextView(this);
        previewPill.setTextSize(15);
        previewPill.setTextColor(Color.rgb(0xbf, 0xe6, 0xff));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.rgb(0x1d, 0x2a, 0x36));
        pillBg.setCornerRadius(30 * d);
        previewPill.setBackground(pillBg);
        previewPill.setPadding((int) (14 * d), (int) (7 * d), (int) (14 * d), (int) (7 * d));
        previewPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = (int) (14 * d);
        root.addView(previewPill, plp);
        setContentView(root);
        showTab(tab);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void showTab(int which) {
        tab = which;
        for (int i = 0; i < tabViews.length; i++) {
            boolean sel = i == which;
            TextView t = tabViews[i];
            t.setTextColor(sel ? Color.WHITE : Color.rgb(0xaa, 0xb0, 0xbb));
            if (sel) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.rgb(0x16, 0x28, 0x3a));
                bg.setCornerRadius(10 * d);
                t.setBackground(bg);
            } else {
                t.setBackground(null);
            }
        }
        page.removeAllViews();
        list = page;
        switch (which) {
            case 0:
                header("Connection", "When the cable connection starts, and what it shows");
                toggle("Connect when plugged in", "When EtherShare on your PC finds the Thor, the cable "
                        + "connection starts by itself", Prefs.KEY_AUTO_CONNECT, prefs.autoConnect());
                toggle("Open EtherShare when it connects", "Shows the speed screens each time. Off keeps "
                        + "whatever you're playing on screen", Prefs.KEY_OPEN_ON_CONNECT, prefs.openOnConnect());
                toggle("Speed in the notification", "Live download and upload speed in EtherShare's notification",
                        Prefs.KEY_NOTIFY_SPEED, prefs.notifySpeed());
                break;
            case 1:
                header("Network", "How names are looked up, and reaching your PC");
                dnsPicker();
                pcAddressCard();
                break;
            case 2:
                header("Screens", "Where the status and the live graph go");
                toggle("Use both screens", "Status on the bottom screen, the live speed graph on the top. "
                        + "Reopen EtherShare after changing.", Prefs.KEY_BOTH_SCREENS, prefs.bothScreens());
                toggle("Swap screens", "The graph on the bottom, the status on top. Reopen EtherShare after changing.",
                        Prefs.KEY_SWAP_SCREENS, prefs.swapScreens());
                toggle("Keep the screen on", "While EtherShare's screens are open, so you can watch a big download",
                        Prefs.KEY_KEEP_SCREEN_ON, prefs.keepScreenOn());
                break;
            case 3:
                header("Haptics", "Pick a feel and strength for each action.");
                hapticsSection();
                break;
            default:
                header("Updates", "Get the newest EtherShare from GitHub");
                updateSection();
                break;
        }
        scroll.scrollTo(0, 0);
    }

    private void header(String title, String lead) {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = plain((title), 22, Color.WHITE);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        diagnosticsOnLongPress(h);
        top.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Back, top right: closes Settings.
        TextView back = chip("←  " + ("Back"));
        back.setTextSize(15);
        back.setTextColor(Color.rgb(0xd8, 0xdc, 0xe3));
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(Color.rgb(0x1c, 0x1e, 0x24));
        bbg.setCornerRadius(18 * d);
        back.setBackground(bbg);
        back.setPadding((int) (14 * d), (int) (7 * d), (int) (16 * d), (int) (7 * d));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(SettingsActivity.this, Haptics.BAR);
                finish();
            }
        });
        top.addView(back);
        page.addView(top);
        TextView l = plain((lead), 13, Color.rgb(0x7a, 0x80, 0x8b));
        l.setPadding(0, (int) (2 * d), 0, (int) (12 * d));
        page.addView(l);
    }

    /** A rounded card on the page; returned so a section can fill it. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(12 * d);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (7 * d);
        page.addView(c, lp);
        return c;
    }

    private TextView plain(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** The value chip at the end of a slider row. */
    private TextView chip(String s) {
        TextView t = plain(s, 14, Color.rgb(0x9f, 0xd2, 0xf5));
        t.setGravity(Gravity.CENTER);
        t.setMinWidth((int) (42 * d));
        t.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(0x16, 0x22, 0x2d));
        bg.setCornerRadius(9 * d);
        t.setBackground(bg);
        return t;
    }

    private void diagnosticsOnLongPress(View v) {
        // Long-press a heading: saves screenshots and the window list for bug reports.
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                saveDiagnostics();
                return true;
            }
        });
    }

    // ---- Network ------------------------------------------------------------------------------

    private void dnsPicker() {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(plain("DNS", 16, Color.WHITE));
        TextView s = plain("Who turns names like youtube.com into addresses. Your PC's DNS follows the PC, "
                + "including a Pi-hole or a work VPN. Takes effect on the next connection", 12, SUB);
        s.setPadding(0, (int) (2 * d), 0, 0);
        words.addView(s);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pick = chip("🌐 " + Prefs.DNS_NAMES[prefs.dnsIndex()] + " ▾");
        pick.setTextSize(15);
        pick.setPadding((int) (12 * d), (int) (7 * d), (int) (12 * d), (int) (7 * d));
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("DNS")
                        .setSingleChoiceItems(Prefs.DNS_NAMES, prefs.dnsIndex(), new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                prefs.setDns(which);
                                showTab(1);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = (int) (12 * d);
        row.addView(pick, plp);
        c.addView(row);
    }

    private void pcAddressCard() {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(plain("Your PC's address", 16, Color.WHITE));
        TextView s = plain("While connected, apps on the Thor reach the PC itself at 10.88.0.1, for example "
                + "Moonlight, a media server or a local web page. Other devices on your home network keep "
                + "their usual addresses", 12, SUB);
        s.setPadding(0, (int) (2 * d), 0, 0);
        words.addView(s);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView ip = chip("10.88.0.1");
        ip.setTextSize(15);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = (int) (12 * d);
        row.addView(ip, plp);
        c.addView(row);
    }

    private void saveDiagnostics() {
        if (!ShizukuClient.hasPermission()) {
            Toast.makeText(this, ("Needs Shizuku"), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, ("Saving screenshots…"), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = ShizukuClient.runShell(
                        "d=/sdcard/Download; "
                        // The VPN, routes and DNS, for connection problems.
                        + "dumpsys connectivity > $d/ethershare-network.txt 2>&1; "
                        + "dumpsys window windows > $d/ethershare-windows.txt; "
                        // What EtherShare was stuck on, if it ever showed "not responding".
                        + "dumpsys dropbox --print data_app_anr > $d/ethershare-anr.txt 2>&1; "
                        + "i=0; for id in $(dumpsys SurfaceFlinger --display-id | sed -n 's/^Display \\([0-9]*\\).*/\\1/p'); do "
                        + "i=$((i+1)); screencap -d $id -p $d/ethershare-screen-$i.png; done; "
                        + "screencap -p $d/ethershare-screen-main.png; true");
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(SettingsActivity.this, ok
                                ? ("Saved ethershare-screen, -windows, -network and -anr files to Download")
                                : ("Couldn't save the screenshots"), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "EtherShare diagnostics").start();
    }

    // ---- Updates -------------------------------------------------------------------------------

    private void updateSection() {
        list = card();
        final TextView status = plain("EtherShare " + versionName(), 16, Color.WHITE);
        status.setPadding(0, 0, 0, (int) (6 * d));
        list.addView(status);
        final Button check = new Button(this);
        check.setText(("Check for updates"));
        check.setAllCaps(false);
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                check.setEnabled(false);
                final Updater[] updater = new Updater[1];
                updater[0] = new Updater(SettingsActivity.this, new Updater.Listener() {
                    @Override public void onUpdateStatus(String s, boolean done) {
                        status.setText(s);
                        if (done) check.setEnabled(true);
                    }

                    @Override public void onUpdateAvailable(Updater.Release r) {
                        UpdateDialog.show(SettingsActivity.this, r, updater[0], null);
                    }
                });
                updater[0].check();
            }
        });
        list.addView(check, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Installs the latest release again even if it's this version, e.g. after its APK
        // was replaced on GitHub without a new version number.
        final Button reinstall = new Button(this);
        reinstall.setText(("Reinstall"));
        reinstall.setAllCaps(false);
        reinstall.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                reinstall.setEnabled(false);
                final Updater[] updater = new Updater[1];
                updater[0] = new Updater(SettingsActivity.this, new Updater.Listener() {
                    @Override public void onUpdateStatus(String s, boolean done) {
                        status.setText(s);
                        if (done) reinstall.setEnabled(true);
                    }

                    @Override public void onUpdateAvailable(Updater.Release r) {
                        UpdateDialog.show(SettingsActivity.this, r, updater[0], null);
                    }
                });
                updater[0].check(true);
            }
        });
        list.addView(reinstall, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list = page;
        releaseNotesSection();
    }

    /** This version's release notes (bundled with the app by build.sh), under the update buttons. */
    private void releaseNotesSection() {
        String notes;
        try {
            java.io.InputStream in = getAssets().open("release-notes.md");
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            notes = buf.toString("UTF-8");
        } catch (java.io.IOException e) {
            return; // a build without notes: no section
        }
        list = card();
        TextView title = plain("📄 " + ("Release notes") + " · EtherShare " + versionName(), 16, Color.WHITE);
        title.setPadding(0, 0, 0, (int) (6 * d));
        list.addView(title);
        TextView body = plain("", 14, SUB);
        body.setText(ReleaseNotes.format(notes));
        body.setLineSpacing(0, 1.15f);
        list.addView(body);
        list = page;
    }

    // ---- Haptics -------------------------------------------------------------------------------

    private TextView previewPill;

    private final Runnable hidePill = new Runnable() {
        @Override public void run() { previewPill.setVisibility(View.GONE); }
    };

    private void hapticsSection() {
        if (!Haptics.hasStrengthControl(this)) {
            TextView motor = text("This device's vibration motor can't buzz softer or harder, so strength "
                    + "changes how long each buzz lasts instead.", 13);
            motor.setTextColor(SUB);
            list.addView(motor);
        }
        list = card();
        for (int a = 0; a < Haptics.ACTION_NAMES.length; a++) hapticRow(a);
        list = page;
        // Room so the preview pill never covers the last row.
        page.addView(new View(this), new LinearLayout.LayoutParams(1, (int) (50 * d)));
    }

    private void hapticRow(final int action) {
        final SharedPreferences sp = prefs.raw();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) (46 * d));

        TextView name = new TextView(this);
        name.setText((Haptics.ACTION_NAMES[action]));
        name.setTextSize(15);
        name.setTextColor(Color.WHITE);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Spinner feel = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, Haptics.feelNames()) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(Color.rgb(0xcf, 0xe9, 0xff));
                t.setTextSize(15);
                return t;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        feel.setAdapter(adapter);
        feel.setSelection(Haptics.feel(sp, action), false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(0x1c, 0x1e, 0x24));
        bg.setCornerRadius(8 * d);
        feel.setBackground(bg);
        row.addView(feel, new LinearLayout.LayoutParams((int) (110 * d), (int) (36 * d)));

        // Strength: a chip showing the %, which opens a vertical slider under it.
        final int[] strength = {Haptics.strength(sp, action)};
        final TextView pct = chip(strength[0] + "%  ▾");
        pct.setTextSize(15);
        pct.setPadding((int) (10 * d), (int) (7 * d), (int) (10 * d), (int) (7 * d));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams((int) (76 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = (int) (10 * d);
        row.addView(pct, plp);
        pct.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                strengthPopup(pct, strength[0], new StrengthListener() {
                    @Override public void onStrength(int value, boolean done) {
                        strength[0] = value;
                        pct.setText(value + "%  ▾");
                        if (done) {
                            sp.edit().putInt(Haptics.strengthKey(action), value).apply();
                            preview(feel.getSelectedItemPosition(), value);
                        }
                    }
                });
            }
        });

        feel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == Haptics.feel(sp, action)) return; // not a change
                sp.edit().putInt(Haptics.feelKey(action), position).apply();
                preview(position, strength[0]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        list.addView(row);
    }

    private interface StrengthListener {
        /** done = the finger lifted: save and play a preview. */
        void onStrength(int value, boolean done);
    }

    /** A small panel under the chip: the % on top and a tall vertical slider (0 at the bottom). */
    private void strengthPopup(View anchor, int value, final StrengthListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, (int) (10 * d), 0, (int) (12 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(0x16, 0x19, 0x1f));
        bg.setStroke(Math.max(1, (int) (0.8f * d)), Color.rgb(0x2a, 0x2f, 0x3a));
        bg.setCornerRadius(14 * d);
        box.setBackground(bg);
        final TextView shown = plain(value + "%", 17, Color.rgb(0xbf, 0xe6, 0xff));
        shown.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(shown);
        final int[] v = {value};
        View slider = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float cx = getWidth() / 2f, r = 12 * d, top = r + 2 * d, bottom = getHeight() - r - 2 * d;
                float y = bottom - (bottom - top) * v[0] / 100f;
                paint.setColor(Color.rgb(0x2e, 0x32, 0x3b));
                c.drawRoundRect(cx - 3.5f * d, top, cx + 3.5f * d, bottom, 3.5f * d, 3.5f * d, paint);
                paint.setColor(ACCENT);
                c.drawRoundRect(cx - 3.5f * d, y, cx + 3.5f * d, bottom, 3.5f * d, 3.5f * d, paint);
                paint.setColor(Color.argb(38, 120, 200, 255));
                c.drawCircle(cx, y, r + 6 * d, paint);
                paint.setColor(Color.rgb(0xbf, 0xe6, 0xff));
                c.drawCircle(cx, y, r, paint);
            }
            @Override public boolean onTouchEvent(MotionEvent ev) {
                float r = 12 * d, top = r + 2 * d, bottom = getHeight() - r - 2 * d;
                int value = Math.round((bottom - ev.getY()) / (bottom - top) * 100);
                value = Math.max(0, Math.min(100, value));
                int action = ev.getActionMasked();
                if (value != v[0]) {
                    v[0] = value;
                    shown.setText(value + "%");
                    listener.onStrength(value, false);
                    invalidate();
                }
                if (action == MotionEvent.ACTION_UP) listener.onStrength(v[0], true);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        };
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams((int) (60 * d), (int) (170 * d));
        slp.topMargin = (int) (6 * d);
        slp.bottomMargin = (int) (4 * d);
        box.addView(slider, slp);
        box.addView(plain("▶", 12, SUB));
        PopupWindow popup = new PopupWindow(box, Math.max(anchor.getWidth(), (int) (76 * d)),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(16 * d);
        popup.showAsDropDown(anchor, 0, (int) (4 * d));
    }

    private void preview(int feel, int strength) {
        Haptics.play(this, feel, strength);
        previewPill.setText(feel == Haptics.OFF || strength == 0
                ? ("Off") : String.format("▶ Preview: %s", (Haptics.FEEL_NAMES[feel]) + ", " + strength + "%"));
        previewPill.setVisibility(View.VISIBLE);
        previewPill.removeCallbacks(hidePill);
        previewPill.postDelayed(hidePill, 1500);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText((s));
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, (int) (6 * d), 0, (int) (4 * d));
        return t;
    }

    /** A card with a title, a gray line under it, and a switch on the right; the whole card toggles. */
    private void toggle(String title, String sub, final String key, boolean value) {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(plain((title), 16, Color.WHITE));
        TextView s = plain((sub), 12, SUB);
        s.setPadding(0, (int) (2 * d), 0, 0);
        words.addView(s);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch sw = new Switch(this);
        sw.setChecked(value);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[] {Color.rgb(0xbf, 0xe6, 0xff), Color.rgb(0x9a, 0xa0, 0xab)}));
        sw.setTrackTintList(new ColorStateList(states, new int[] {Color.rgb(0x2b, 0x6e, 0x99), Color.rgb(0x3a, 0x3e, 0x48)}));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                SharedPreferences.Editor e = prefs.raw().edit();
                e.putBoolean(key, checked);
                e.apply();
            }
        });
        LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swp.leftMargin = (int) (12 * d);
        row.addView(sw, swp);
        c.addView(row);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.toggle(); }
        });
    }

}
