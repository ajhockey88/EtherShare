package com.ethershare;

import java.util.Locale;

/** Speeds, sizes and times as the screens show them. */
final class Format {
    private Format() {}

    /** Bytes per second as megabits per second, the unit internet speeds are sold in. */
    static String mbps(double bytesPerSec) {
        double m = bytesPerSec * 8 / 1e6;
        return String.format(Locale.US, m >= 100 ? "%.0f" : m >= 10 ? "%.1f" : "%.2f", m);
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s", v, units[u]);
    }

    /** 0:42, 12:05, 1:02:33 */
    static String duration(long ms) {
        long s = ms / 1000, h = s / 3600, m = s / 60 % 60;
        s %= 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }
}
