package io.github.lorenzdenk.lorimaps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;


import java.util.Locale;

/**
 * Map scale as wide as the view allows: a bar with a round length (1, 2 or 5 × 10^n),
 * split in the middle (black / white halves) so half the distance can be read too.
 */
public class ScaleBarView extends View {

    private MapOverlayView map;
    private boolean imperial;

    private final float dp;
    private final Paint fillDark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillLight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ScaleBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        fillDark.setColor(0xFF333333);
        fillLight.setColor(0xFFFFFFFF);
        outline.setColor(0xFF333333);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.2f * dp);
        text.setColor(0xFF222222);
        text.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        halo.set(text);
        halo.setColor(0xDDFFFFFF);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(3.5f * dp);
        halo.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setMap(MapOverlayView map) {
        this.map = map;
        invalidate();
    }

    public void setImperial(boolean imperial) {
        this.imperial = imperial;
        invalidate();
    }

    /** largest 1/2/5 × 10^n not above v */
    private static double niceBelow(double v) {
        double p = Math.pow(10, Math.floor(Math.log10(v)));
        double f = v / p;
        return (f >= 5 ? 5 : f >= 2 ? 2 : 1) * p;
    }

    private static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9)
            return String.format(Locale.ROOT, "%d", (long) Math.rint(v));
        return String.format(Locale.ROOT, "%s", (float) v);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (map == null || !map.isReady() || getWidth() == 0) return;

        // ground distance per screen pixel at the map centre
        int cy = map.getHeight() / 2;
        int w = map.getWidth();
        org.maplibre.android.geometry.LatLng a = map.fromPixels(w / 4f, cy);
        org.maplibre.android.geometry.LatLng b = map.fromPixels(3 * w / 4f, cy);
        if (a == null || b == null) return;
        double mPerPx = a.distanceTo(b) / (w / 2.0);
        if (!(mPerPx > 0)) return;

        float pad = 6 * dp;
        float maxLen = getWidth() - 2 * pad;

        // pick units and a round length
        double unitM;
        String unit;
        double maxUnits;
        if (imperial) {
            double ft = maxLen * mPerPx / 0.3048;
            if (ft >= 2640) { unitM = 1609.344; unit = "mi"; } else { unitM = 0.3048; unit = "ft"; }
        } else {
            double m = maxLen * mPerPx;
            if (m >= 1000) { unitM = 1000; unit = "km"; } else { unitM = 1; unit = "m"; }
        }
        maxUnits = maxLen * mPerPx / unitM;
        double total = niceBelow(maxUnits);
        float len = (float) (total * unitM / mPerPx);

        float barH = 6 * dp;
        float bottom = getHeight() - 2 * dp;
        float top = bottom - barH;
        float x0 = pad;
        float xm = x0 + len / 2;
        float x1 = x0 + len;

        // halo behind the bar for contrast on dark map areas
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0x99FFFFFF);
        canvas.drawRect(x0 - 2 * dp, top - 2 * dp, x1 + 2 * dp, bottom + 2 * dp, bg);
        canvas.drawRect(x0, top, xm, bottom, fillDark);
        canvas.drawRect(xm, top, x1, bottom, fillLight);
        canvas.drawRect(x0, top, x1, bottom, outline);

        float ty = top - 4 * dp;
        label(canvas, "0", x0, ty, Paint.Align.LEFT);
        label(canvas, num(total / 2), xm, ty, Paint.Align.CENTER);
        label(canvas, num(total) + " " + unit, x1, ty, Paint.Align.RIGHT);
    }

    private void label(Canvas c, String s, float x, float y, Paint.Align align) {
        halo.setTextAlign(align);
        text.setTextAlign(align);
        c.drawText(s, x, y, halo);
        c.drawText(s, x, y, text);
    }
}
