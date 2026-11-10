package io.github.lorenzdenk.lorimaps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.UiSettings;
import org.maplibre.android.module.http.HttpRequestUtil;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.Source;
import org.maplibre.android.style.sources.TileSet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.lorenzdenk.lorimaps.databinding.ActivityMainBinding;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/**
 * The map screen. The map is drawn by MapLibre (raster tiles as a raster layer, vector maps from a style);
 * position, pins and ruler are drawn by the app itself in a view over the map (MapOverlayView).
 *
 * Zoom: the app works with "levels" as in OpenStreetMap tile URLs. At a whole level the tiles of that level
 * are shown at exactly the size chosen in the settings, so raster maps are sharp; the map always comes to
 * rest on a whole level (see levelOffset()).
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_LOCATION_DISPLAY = 123;
    private static final int REQUEST_LOCATION_JUMP = 124;

    public static final int ANIMATION_SPEED_FAST = 250;
    public static final int ANIMATION_SPEED_SLOW = 500;

    static final String USER_AGENT = "LoriMaps/" + BuildConfig.VERSION_NAME + " (+https://github.com/lorenzdenk/lorimaps)";

    private ActivityMainBinding binding;
    private MapView mapView;
    /** null until the map is ready */
    @Nullable private MapLibreMap map;
    private MapOverlayView overlayView;

    private TextView tvDistance;
    private ImageButton btnJumpToLocation;
    private ImageButton btnGPS;
    private ImageButton btnChangeSource;
    private ImageButton btnRuler;
    private ImageButton btnOverlay;
    private UserTouchSurface userTouchSurface;
    private OutOfScreenPointer outOfScreenPointer;

    private EnhancedSharedPreferences prefs;
    private MapsCatalog mapsCatalog;
    private PinManager pins;
    private LocationMarker locationMarker;
    private final RulerLayer ruler = new RulerLayer();

    /** map shown at the moment */
    private MapSource currentSource = MapsCatalog.MAPS[0];
    private List<MapsCatalog.ShownOverlay> currentOverlays = new ArrayList<>();
    /** style of the map; null while a new one is loading */
    @Nullable private Style style;

    /** centre the map is to keep while zooming with the buttons (null after the user moved the map) */
    @Nullable private LatLng requiredCenter;
    /** the camera is moved by a finger at the moment */
    private boolean gestureMove;
    /** target of a running zoom button animation, NaN = none */
    private double zoomTarget = Double.NaN;
    private boolean continuousRulerMode;
    private Uri pendingGeoUri;

    // ------------------------------------------------------------------ start

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TopExceptionHandler.retrieveLastErrorReport(this);
        Thread.setDefaultUncaughtExceptionHandler(new TopExceptionHandler(this));

        prefs = EnhancedSharedPreferences.getDefaultSharedPreferences(this);
        MapsCatalog.thunderforestKey = prefs.getString("ThunderforestKey", "").trim();

        MapLibre.getInstance(this);
        // tile servers of OpenStreetMap projects block unknown or generic apps: send a clear app name with
        // version and project page, and do not open many connections at once
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequestsPerHost(6);
        HttpRequestUtil.setOkHttpClient(new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()))
                .build());

        transparentStatusAndNavigation();

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mapView = binding.map;
        overlayView = binding.mapOverlay;
        tvDistance = binding.textLabelsPanel.tvDistance;
        binding.textLabelsPanel.tvSpeed.setVisibility(View.GONE);
        btnJumpToLocation = binding.btnJumpToLocation;
        btnJumpToLocation.setOnClickListener(view -> clickMyLocation());
        btnJumpToLocation.setOnLongClickListener(view -> followMode());
        btnGPS = binding.mainButtons.btnGPS;
        btnGPS.setOnClickListener(view -> switchGPS());
        btnChangeSource = binding.mainButtons.btnChangeSource;
        btnChangeSource.setOnClickListener(view -> mapsCatalog.showMapMenu(this));
        btnChangeSource.setOnLongClickListener(view -> { mapsCatalog.showMapMenu(this); return true; });
        userTouchSurface = binding.userTouchSurface;
        btnRuler = binding.mainButtons.collapsiblePanel.btnRuler;
        btnRuler.setOnClickListener(view -> switchRuler(false));
        btnRuler.setOnLongClickListener(view -> { switchRuler(true); return true; });
        btnOverlay = binding.mainButtons.btnOverlay;
        btnOverlay.setOnClickListener(view -> mapsCatalog.toggleOverlay());
        btnOverlay.setOnLongClickListener(view -> { mapsCatalog.selectOverlay(this); return true; });
        outOfScreenPointer = binding.outOfScreenPointer;
        outOfScreenPointer.setClickToJump(view -> jumpToLocation());
        binding.auxButtons.btnZoomOut.setOnClickListener(view -> changeZoom(-1));
        binding.auxButtons.btnZoomIn.setOnClickListener(view -> changeZoom(+1));
        binding.auxButtons.tvZoom.setText("");
        binding.mainButtons.collapsiblePanel.btnSettings.setOnClickListener(view -> openSettings());
        binding.mainButtons.collapsiblePanel.btnSettings.setOnLongClickListener(view -> {
            collapseAuxPanel(btnRuler.getVisibility() == View.VISIBLE);
            return true;
        });
        binding.mainButtons.collapsiblePanel.btnSearch.setOnClickListener(view -> pins.showSearchDialog(this));
        for (View v : new View[]{binding.debugLayout.tvDebugInfo, binding.debugLayout.placeholder1, binding.debugLayout.tvAltitude})
            v.setVisibility(View.GONE);
        tvDistance.setVisibility(View.GONE);

        // drawn in this order: pins, ruler, own position on top
        pins = new PinManager(prefs, overlayView, this::jump);
        locationMarker = new LocationMarker(overlayView);
        locationMarker.setListener(this::onLocation);
        overlayView.addLayer(pins.getOverlay());
        overlayView.addLayer(ruler);
        overlayView.addLayer(locationMarker);
        binding.scaleBar.setMap(overlayView);

        mapsCatalog = new MapsCatalog(prefs, this::applyMap, this::applyOverlays);
        mapsCatalog.setPins(pins, this::jump);

        userTouchSurface.setCallback(new TwoFingerDrag(getBaseContext(), new TwoFingerDrag.Listener() {
            @Override
            public void onOneFinger(@Nullable MotionEvent event) {
                if (event != null)
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                        case MotionEvent.ACTION_MOVE:
                        case MotionEvent.ACTION_UP:
                            drawRuler(event.getX(), event.getY(), event.getAction());
                            break;
                    }
            }

            @Override
            public void onTwoFingers(MotionEvent event) {
                mapView.dispatchTouchEvent(event);
            }
        }));

        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()))
            pendingGeoUri = intent.getData();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isRulerActivated()) {
                    switchRuler(false);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this::onMapReady);
    }

    private void onMapReady(@NonNull MapLibreMap m) {
        map = m;

        UiSettings ui = m.getUiSettings();
        ui.setRotateGesturesEnabled(false); // north is always up
        ui.setTiltGesturesEnabled(false);
        ui.setCompassEnabled(false);
        ui.setLogoEnabled(false);
        // after zooming with two fingers the map comes to rest on a whole level at once (no run-out)
        ui.setScaleVelocityAnimationEnabled(false);
        // the "i" with the sources of the map: top left, below the status bar
        float dp = getResources().getDisplayMetrics().density;
        ui.setAttributionEnabled(true);
        ui.setAttributionGravity(Gravity.TOP | Gravity.START);
        ui.setAttributionMargins((int) (8 * dp), (int) (40 * dp), 0, 0);
        ViewCompat.setOnApplyWindowInsetsListener(mapView, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            ui.setAttributionMargins((int) (8 * dp), top + (int) (8 * dp), 0, 0);
            return insets;
        });
        ViewCompat.requestApplyInsets(mapView);
        m.setMinZoomPreference(0);

        overlayView.setMap(m);

        m.addOnCameraMoveStartedListener(reason -> {
            gestureMove = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE;
            if (gestureMove) {
                requiredCenter = null;
                zoomTarget = Double.NaN;
                if (locationMarker.isFollowing()) { // moving the map by hand ends "follow position"
                    locationMarker.setFollowing(false);
                    updateLocationButton();
                }
            }
        });
        m.addOnCameraMoveListener(this::onCameraMoved);
        m.addOnCameraIdleListener(() -> {
            onCameraMoved();
            if (gestureMove) { // after zooming with the fingers: come to rest on a whole level
                gestureMove = false;
                double zoom = m.getCameraPosition().zoom;
                double snapped = snap(zoom);
                if (Math.abs(snapped - zoom) > 1e-4)
                    m.animateCamera(CameraUpdateFactory.zoomTo(snapped), 150);
            }
        });

        // tap on a pin: rename, move or delete it; long press on the map: set a pin
        m.addOnMapClickListener(point -> {
            PointF p = m.getProjection().toScreenLocation(point);
            return pins.onTap(p.x, p.y);
        });
        m.addOnMapLongClickListener(point -> {
            PointF p = m.getProjection().toScreenLocation(point);
            pins.onLongPress(p.x, p.y, point);
            return true;
        });

        loadSettings(); // sets the map and with it the zoom grid

        boolean firstStart = !prefs.contains("mapPosX");
        m.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(prefs.getDouble("mapPosX", 48.8583), prefs.getDouble("mapPosY", 2.2944)))
                .zoom(zoomOfLevel(Math.round(prefs.getDouble("mapLevel", 10.0))))
                .build()));
        onCameraMoved();

        if (firstStart)
            jumpToLocation(); // asks for the location permission and then shows the position
        else if (prefs.getBoolean("GPSisOn", true)) // position shown by default
            switchGPS();

        if (pendingGeoUri != null) {
            Uri uri = pendingGeoUri;
            pendingGeoUri = null;
            parseGeoUri(uri);
        }
    }

    private void onCameraMoved() {
        overlayView.invalidate();
        binding.scaleBar.invalidate();
        updateZoomLabel();
        updateOutOfScreenPosition();
    }

    private void loadSettings() {
        binding.scaleBar.setImperial("imperial".equals(prefs.getString("UnitsOfMeasure", "metric")));

        boolean showOverlayButton = prefs.getBoolean("ShowOverlayButton", false);
        btnOverlay.setVisibility(showOverlayButton ? View.VISIBLE : View.GONE);
        btnRuler.setVisibility(prefs.getBoolean("ShowRulerButton", false) ? View.VISIBLE : View.GONE);
        if (!showOverlayButton)
            mapsCatalog.hideOverlays();

        outOfScreenPointer.turnOnOff(prefs.getBoolean("ExperimentalOutOfCenterPointer", false));

        if (prefs.getBoolean("KeepScreenOn", false))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mapsCatalog.load();
    }

    // ------------------------------------------------------------------ life cycle

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
        if (map == null) return;
        if (locationMarker.isEnabled())
            locationMarker.resume();
        else if (prefs.getBoolean("GPSisOn", true) && locationAllowedQuietly())
            switchGPS(); // "show position" is on, but it could not start before (permission or location service was off)
    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
        locationMarker.pause();
        if (map != null) {
            CameraPosition c = map.getCameraPosition();
            if (c.target != null)
                prefs.edit()
                        .putDouble("mapPosX", c.target.getLatitude())
                        .putDouble("mapPosY", c.target.getLongitude())
                        .putDouble("mapLevel", levelOfZoom(c.zoom))
                        .apply();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
    }

    // ------------------------------------------------------------------ map and overlays

    /** map size from the settings ("MapScale" in percent), 1 = normal */
    private float userScale() {
        try { return Integer.parseInt(prefs.getString("MapScale", "100")) / 100f; } catch (Exception e) { return 1f; }
    }

    /**
     * Size at which one raster tile is shown, in MapLibre's units (density independent pixels).
     * 256 px tiles at 100 % are shown at 164 dp: slightly smaller than their pixels on a phone screen,
     * so the map is sharp and shows a larger area (the size the previous app used).
     */
    private int tileDisplaySize(MapSource source) {
        return Math.max(32, Math.round(256f * 0.64f * userScale() * 256f / (source.isVector() ? 256 : source.tilePixels)));
    }

    /**
     * MapLibre zoom minus tile level. MapLibre's zoom 0 shows the world 512 units wide; a raster source
     * with tile size T shows level n at exactly T units per tile at zoom n + log2(T / 512).
     * Vector maps use the same grid, so a level shows the same area on every map.
     */
    private double levelOffset() {
        return Math.log(tileDisplaySize(currentSource) / 512.0) / Math.log(2);
    }

    private double zoomOfLevel(double level) {
        return level + levelOffset();
    }

    private double levelOfZoom(double zoom) {
        return zoom - levelOffset();
    }

    /** nearest zoom at which the map rests (a whole level) */
    private double snap(double zoom) {
        return zoomOfLevel(Math.round(levelOfZoom(zoom)));
    }

    private static String jsonArray(String[] urls, String suffix) {
        JSONArray a = new JSONArray();
        for (String u : urls) a.put(u + suffix);
        return a.toString();
    }

    private static String urlSuffix(MapSource source) {
        boolean thunderforest = source.tileUrls.length > 0 && source.tileUrls[0].contains("thunderforest.com");
        return thunderforest && !MapsCatalog.thunderforestKey.isEmpty() ? "?apikey=" + MapsCatalog.thunderforestKey : "";
    }

    /** MapLibre style with one raster layer for an online raster map */
    private String rasterStyle(MapSource source) {
        try {
            JSONObject src = new JSONObject()
                    .put("type", "raster")
                    .put("tiles", new JSONArray(jsonArray(source.tileUrls, urlSuffix(source))))
                    .put("tileSize", tileDisplaySize(source))
                    .put("minzoom", source.minZoom)
                    .put("maxzoom", source.maxZoom)
                    .put("attribution", source.attribution);
            JSONArray layers = new JSONArray()
                    .put(new JSONObject().put("id", "background").put("type", "background")
                            .put("paint", new JSONObject().put("background-color", "#e6e6e6")))
                    .put(new JSONObject().put("id", "base").put("type", "raster").put("source", "base")
                            .put("paint", new JSONObject().put("raster-fade-duration", 100)));
            return new JSONObject()
                    .put("version", 8)
                    .put("name", source.name)
                    .put("sources", new JSONObject().put("base", src))
                    .put("layers", layers)
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Shows the given map (callback of the map menu, also called after the settings changed). */
    private void applyMap(MapSource source) {
        btnChangeSource.setImageResource(source.icon);
        if (map == null) {
            currentSource = source;
            return;
        }
        // the same level before and after, also when the zoom grid changes (map size, raster / vector)
        double level = Math.round(levelOfZoom(map.getCameraPosition().zoom));
        currentSource = source;
        map.setMaxZoomPreference(zoomOfLevel(22)); // beyond the last level of a map its tiles are enlarged
        map.moveCamera(CameraUpdateFactory.zoomTo(zoomOfLevel(level)));

        style = null;
        Style.Builder builder = source.isVector()
                ? new Style.Builder().fromUri(source.styleUrl)
                : new Style.Builder().fromJson(rasterStyle(source));
        map.setStyle(builder, loaded -> {
            style = loaded;
            addOverlayLayers();
        });
        onCameraMoved();
    }

    /** Shows exactly the given overlays over the map, the first one at the bottom. */
    private void applyOverlays(List<MapsCatalog.ShownOverlay> overlays) {
        currentOverlays = overlays;
        btnOverlay.setImageResource(!overlays.isEmpty() ? R.drawable.ic_overlay_on : R.drawable.ic_overlay_off);
        if (style != null && style.isFullyLoaded())
            addOverlayLayers();
    }

    private void addOverlayLayers() {
        Style s = style;
        if (s == null) return;
        try {
            for (Layer l : s.getLayers())
                if (l.getId().startsWith("overlay-")) s.removeLayer(l);
            for (Source src : s.getSources())
                if (src.getId().startsWith("overlay-")) s.removeSource(src);
            int i = 0;
            for (MapsCatalog.ShownOverlay o : currentOverlays) {
                String id = "overlay-" + (i++);
                String[] urls = new String[o.source.tileUrls.length];
                for (int k = 0; k < urls.length; k++) urls[k] = o.source.tileUrls[k] + urlSuffix(o.source);
                TileSet tiles = new TileSet("2.2.0", urls);
                tiles.setMinZoom(o.source.minZoom);
                tiles.setMaxZoom(o.source.maxZoom);
                tiles.setAttribution(o.source.attribution);
                s.addSource(new RasterSource(id, tiles, tileDisplaySize(o.source)));
                RasterLayer layer = new RasterLayer(id, id);
                layer.setProperties(PropertyFactory.rasterOpacity(o.opacity), PropertyFactory.rasterFadeDuration(100f));
                s.addLayer(layer);
            }
        } catch (Exception e) {
            Log.e("LoriMaps", "overlays: " + e);
        }
    }

    // ------------------------------------------------------------------ zoom

    private void updateZoomLabel() {
        if (map == null) return;
        binding.tvZoomLevel.setText(String.format(Locale.ROOT, "Z %d", Math.round(levelOfZoom(map.getCameraPosition().zoom))));
    }

    /** zoom buttons: one level in or out, around the centre of the map */
    private void changeZoom(int diff) {
        if (map == null) return;
        CameraPosition c = map.getCameraPosition();
        if (requiredCenter == null && c.target != null)
            requiredCenter = c.target; // keep this centre while zooming in and out

        double target;
        if (!Double.isNaN(zoomTarget)) { // pressed again before the animation ended
            target = zoomTarget + diff;
        } else if (Math.abs(snap(c.zoom) - c.zoom) < 1e-3) { // on a resting level
            target = snap(c.zoom) + diff;
        } else { // to the closest resting level in this direction
            double level = levelOfZoom(c.zoom);
            target = zoomOfLevel(diff > 0 ? Math.ceil(level) : Math.floor(level));
        }
        target = Math.max(map.getMinZoomLevel(), Math.min(map.getMaxZoomLevel(), target));
        zoomTarget = target;
        final double t = target;
        map.animateCamera(requiredCenter != null
                        ? CameraUpdateFactory.newLatLngZoom(requiredCenter, t)
                        : CameraUpdateFactory.zoomTo(t),
                ANIMATION_SPEED_FAST, new MapLibreMap.CancelableCallback() {
                    @Override public void onCancel() { }
                    @Override public void onFinish() { if (zoomTarget == t) zoomTarget = Double.NaN; }
                });
    }

    // ------------------------------------------------------------------ moving the map

    private void jump(LatLng target) {
        jump(target, 0);
    }

    /** Moves the map to the point; level 0 = keep the zoom. Far away: directly, without animation. */
    private void jump(LatLng target, double level) {
        if (map == null) return;
        requiredCenter = target;
        zoomTarget = Double.NaN;
        double zoom = level == 0 ? map.getCameraPosition().zoom : zoomOfLevel(Math.round(level));
        PointF p = map.getProjection().toScreenLocation(target);
        float w = mapView.getWidth(), h = mapView.getHeight();
        boolean far = w == 0 || Math.abs(p.x - w / 2) > 4 * w || Math.abs(p.y - h / 2) > 4 * h;
        if (far)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom));
        else
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom), ANIMATION_SPEED_SLOW);
    }

    /** where the map was before the last jump to the GPS position (for jumping back) */
    @Nullable private LatLng returnCenter;
    @Nullable private LatLng jumpTarget;

    /** first tap: to the GPS position; second tap without moving the map: back to where the map was. The zoom level never changes. */
    private void clickMyLocation() {
        if (map == null) return;
        if (returnCenter != null && jumpTarget != null && isNear(jumpTarget)) {
            LatLng back = returnCenter;
            returnCenter = null;
            jumpTarget = null;
            jump(back);
            return;
        }
        returnCenter = map.getCameraPosition().target;
        jumpTarget = null;
        requiredCenter = null;
        jumpToLocation();
        if (requiredCenter != null) // a jump was started
            jumpTarget = requiredCenter;
        else
            returnCenter = null;
    }

    /** map centre (or the running jump's target) within a few pixels of p */
    private boolean isNear(LatLng p) {
        if (map == null) return false;
        LatLng c = requiredCenter != null ? requiredCenter : map.getCameraPosition().target;
        if (c == null) return false;
        PointF a = map.getProjection().toScreenLocation(c);
        PointF b = map.getProjection().toScreenLocation(p);
        float d = getResources().getDisplayMetrics().density * 30;
        return Math.abs(a.x - b.x) < d && Math.abs(a.y - b.y) < d;
    }

    // ------------------------------------------------------------------ position

    private void onLocation(Location location) {
        btnGPS.setImageResource(R.drawable.ic_gps_on);
        if (locationMarker.isFollowing() && map != null) {
            requiredCenter = null;
            map.animateCamera(CameraUpdateFactory.newLatLng(new LatLng(location.getLatitude(), location.getLongitude())), ANIMATION_SPEED_SLOW);
        }
        new Handler(Looper.getMainLooper()).post(this::updateOutOfScreenPosition);
    }

    private void updateLocationButton() {
        btnJumpToLocation.setImageResource(locationMarker.isFollowing() ? R.drawable.ic_follow : R.drawable.ic_my_location);
    }

    private void updateOutOfScreenPosition() {
        outOfScreenPointer.updatePosition(overlayView, locationMarker);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean anyGranted = false; // precise or approximate location
        for (int r : grantResults)
            anyGranted |= r == PackageManager.PERMISSION_GRANTED;
        if (anyGranted) {
            switch (requestCode) {
                case REQUEST_LOCATION_JUMP:
                    jumpToLocation();
                    break;
                case REQUEST_LOCATION_DISPLAY:
                    switchGPS();
                    break;
            }
        } else {
            prefs.edit().putBoolean("GPSisOn", false).apply(); // do not ask again on every start
            boolean permanentlyDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dlg_deny_loc_perm_title)
                    .setMessage(permanentlyDenied ? R.string.dlg_permanent_deny_loc_perm_message : R.string.dlg_deny_loc_perm_message)
                    .setPositiveButton(R.string.btn_ok, null)
                    .show();
        }
    }

    private boolean locationPermitted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationServiceOn() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            return lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean permissionsGrantedAndLocationServiceEnabled(int requestID) {
        if (!locationPermitted()) {
            // Android 12+ ignores a request for FINE location unless COARSE is requested too
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    requestID);
            return false;
        }
        if (!locationServiceOn()) {
            Toast.makeText(getBaseContext(), R.string.toast_location_service_disabled, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /** location permission granted and a location service on (without asking or showing anything) */
    private boolean locationAllowedQuietly() {
        return locationPermitted() && locationServiceOn();
    }

    /** GPS button: position shown or not */
    private void switchGPS() {
        if (!locationMarker.isEnabled()) {
            if (!permissionsGrantedAndLocationServiceEnabled(REQUEST_LOCATION_DISPLAY)) return;
            locationMarker.enable();
            btnGPS.setImageResource(locationMarker.getLastFix() == null ? R.drawable.ic_gps_search : R.drawable.ic_gps_on);
            prefs.edit().putBoolean("GPSisOn", true).apply();
        } else {
            locationMarker.disable();
            btnGPS.setImageResource(R.drawable.ic_gps_off);
            prefs.edit().putBoolean("GPSisOn", false).apply();
        }
        updateLocationButton();
        updateOutOfScreenPosition();
    }

    private void jumpToLocation() {
        Location fix = locationMarker.getLastFix();
        if (fix != null && (fix.getLatitude() != 0 || fix.getLongitude() != 0)) {
            jump(new LatLng(fix.getLatitude(), fix.getLongitude()));
            return;
        }

        if (!permissionsGrantedAndLocationServiceEnabled(REQUEST_LOCATION_JUMP)) return;

        // "show position" is on but the marker is not there (e.g. it could not start with the app): start it now
        if (prefs.getBoolean("GPSisOn", true) && !locationMarker.isEnabled())
            switchGPS();

        boolean jumpPerformed = false;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm != null) {
            List<String> providers = new ArrayList<>(lm.getProviders(true)); // "passive", "gps", "network"
            String best = lm.getBestProvider(new Criteria(), true);
            if (best != null) providers.add(0, best);
            for (String provider : providers) {
                try {
                    @SuppressLint("MissingPermission") Location last = lm.getLastKnownLocation(provider);
                    if (last != null) {
                        jump(new LatLng(last.getLatitude(), last.getLongitude()));
                        jumpPerformed = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (!jumpPerformed)
            Toast.makeText(this, R.string.toast_loc_not_avail2, Toast.LENGTH_SHORT).show();
    }

    /** long press on the position button: the map follows the position, or no longer */
    private boolean followMode() {
        if (locationMarker.isEnabled()) {
            requiredCenter = null;
            locationMarker.setFollowing(!locationMarker.isFollowing());
            updateLocationButton();
            if (locationMarker.isFollowing()) {
                Location fix = locationMarker.getLastFix();
                if (fix != null && map != null)
                    map.animateCamera(CameraUpdateFactory.newLatLng(new LatLng(fix.getLatitude(), fix.getLongitude())), ANIMATION_SPEED_SLOW);
                Toast.makeText(this, R.string.toast_follow_loc_1, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.toast_follow_loc_0, Toast.LENGTH_SHORT).show();
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ settings

    private void openSettings() {
        resultLaunchSettings.launch(new Intent(this, SettingsActivity.class));
    }

    private final ActivityResultLauncher<Intent> resultLaunchSettings = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        // whichever way the settings were left (arrow, back button, back gesture): they are stored already
        MapsCatalog.thunderforestKey = prefs.getString("ThunderforestKey", "").trim();
        if (map == null) return;
        loadSettings(); // also sets the map again: new map size, new key
        if (prefs.getBoolean("GPSisOn", true) != locationMarker.isEnabled())
            switchGPS();
    });

    private void collapseAuxPanel(boolean collapse) {
        btnRuler.setVisibility(collapse ? View.GONE : View.VISIBLE);
        binding.scaleBar.invalidate();
        prefs.edit().putBoolean("ShowRulerButton", !collapse).apply();
    }

    private void transparentStatusAndNavigation() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        Window win = getWindow();
        WindowManager.LayoutParams winParams = win.getAttributes();
        winParams.flags &= ~(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        win.setAttributes(winParams);
        win.setStatusBarColor(ContextCompat.getColor(this, R.color.btnBack));
        win.setNavigationBarColor(ContextCompat.getColor(this, R.color.btnBack));
    }

    // ------------------------------------------------------------------ ruler

    /** line of the ruler with a dot at each point set */
    private class RulerLayer implements MapOverlayView.Layer {
        final List<LatLng> line = new ArrayList<>();
        final List<LatLng> dots = new ArrayList<>();
        boolean active;
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final PointF pixel = new PointF();

        RulerLayer() {
            linePaint.setColor(Color.RED);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            dotPaint.setColor(Color.RED);
            dotEdge.setColor(Color.WHITE);
            dotEdge.setStyle(Paint.Style.STROKE);
        }

        double distance() {
            double d = 0;
            for (int i = 1; i < line.size(); i++)
                d += line.get(i - 1).distanceTo(line.get(i));
            return d;
        }

        @Override
        public void draw(Canvas canvas, MapOverlayView view) {
            if (!active || line.isEmpty()) return;
            float dp = getResources().getDisplayMetrics().density;
            linePaint.setStrokeWidth(3.5f * dp);
            dotEdge.setStrokeWidth(1.5f * dp);
            path.reset();
            boolean first = true;
            for (LatLng p : line) {
                view.toPixels(p.getLatitude(), p.getLongitude(), pixel);
                if (first) path.moveTo(pixel.x, pixel.y); else path.lineTo(pixel.x, pixel.y);
                first = false;
            }
            canvas.drawPath(path, linePaint);
            for (LatLng p : dots) {
                view.toPixels(p.getLatitude(), p.getLongitude(), pixel);
                canvas.drawCircle(pixel.x, pixel.y, 5 * dp, dotPaint);
                canvas.drawCircle(pixel.x, pixel.y, 5 * dp, dotEdge);
            }
        }
    }

    /** ruler on (straight line from a start point, or continuous: along the finger) or off */
    private void switchRuler(boolean continuous) {
        ruler.line.clear();
        ruler.dots.clear();
        if (!isRulerActivated()) {
            ruler.active = true;
            continuousRulerMode = continuous;
            btnRuler.setImageResource(continuous ? R.drawable.ic_ruler_continuous : R.drawable.ic_ruler_on);
            userTouchSurface.setVisibility(View.VISIBLE);
            tvDistance.setText(R.string.rulerStart);
            tvDistance.setVisibility(View.VISIBLE);
        } else {
            ruler.active = false;
            btnRuler.setImageResource(R.drawable.ic_ruler_off);
            userTouchSurface.setVisibility(View.GONE);
            tvDistance.setVisibility(View.GONE);
        }
        overlayView.invalidate();
    }

    private boolean isRulerActivated() {
        return userTouchSurface.getVisibility() != View.GONE;
    }

    private void drawRuler(float x, float y, int action) {
        if (!ruler.active) return;
        LatLng gp = overlayView.fromPixels(x, y);
        if (gp == null) return;

        if (!continuousRulerMode) { // straight line from the first point tapped to this one
            LatLng start = ruler.line.isEmpty() ? gp : ruler.line.get(0);
            boolean isStart = ruler.line.isEmpty();
            ruler.line.clear();
            ruler.line.add(start);
            ruler.line.add(gp);
            ruler.dots.clear();
            ruler.dots.addAll(ruler.line);
            overlayView.invalidate();
            if (isStart || ruler.distance() < 0.5)
                tvDistance.setText(R.string.rulerStartSet);
            else
                tvDistance.setText(Utils.distanceToStr(ruler.distance(), this));
            return;
        }

        // continuous: the line follows the finger, a new point every 1/200 of the screen diagonal
        float dist = 0;
        int n = ruler.line.size();
        if (n >= 2)
            dist = (float) ruler.line.get(n - 2).distanceTo(gp);
        LatLng corner1 = overlayView.fromPixels(0, 0);
        LatLng corner2 = overlayView.fromPixels(overlayView.getWidth(), overlayView.getHeight());
        double screenDiagonal = corner1 != null && corner2 != null ? corner1.distanceTo(corner2) : 0;

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)
            ruler.dots.add(gp);

        if (action == MotionEvent.ACTION_DOWN || n == 1 || dist > screenDiagonal / 200)
            ruler.line.add(gp);
        else if (n > 1)
            ruler.line.set(n - 1, gp);
        overlayView.invalidate();

        tvDistance.setText(Utils.distanceToStr(ruler.distance(), this));
    }

    // ------------------------------------------------------------------ geo: links and place search

    /** Looks up a place name (Nominatim, OpenStreetMap) and moves the map there. */
    private void searchLocation(String name) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(name, "UTF-8"));
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONArray results = new JSONArray(sb.toString());
                if (results.length() == 0) {
                    runOnUiThread(() -> Toast.makeText(this, getString(R.string.toast_place_not_found, name), Toast.LENGTH_LONG).show());
                    return;
                }
                JSONObject o = results.getJSONObject(0);
                LatLng p = new LatLng(o.getDouble("lat"), o.getDouble("lon"));
                runOnUiThread(() -> {
                    jump(p);
                    Toast.makeText(this, getString(R.string.toast_go_to, name), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e("LoriMaps", e.toString());
                runOnUiThread(() -> Toast.makeText(this, R.string.toast_place_search_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    /** geo:lat,lon?z=zoom or geo:0,0?q=place (links from other apps) */
    private void parseGeoUri(@Nullable Uri uri) {
        if (uri == null || !"geo".equals(uri.getScheme()))
            return;
        String schemeSpecific = uri.getSchemeSpecificPart();
        if (schemeSpecific == null)
            return;

        // a name in brackets at the end is not needed
        Matcher nameMatcher = Pattern.compile("[\\+\\s]*\\((.*)\\)[\\+\\s]*$").matcher(schemeSpecific);
        if (nameMatcher.find())
            schemeSpecific = schemeSpecific.substring(0, nameMatcher.start());

        String positionPart = schemeSpecific;
        String queryPart = "";
        int queryStart = schemeSpecific.indexOf('?');
        if (queryStart != -1) {
            positionPart = schemeSpecific.substring(0, queryStart);
            queryPart = schemeSpecific.substring(queryStart + 1);
        }

        double lat = 0, lon = 0;
        Matcher positionMatcher = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?),\\s?([+-]?\\d+(?:\\.\\d+)?)").matcher(positionPart);
        if (positionMatcher.find()) {
            lat = Double.parseDouble(positionMatcher.group(1));
            lon = Double.parseDouble(positionMatcher.group(2));
        }

        int zoom = -1;
        String searchRequest = null;
        for (String param : queryPart.split("&")) {
            int eq = param.indexOf('=');
            if (eq == -1) continue;
            String paramName = param.substring(0, eq);
            String paramValue = param.substring(eq + 1);
            try {
                if ("z".equals(paramName))
                    zoom = (int) Math.round(Double.parseDouble(paramValue));
                else if ("q".equals(paramName))
                    searchRequest = URLDecoder.decode(paramValue, "UTF-8");
            } catch (Exception ignored) {
            }
        }

        if (lat == 0.0 && lon == 0.0 && searchRequest != null) {
            double[] coordinates = CoordinateParser.parse(searchRequest);
            if (coordinates != null)
                jump(new LatLng(coordinates[0], coordinates[1]));
            else
                searchLocation(searchRequest);
            return;
        }

        LatLng target = new LatLng(lat, lon);
        if (zoom != -1)
            jump(target, zoom);
        else
            jump(target);
        Toast.makeText(this, getString(R.string.toast_go_to, PinManager.formatCoordinates(lat, lon)), Toast.LENGTH_LONG).show();
    }
}
