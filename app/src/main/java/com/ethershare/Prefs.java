package com.ethershare;

import android.content.Context;
import android.content.SharedPreferences;

/** User-tunable settings, shared by the screens and the VPN service. */
final class Prefs {
    static final String KEY_BOTH_SCREENS = "both_screens";
    static final String KEY_SWAP_SCREENS = "swap_screens";
    static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    static final String KEY_AUTO_CONNECT = "auto_connect";
    static final String KEY_OPEN_ON_CONNECT = "open_on_connect";
    static final String KEY_NOTIFY_SPEED = "notify_speed";
    static final String KEY_DNS = "dns";

    /** DNS choices: the value stored, the name shown, and the servers the VPN gives Android. */
    static final String[] DNS_KEYS = {"pc", "cloudflare", "google", "quad9"};
    static final String[] DNS_NAMES = {"Your PC's DNS", "Cloudflare (1.1.1.1)", "Google (8.8.8.8)", "Quad9 (9.9.9.9)"};
    static final String[][] DNS_SERVERS = {
            {"10.88.0.1"}, {"1.1.1.1", "1.0.0.1"}, {"8.8.8.8", "8.8.4.4"}, {"9.9.9.9", "149.112.112.112"}};

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences("ethershare", Context.MODE_PRIVATE);
    }

    SharedPreferences raw() { return sp; }

    /** Status and controls on the bottom screen, the live speed graph on the top screen. */
    boolean bothScreens() { return sp.getBoolean(KEY_BOTH_SCREENS, true); }
    /** The graph on the bottom screen, the controls on the top. */
    boolean swapScreens() { return sp.getBoolean(KEY_SWAP_SCREENS, false); }
    boolean keepScreenOn() { return sp.getBoolean(KEY_KEEP_SCREEN_ON, false); }
    /** Start the cable connection when EtherShare on the PC finds the Thor. */
    boolean autoConnect() { return sp.getBoolean(KEY_AUTO_CONNECT, true); }
    /** Open the EtherShare screens each time the PC connects. */
    boolean openOnConnect() { return sp.getBoolean(KEY_OPEN_ON_CONNECT, false); }
    /** Live speeds in the notification. */
    boolean notifySpeed() { return sp.getBoolean(KEY_NOTIFY_SPEED, true); }

    int dnsIndex() {
        String v = sp.getString(KEY_DNS, DNS_KEYS[0]);
        for (int i = 0; i < DNS_KEYS.length; i++) if (DNS_KEYS[i].equals(v)) return i;
        return 0;
    }

    void setDns(int index) { sp.edit().putString(KEY_DNS, DNS_KEYS[index]).apply(); }
}
