package io.github.lorenzdenk.lorimaps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Transparent view over the map for everything the app draws itself (position, pins, ruler).
 * It is redrawn whenever the map moves; the layers get screen positions from the map's projection.
 * Touches pass through to the map below.
 */
public class MapOverlayView extends View {

    public interface Layer {
        void draw(Canvas canvas, MapOverlayView view);
    }

    @Nullable private MapLibreMap map;
    private final List<Layer> layers = new ArrayList<>();

    public MapOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMap(MapLibreMap map) {
        this.map = map;
        invalidate();
    }

    /** layers are drawn in the order they were added (the last one on top) */
    public void addLayer(Layer layer) {
        layers.add(layer);
        invalidate();
    }

    public boolean isReady() {
        return map != null && getWidth() > 0;
    }

    /** screen position (pixels of this view) of a point on the map; false while the map is not ready */
    public boolean toPixels(double lat, double lon, PointF out) {
        if (map == null) return false;
        PointF p = map.getProjection().toScreenLocation(new LatLng(lat, lon));
        out.set(p.x, p.y);
        return true;
    }

    @Nullable
    public LatLng fromPixels(float x, float y) {
        if (map == null) return null;
        return map.getProjection().fromScreenLocation(new PointF(x, y));
    }

    /** length in screen pixels of a distance in metres at the given place */
    public float metersToPixels(double meters, double lat, double lon) {
        if (map == null || meters <= 0) return 0;
        double dLat = Math.min(meters / 111320.0, 1.0);
        double lat2 = lat + dLat > 85 ? lat - dLat : lat + dLat;
        PointF a = map.getProjection().toScreenLocation(new LatLng(lat, lon));
        PointF b = map.getProjection().toScreenLocation(new LatLng(lat2, lon));
        double px = Math.hypot(a.x - b.x, a.y - b.y);
        return (float) (px * meters / (dLat * 111320.0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isReady()) return;
        for (Layer l : layers)
            l.draw(canvas, this);
    }
}
