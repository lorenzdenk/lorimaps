package io.github.lorenzdenk.lorimaps;

import org.maplibre.android.geometry.LatLng;
import android.graphics.PointF;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Own pins on the map: set with a long press on the map or by entering coordinates, named, renamed and
 * deleted by tapping them. Stored in the app settings ("pins", JSON).
 */
public class PinManager {

    public static class Pin {
        public double lat, lon;
        public String name;

        Pin(double lat, double lon, String name) {
            this.lat = lat;
            this.lon = lon;
            this.name = name == null ? "" : name;
        }

        public LatLng point() {
            return new LatLng(lat, lon);
        }

        /** name, or the coordinates if it has none */
        public String label() {
            return name.isEmpty() ? formatCoordinates(lat, lon) : name;
        }
    }

    public interface JumpCallback {
        void jump(LatLng point);
    }

    private static final String PREF = "pins";

    private final SharedPreferences prefs;
    private final MapOverlayView map;
    private final JumpCallback jump;
    private final List<Pin> pins = new ArrayList<>();
    private final PinOverlay overlay;

    public PinManager(SharedPreferences prefs, MapOverlayView map, JumpCallback jump) {
        this.prefs = prefs;
        this.map = map;
        this.jump = jump;
        load();
        overlay = new PinOverlay();
    }

    public MapOverlayView.Layer getOverlay() {
        return overlay;
    }

    /** tap on the map: true if a pin or the search point was hit (its dialog is shown) */
    public boolean onTap(float x, float y) {
        return overlay.onTap(x, y);
    }

    /** long press on the map: edit the pin there, or set a new one */
    public void onLongPress(float x, float y, LatLng point) {
        overlay.onLongPress(x, y, point);
    }

    public List<Pin> getPins() {
        return pins;
    }

    private void load() {
        pins.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString(PREF, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                pins.add(new Pin(o.getDouble("lat"), o.getDouble("lon"), o.optString("name", "")));
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Pin p : pins) {
                JSONObject o = new JSONObject();
                o.put("lat", p.lat);
                o.put("lon", p.lon);
                o.put("name", p.name);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(PREF, a.toString()).apply();
        map.invalidate();
    }

    // ------------------------------------------------------------------ dialogs

    /** New pin at the given point (long press) or, with null, at entered coordinates. */
    public void showNewPinDialog(Context context, @Nullable LatLng at) {
        showDialog(context, null, at);
    }

    /** Rename, move (coordinates) or delete a pin. */
    public void showEditDialog(Context context, Pin pin) {
        showDialog(context, pin, null);
    }

    private void showDialog(Context context, @Nullable Pin pin, @Nullable LatLng at) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (22 * dp), (int) (8 * dp), (int) (22 * dp), 0);

        EditText name = new EditText(context);
        name.setSingleLine(true);
        name.setHint(R.string.pin_name_hint);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (pin != null) name.setText(pin.name);
        box.addView(name);

        TextView coordLabel = new TextView(context);
        coordLabel.setText(R.string.pin_coordinates_label);
        coordLabel.setAlpha(0.7f);
        coordLabel.setPadding(0, (int) (12 * dp), 0, 0);
        box.addView(coordLabel);

        EditText coords = new EditText(context);
        coords.setSingleLine(true);
        coords.setHint(R.string.pin_coordinates_hint);
        coords.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (pin != null) coords.setText(formatCoordinates(pin.lat, pin.lon));
        else if (at != null) coords.setText(formatCoordinates(at.getLatitude(), at.getLongitude()));
        box.addView(coords);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(pin == null ? R.string.pin_new_title : R.string.pin_edit_title)
                .setView(box)
                .setPositiveButton(R.string.btn_ok, null)
                .setNegativeButton(R.string.btn_cancel, null);
        if (pin != null)
            builder.setNeutralButton(R.string.btn_delete, (d, w) -> {
                pins.remove(pin);
                save();
            });
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            (pin == null && at == null ? coords : name).requestFocus();
            if (dialog.getWindow() != null)
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            // checks the coordinates before closing
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                LatLng p = parseCoordinates(coords.getText().toString());
                if (p == null) {
                    Toast.makeText(context, R.string.pin_coordinates_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                String n = name.getText().toString().trim();
                boolean moved;
                if (pin == null) {
                    pins.add(new Pin(p.getLatitude(), p.getLongitude(), n));
                    moved = at == null;
                } else {
                    moved = Math.abs(pin.lat - p.getLatitude()) > 1e-7 || Math.abs(pin.lon - p.getLongitude()) > 1e-7;
                    pin.lat = p.getLatitude();
                    pin.lon = p.getLongitude();
                    pin.name = n;
                }
                save();
                dialog.dismiss();
                if (moved) jump.jump(p); // entered coordinates: show the pin
            });
        });
        dialog.show();
    }

    // ------------------------------------------------------------------ coordinates

    public static String formatCoordinates(double lat, double lon) {
        return String.format(Locale.ROOT, "%.6f, %.6f", lat, lon);
    }


    /** Coordinates in (almost) any way of writing, see {@link CoordinateParser}. Null if not readable. */
    @Nullable
    public static LatLng parseCoordinates(String text) {
        double[] p = CoordinateParser.parse(text);
        return p == null ? null : new LatLng(p[0], p[1]);
    }

    // ------------------------------------------------------------------ coordinate search

    /** point of the last coordinate search (blue target), null = none */
    @Nullable private LatLng searchPoint;

    /** Search button (magnifier): enter or paste coordinates, the map jumps there and marks the point. */
    public void showSearchDialog(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (22 * dp), (int) (8 * dp), (int) (22 * dp), 0);

        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(R.string.search_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(input);

        TextView result = new TextView(context);
        result.setPadding(0, (int) (8 * dp), 0, 0);
        result.setAlpha(0.75f);
        box.addView(result);

        TextView help = new TextView(context);
        help.setText(R.string.search_formats);
        help.setTextSize(12);
        help.setAlpha(0.55f);
        help.setPadding(0, (int) (10 * dp), 0, 0);
        box.addView(help);

        // copied coordinates are taken over right away
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
                if (clip != null && clip.length() < 300 && parseCoordinates(clip.toString()) != null)
                    input.setText(clip.toString().trim());
            }
        } catch (Exception ignored) {
        }

        Runnable preview = () -> {
            LatLng p = parseCoordinates(input.getText().toString());
            result.setText(input.getText().length() == 0 ? ""
                    : p == null ? context.getString(R.string.search_not_readable)
                    : "→ " + formatCoordinates(p.getLatitude(), p.getLongitude()));
        };
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) { preview.run(); }
        });
        preview.run();

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.search_title)
                .setView(box)
                .setPositiveButton(R.string.search_go, null)
                .setNegativeButton(R.string.btn_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            input.selectAll();
            if (dialog.getWindow() != null)
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                LatLng p = parseCoordinates(input.getText().toString());
                if (p == null) {
                    Toast.makeText(context, R.string.search_not_readable, Toast.LENGTH_LONG).show();
                    return;
                }
                searchPoint = p;
                map.invalidate();
                dialog.dismiss();
                jump.jump(p);
            });
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        dialog.show();
    }

    /** Tap on the search point: keep it as a pin, or remove it. */
    private void showSearchPointDialog(Context context) {
        if (searchPoint == null) return;
        LatLng p = searchPoint;
        new AlertDialog.Builder(context)
                .setTitle(R.string.search_point_title)
                .setMessage(formatCoordinates(p.getLatitude(), p.getLongitude()))
                .setPositiveButton(R.string.search_save_pin, (d, w) -> {
                    searchPoint = null;
                    showNewPinDialog(context, p);
                    map.invalidate();
                })
                .setNeutralButton(R.string.search_remove, (d, w) -> {
                    searchPoint = null;
                    map.invalidate();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ overlay

    private class PinOverlay implements MapOverlayView.Layer {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint searchRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint searchHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint searchDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final PointF pixel = new PointF();
        private final Path path = new Path();
        private final float dp;

        PinOverlay() {
            dp = map.getContext().getResources().getDisplayMetrics().density;
            fill.setColor(Color.rgb(220, 30, 60));
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.5f * dp);
            inner.setColor(Color.WHITE);
            text.setColor(Color.rgb(150, 0, 30));
            text.setTextSize(14 * dp);
            text.setFakeBoldText(true);
            halo.set(text);
            halo.setColor(Color.WHITE);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(3.5f * dp);
            searchRing.setColor(Color.rgb(20, 90, 220));
            searchRing.setStyle(Paint.Style.STROKE);
            searchRing.setStrokeWidth(2.5f * dp);
            searchRing.setStrokeCap(Paint.Cap.ROUND);
            searchHalo.setColor(Color.argb(200, 255, 255, 255));
            searchHalo.setStyle(Paint.Style.STROKE);
            searchHalo.setStrokeWidth(5.5f * dp);
            searchDot.setColor(Color.rgb(20, 90, 220));
        }

        @Override
        public void draw(Canvas c, MapOverlayView pj) {
            if (searchPoint != null) { // blue target with a white edge
                pj.toPixels(searchPoint.getLatitude(), searchPoint.getLongitude(), pixel);
                float x = pixel.x, y = pixel.y;
                c.drawCircle(x, y, 11 * dp, searchHalo);
                c.drawCircle(x, y, 11 * dp, searchRing);
                c.drawLine(x - 17 * dp, y, x - 5 * dp, y, searchRing);
                c.drawLine(x + 5 * dp, y, x + 17 * dp, y, searchRing);
                c.drawLine(x, y - 17 * dp, x, y - 5 * dp, searchRing);
                c.drawLine(x, y + 5 * dp, x, y + 17 * dp, searchRing);
                c.drawCircle(x, y, 2.5f * dp, searchDot);
            }
            float r = 8 * dp, h = 24 * dp;
            for (Pin p : pins) {
                pj.toPixels(p.lat, p.lon, pixel);
                float x = pixel.x, y = pixel.y, cy = y - h + r;
                path.reset();
                path.moveTo(x, y);
                path.lineTo(x - r * 0.75f, cy + r * 0.6f);
                path.addCircle(x, cy, r, Path.Direction.CW);
                path.moveTo(x, y);
                path.lineTo(x + r * 0.75f, cy + r * 0.6f);
                path.lineTo(x - r * 0.75f, cy + r * 0.6f);
                path.close();
                c.drawPath(path, fill);
                c.drawCircle(x, cy, r, stroke);
                c.drawCircle(x, cy, r * 0.38f, inner);
                if (!p.name.isEmpty()) {
                    float tx = x + r + 3 * dp, ty = cy + text.getTextSize() * 0.35f;
                    c.drawText(p.name, tx, ty, halo);
                    c.drawText(p.name, tx, ty, text);
                }
            }
        }

        @Nullable
        private Pin hit(float ex, float ey) {
            float best = 26 * dp;
            Pin found = null;
            for (Pin p : pins) {
                if (!map.toPixels(p.lat, p.lon, pixel)) continue;
                float dx = ex - pixel.x, dy = ey - (pixel.y - 14 * dp);
                float d = (float) Math.hypot(dx, dy);
                if (d < best) {
                    best = d;
                    found = p;
                }
            }
            return found;
        }

        boolean onTap(float ex, float ey) {
            Pin p = hit(ex, ey);
            if (p == null && searchPoint != null
                    && map.toPixels(searchPoint.getLatitude(), searchPoint.getLongitude(), pixel)
                    && Math.hypot(ex - pixel.x, ey - pixel.y) < 22 * dp) {
                showSearchPointDialog(map.getContext());
                return true;
            }
            if (p == null) return false;
            showEditDialog(map.getContext(), p);
            return true;
        }

        void onLongPress(float ex, float ey, LatLng point) {
            Pin p = hit(ex, ey);
            if (p != null)
                showEditDialog(map.getContext(), p);
            else
                showNewPinDialog(map.getContext(), point);
        }
    }
}
