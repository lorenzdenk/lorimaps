package io.github.lorenzdenk.lorimaps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Current position as in MyTrails: translucent circle for the GPS accuracy, a red cone for the
 * direction the phone points to (compass, falls back to the GPS course while moving) and a red dot.
 * Gets the position itself from the GPS and network location providers.
 */
public class LocationMarker implements MapOverlayView.Layer, SensorEventListener, LocationListener {

    public interface Listener {
        void onLocation(Location location);
    }

    private static final float CONE_HALF_ANGLE = 30f;
    /** a network position is ignored for this long after a GPS position */
    private static final long GPS_PRIORITY_MS = 20_000;

    private final MapOverlayView view;
    private final LocationManager locationManager;
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final WindowManager windowManager;
    private final float density;
    private final Paint accuracyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accuracyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint conePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PointF pixel = new PointF();
    private final RectF oval = new RectF();
    private final float[] rotation = new float[9];
    private final float[] remapped = new float[9];
    private final float[] orientation = new float[3];

    /** "show position" is switched on */
    private boolean enabled;
    /** updates are running (enabled and the app is in the foreground) */
    private boolean running;
    private boolean follow;
    @Nullable private Location lastFix;
    private long lastGpsFix;
    @Nullable private Listener listener;

    private float azimuth = Float.NaN;      // degrees from true north, smoothed
    private float drawnAzimuth = Float.NaN;
    private float declination;
    private Location declinationFor;
    private long lastInvalidate;

    public LocationMarker(MapOverlayView view) {
        this.view = view;
        Context context = view.getContext();
        density = context.getResources().getDisplayMetrics().density;
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        accuracyFill.setColor(Color.argb(60, 70, 90, 255));
        accuracyStroke.setColor(Color.argb(150, 70, 90, 255));
        accuracyStroke.setStyle(Paint.Style.STROKE);
        accuracyStroke.setStrokeWidth(1.5f * density);
        dotFill.setColor(Color.rgb(230, 20, 20));
        dotStroke.setColor(Color.WHITE);
        dotStroke.setStyle(Paint.Style.STROKE);
        dotStroke.setStrokeWidth(2f * density);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    public Location getLastFix() {
        return enabled ? lastFix : null;
    }

    public boolean isFollowing() {
        return enabled && follow;
    }

    public void setFollowing(boolean follow) {
        this.follow = follow;
    }

    // ------------------------------------------------------------------ switching on and off

    /** Shows the position. The location permission must have been granted. */
    public void enable() {
        enabled = true;
        start();
        view.invalidate();
    }

    public void disable() {
        enabled = false;
        follow = false;
        stop();
        lastFix = null;
        azimuth = Float.NaN;
        view.invalidate();
    }

    /** app in the foreground again */
    public void resume() {
        if (enabled) start();
    }

    /** app in the background: no position updates, saves battery */
    public void pause() {
        stop();
    }

    @SuppressLint("MissingPermission")
    private void start() {
        if (running || locationManager == null) return;
        try {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!locationManager.getAllProviders().contains(provider)) continue;
                Location last = locationManager.getLastKnownLocation(provider);
                if (last != null && (lastFix == null || last.getTime() > lastFix.getTime()))
                    lastFix = last;
                locationManager.requestLocationUpdates(provider, 1000, 0, this);
            }
            running = true;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e("LoriMaps", e.toString());
        }
        if (rotationSensor != null)
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
    }

    private void stop() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception e) {
                Log.e("LoriMaps", e.toString());
            }
        }
        if (sensorManager != null)
            sensorManager.unregisterListener(this);
        running = false;
    }

    // ------------------------------------------------------------------ position

    @Override
    public void onLocationChanged(@NonNull Location location) {
        long now = SystemClock.elapsedRealtime();
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider()))
            lastGpsFix = now;
        else if (lastGpsFix != 0 && now - lastGpsFix < GPS_PRIORITY_MS)
            return; // GPS is more precise
        lastFix = location;
        view.invalidate();
        if (listener != null)
            listener.onLocation(location);
    }

    @Override public void onProviderEnabled(@NonNull String provider) { }
    @Override public void onProviderDisabled(@NonNull String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    // ------------------------------------------------------------------ drawing

    @Override
    public void draw(Canvas canvas, MapOverlayView v) {
        Location fix = getLastFix();
        if (fix == null || !v.toPixels(fix.getLatitude(), fix.getLongitude(), pixel)) return;
        float x = pixel.x, y = pixel.y;
        float dotRadius = 5f * density;

        float accuracy = fix.hasAccuracy() ? v.metersToPixels(fix.getAccuracy(), fix.getLatitude(), fix.getLongitude()) : 0;
        if (accuracy > dotRadius * 1.5f) {
            canvas.drawCircle(x, y, accuracy, accuracyFill);
            canvas.drawCircle(x, y, accuracy, accuracyStroke);
        }

        float direction = azimuth;
        if (Float.isNaN(direction) && fix.hasBearing() && fix.getSpeed() > 1f)
            direction = fix.getBearing();
        if (!Float.isNaN(direction)) {
            float length = Math.max(30f * density, Math.min(accuracy, 70f * density));
            conePaint.setShader(new RadialGradient(x, y, length,
                    new int[]{Color.argb(200, 230, 20, 20), Color.argb(0, 230, 20, 20)}, null, Shader.TileMode.CLAMP));
            oval.set(x - length, y - length, x + length, y + length);
            // drawArc: 0° = east, clockwise; map north (up) = -90°
            canvas.drawArc(oval, direction - 90f - CONE_HALF_ANGLE, 2 * CONE_HALF_ANGLE, true, conePaint);
            drawnAzimuth = direction;
        }

        canvas.drawCircle(x, y, dotRadius, dotFill);
        canvas.drawCircle(x, y, dotRadius, dotStroke);
    }

    // ------------------------------------------------------------------ compass

    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        int axisX, axisY;
        switch (windowManager.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:  axisX = SensorManager.AXIS_Y;       axisY = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X;       break;
            default:                   axisX = SensorManager.AXIS_X;       axisY = SensorManager.AXIS_Y;       break;
        }
        SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped);
        SensorManager.getOrientation(remapped, orientation);

        Location fix = lastFix;
        if (fix != null && fix != declinationFor) {
            declination = new GeomagneticField((float) fix.getLatitude(), (float) fix.getLongitude(),
                    (float) fix.getAltitude(), fix.getTime()).getDeclination();
            declinationFor = fix;
        }
        float measured = normalize((float) Math.toDegrees(orientation[0]) + declination);
        if (Float.isNaN(azimuth)) {
            azimuth = measured;
        } else {
            float diff = ((measured - azimuth + 540f) % 360f) - 180f;
            azimuth = normalize(azimuth + diff * 0.2f); // smoothing
        }

        long now = SystemClock.uptimeMillis();
        boolean changed = Float.isNaN(drawnAzimuth) || Math.abs(((azimuth - drawnAzimuth + 540f) % 360f) - 180f) > 1.5f;
        if (changed && now - lastInvalidate > 60 && fix != null) {
            lastInvalidate = now;
            view.postInvalidate();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static float normalize(float degrees) {
        degrees %= 360f;
        return degrees < 0 ? degrees + 360f : degrees;
    }
}
