package com.ethershare;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * The live speed graph: download (blue, filled) and upload (green) for the last two minutes.
 * Full size adds the big current speeds on the left, for the top screen.
 */
final class GraphView extends View {
    static final int DOWN = Color.rgb(0x78, 0xc8, 0xff), UP = Color.rgb(0x7e, 0xe0, 0xa1);
    private static final int CARD = Color.rgb(0x11, 0x13, 0x18), GRID = Color.rgb(0x22, 0x26, 0x2f),
            SUB = Color.rgb(0x8a, 0x90, 0x9b), DIM = Color.rgb(0x5d, 0x63, 0x6e);

    private final boolean full;
    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    GraphView(Context context, boolean full) {
        super(context);
        this.full = full;
        d = getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        boolean on = TunnelService.state == TunnelService.CONNECTED;
        float left = 0;
        if (full) {
            float pad = 28 * d;
            left = Math.min(w * 0.36f, 380 * d);
            drawNumbers(c, pad, pad, left - pad, h - pad, on);
            rect.set(left, pad, w - pad, h - pad);
        } else {
            rect.set(0, 0, w, h);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(CARD);
        c.drawRoundRect(rect, 12 * d, 12 * d, paint);
        drawChart(c, rect, on);
    }

    private void drawNumbers(Canvas c, float x, float top, float width, float bottom, boolean on) {
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(15 * d);
        paint.setColor(on ? UP : TunnelService.state == TunnelService.CONNECTING ? DOWN : DIM);
        c.drawCircle(x + 5 * d, top + 10 * d, 5 * d, paint);
        paint.setColor(SUB);
        c.drawText("EtherShare", x + 18 * d, top + 15 * d, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(24 * d);
        String title = on ? TunnelService.pcName : TunnelService.state == TunnelService.CONNECTING
                ? "Connecting…" : "Not connected";
        c.drawText(ellipsize(title == null ? "your PC" : title, width), x, top + 58 * d, paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(14 * d);
        paint.setColor(SUB);
        c.drawText(on ? "Your PC's internet, over USB" : "Plug into your PC with EtherShare open", x, top + 82 * d, paint);

        float y = top + 150 * d;
        label(c, "↓  DOWNLOAD", x, y, DOWN);
        bigNumber(c, on ? Format.mbps(TunnelService.downNow) : "–", x, y + 70 * d, 64, width);
        y += 120 * d;
        label(c, "↑  UPLOAD", x, y, UP);
        bigNumber(c, on ? Format.mbps(TunnelService.upNow) : "–", x, y + 52 * d, 44, width);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(14 * d);
        paint.setColor(SUB);
        if (on) {
            c.drawText("Peak " + Format.mbps(TunnelService.downPeak) + " Mbps  ·  " + Format.size(TunnelService.sessionBytes)
                    + "  ·  " + Format.duration(System.currentTimeMillis() - TunnelService.connectedAt), x, bottom, paint);
        }
    }

    private void label(Canvas c, String s, float x, float y, int color) {
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(13 * d);
        paint.setLetterSpacing(0.1f);
        paint.setColor(color);
        c.drawText(s, x, y, paint);
        paint.setLetterSpacing(0);
    }

    private void bigNumber(Canvas c, String value, float x, float y, int sp, float width) {
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(sp * d);
        paint.setColor(Color.WHITE);
        c.drawText(value, x, y, paint);
        float end = x + paint.measureText(value) + 10 * d;
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(Math.max(14, sp / 3f) * d);
        paint.setColor(SUB);
        if (end < x + width) c.drawText("Mbps", end, y, paint);
    }

    private String ellipsize(String s, float width) {
        if (paint.measureText(s) <= width) return s;
        while (s.length() > 1 && paint.measureText(s + "…") > width) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private void drawChart(Canvas c, RectF r, boolean on) {
        float padL = 14 * d, padR = 14 * d, padT = 14 * d, padB = 26 * d;
        float x0 = r.left + padL, x1 = r.right - padR, y0 = r.top + padT, y1 = r.bottom - padB;
        float[] down = TunnelService.downHistory, up = TunnelService.upHistory;
        int n = TunnelService.HISTORY;

        float max = 0;
        for (int i = 0; i < n; i++) max = Math.max(max, Math.max(down[i], up[i]));
        float scale = niceMbps(max * 8 / 1e6f); // top of the chart, in Mbps

        // Grid lines and their labels.
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(11 * d);
        for (int i = 0; i <= 4; i++) {
            float y = y1 - (y1 - y0) * i / 4f;
            paint.setColor(GRID);
            c.drawRect(x0, y, x1, y + Math.max(1, d * 0.8f), paint);
            if (i > 0) {
                paint.setColor(DIM);
                String v = trim(scale * i / 4f) + " Mbps";
                c.drawText(v, x0 + 4 * d, y + 14 * d, paint);
            }
        }
        paint.setColor(DIM);
        c.drawText("2 min ago", x0, r.bottom - 8 * d, paint);
        String now = "now";
        c.drawText(now, x1 - paint.measureText(now), r.bottom - 8 * d, paint);

        if (!on && max == 0) {
            paint.setTextSize(16 * d);
            paint.setColor(SUB);
            String msg = TunnelService.state == TunnelService.CONNECTING ? "Connecting to your PC…" : "Waiting for your PC";
            c.drawText(msg, (x0 + x1 - paint.measureText(msg)) / 2, (y0 + y1) / 2, paint);
            return;
        }

        float bytesTop = scale * 1e6f / 8;
        // Download: filled area under a line.
        path.reset();
        for (int i = 0; i < n; i++) {
            float x = x0 + (x1 - x0) * i / (n - 1f);
            float y = y1 - (y1 - y0) * Math.min(1, down[i] / bytesTop);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        Path fill = new Path(path);
        fill.lineTo(x1, y1);
        fill.lineTo(x0, y1);
        fill.close();
        paint.setShader(new LinearGradient(0, y0, 0, y1, Color.argb(110, 120, 200, 255), Color.argb(8, 120, 200, 255),
                Shader.TileMode.CLAMP));
        c.drawPath(fill, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * d);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(DOWN);
        c.drawPath(path, paint);

        // Upload: a line.
        path.reset();
        for (int i = 0; i < n; i++) {
            float x = x0 + (x1 - x0) * i / (n - 1f);
            float y = y1 - (y1 - y0) * Math.min(1, up[i] / bytesTop);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        paint.setColor(UP);
        paint.setStrokeWidth(1.8f * d);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);

        if (!full) { // a small legend, top right
            paint.setTextSize(12 * d);
            String dl = "↓ " + Format.mbps(TunnelService.downNow) + "   ", ul = "↑ " + Format.mbps(TunnelService.upNow) + " Mbps";
            float tx = x1 - paint.measureText(dl + ul);
            paint.setColor(DOWN);
            c.drawText(dl, tx, y0 + 12 * d, paint);
            paint.setColor(UP);
            c.drawText(ul, tx + paint.measureText(dl), y0 + 12 * d, paint);
        }
    }

    /** The chart's top: 10, 20, 50, 100, 200, 500… Mbps, just above the fastest second shown. */
    static float niceMbps(float mbps) {
        float top = 10;
        float[] steps = {1, 2, 5};
        for (int e = 1; e < 6; e++) {
            for (float s : steps) {
                float v = s * (float) Math.pow(10, e);
                if (v >= mbps * 1.1f) return Math.max(top, v);
            }
        }
        return 10000;
    }

    private static String trim(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }
}
