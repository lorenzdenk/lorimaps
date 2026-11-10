package io.github.lorenzdenk.lorimaps;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * The maps and overlays of the app (for now online only), the map menu and the overlay list.
 * What is shown is stored in the settings; the map itself is set through the two callbacks.
 */
public class MapsCatalog {

    private static final String OSM = "<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap-Mitwirkende</a>";

    /** API key for Thunderforest (OpenCycleMap), from the settings; empty = tiles with an "API Key Required" watermark */
    static String thunderforestKey = "";

    public static final MapSource[] MAPS = new MapSource[]{
            MapSource.raster("OpenTopoMap", 0, 17,
                    OSM + ", <a href=\"https://opentopomap.org/about\">SRTM | Kartendarstellung © OpenTopoMap (CC-BY-SA)</a>", R.drawable.ic_map_topo,
                    "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
                    "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
                    "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"),
            MapSource.raster("OpenCycleMap", 0, 22,
                    "<a href=\"https://www.thunderforest.com\">© Thunderforest</a>, " + OSM, R.drawable.ic_map_usual,
                    "https://tile.thunderforest.com/cycle/{z}/{x}/{y}.png"),
            MapSource.raster("CyclOSM", 0, 20,
                    "<a href=\"https://www.cyclosm.org\">© CyclOSM</a>, " + OSM, R.drawable.ic_map_usual,
                    "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
                    "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
                    "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png"),
            MapSource.vector("OpenFreeMap", "https://tiles.openfreemap.org/styles/liberty", R.drawable.ic_map_usual),
    };

    public static final MapSource[] OVERLAYS = new MapSource[]{
            MapSource.raster("WayMarkedTrails Hiking", 0, 18,
                    "<a href=\"https://hiking.waymarkedtrails.org\">© Waymarked Trails (CC-BY-SA)</a>", 0,
                    "https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png"),
            MapSource.raster("WayMarkedTrails Cycling", 0, 18,
                    "<a href=\"https://cycling.waymarkedtrails.org\">© Waymarked Trails (CC-BY-SA)</a>", 0,
                    "https://tile.waymarkedtrails.org/cycling/{z}/{x}/{y}.png"),
    };

    /** key of the map shown */
    private static final String PREF_MAP = "mapKey";
    /** overlays shown at the moment, "|"-separated keys */
    private static final String PREF_OVERLAYS = "overlaysShown";
    /** overlays shown before the last short press switched them off */
    private static final String PREF_LAST_OVERLAYS = "overlaysLast";
    /** order of all overlays in the overlay list, "|"-separated keys; first = drawn on top */
    private static final String PREF_OVERLAY_ORDER = "overlayOrder";

    /** One overlay as it is to be drawn. */
    public static class ShownOverlay {
        public final MapSource source;
        /** 0.1 .. 1 */
        public final float opacity;

        ShownOverlay(MapSource source, float opacity) {
            this.source = source;
            this.opacity = opacity;
        }
    }

    public interface CallbackSetMap {
        void set(MapSource source);
    }

    /** overlays to show, in drawing order (the first one at the bottom) */
    public interface CallbackOverlays {
        void set(List<ShownOverlay> overlays);
    }

    private final SharedPreferences prefs;
    private final CallbackSetMap setMap;
    private final CallbackOverlays setOverlays;
    @Nullable private PinManager pins;
    @Nullable private PinManager.JumpCallback pinJump;

    public MapsCatalog(SharedPreferences prefs, CallbackSetMap setMap, CallbackOverlays setOverlays) {
        this.prefs = prefs;
        this.setMap = setMap;
        this.setOverlays = setOverlays;
    }

    /** Own pins: listed in the map menu (tap = show, pencil = edit), plus "set pin at coordinates". */
    public void setPins(PinManager pins, PinManager.JumpCallback jump) {
        this.pins = pins;
        this.pinJump = jump;
    }

    // ------------------------------------------------------------------ maps

    public MapSource currentMap() {
        String key = prefs.getString(PREF_MAP, "");
        for (MapSource m : MAPS)
            if (m.key().equals(key)) return m;
        return MAPS[0];
    }

    /** Shows the stored map and overlays (start of the app, after the settings). */
    public void load() {
        setMap.set(currentMap());
        applyOverlays();
    }

    private void select(MapSource m) {
        prefs.edit().putString(PREF_MAP, m.key()).apply();
        setMap.set(m);
        applyOverlays();
    }

    private String displayName(MapSource m) {
        String custom = prefs.getString("mapName_" + m.key(), null);
        return custom != null && !custom.trim().isEmpty() ? custom : m.name;
    }

    /** Map chooser: tap a map to show it, pencil to rename it; below it the pins. */
    public void showMapMenu(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout list = new android.widget.LinearLayout(context);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(list);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.dlg_select_map_title)
                .setView(scroll)
                .setNegativeButton(R.string.btn_cancel, null)
                .create();

        MapSource current = currentMap();
        addHeader(list, context.getString(R.string.header_online_maps), dp);
        for (MapSource m : MAPS)
            addMapRow(list, dialog, m, m == current, dp);

        if (pins != null) {
            addHeader(list, context.getString(R.string.header_pins), dp);
            for (PinManager.Pin p : pins.getPins())
                addActionRow(list, p.label(), p.name.isEmpty() ? "" : PinManager.formatCoordinates(p.lat, p.lon), dp,
                        () -> { dialog.dismiss(); if (pinJump != null) pinJump.jump(p.point()); },
                        () -> { dialog.dismiss(); pins.showEditDialog(context, p); });
            addActionRow(list, context.getString(R.string.pin_add_coordinates), context.getString(R.string.pin_add_hint), dp,
                    () -> { dialog.dismiss(); pins.showNewPinDialog(context, null); }, null);
        }

        dialog.show();
    }

    private void addHeader(android.widget.LinearLayout list, String text, float dp) {
        android.widget.TextView header = new android.widget.TextView(list.getContext());
        header.setText(text.toUpperCase(java.util.Locale.getDefault()));
        header.setTextSize(13);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setAlpha(0.6f);
        header.setPadding((int) (24 * dp), (int) (14 * dp), (int) (24 * dp), (int) (4 * dp));
        list.addView(header);
    }

    private void addMapRow(android.widget.LinearLayout list, AlertDialog dialog, MapSource m, boolean selected, float dp) {
        Context context = list.getContext();
        android.widget.LinearLayout row = new android.widget.LinearLayout(context);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) (16 * dp), (int) (6 * dp), (int) (8 * dp), (int) (6 * dp));
        row.setMinimumHeight((int) (56 * dp));
        android.util.TypedValue tv = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        android.widget.RadioButton radio = new android.widget.RadioButton(context);
        radio.setChecked(selected);
        radio.setClickable(false);
        radio.setFocusable(false);
        row.addView(radio);

        android.widget.LinearLayout texts = new android.widget.LinearLayout(context);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        texts.setPadding((int) (8 * dp), 0, (int) (8 * dp), 0);
        android.widget.TextView name = new android.widget.TextView(context);
        name.setText(displayName(m));
        name.setTextSize(18);
        texts.addView(name);
        android.widget.TextView details = new android.widget.TextView(context);
        details.setText(m.details);
        details.setTextSize(13);
        details.setAlpha(0.6f);
        texts.addView(details);
        row.addView(texts, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageButton edit = new android.widget.ImageButton(context);
        edit.setImageResource(R.drawable.ic_edit);
        edit.setContentDescription(context.getString(R.string.dlg_rename_title));
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        edit.setBackgroundResource(tv.resourceId);
        edit.setColorFilter(name.getCurrentTextColor());
        edit.setAlpha(0.7f);
        int pad = (int) (12 * dp);
        edit.setPadding(pad, pad, pad, pad);
        edit.setOnClickListener(v -> {
            dialog.dismiss();
            showRenameDialog(context, m);
        });
        row.addView(edit);

        row.setOnClickListener(v -> {
            dialog.dismiss();
            select(m);
        });
        list.addView(row);
    }

    /** Row without radio button: title, details, optional pencil. */
    private void addActionRow(android.widget.LinearLayout list, String title, String detailText, float dp,
                              Runnable onClick, @Nullable Runnable onEdit) {
        Context context = list.getContext();
        android.widget.LinearLayout row = new android.widget.LinearLayout(context);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) (24 * dp), (int) (6 * dp), (int) (8 * dp), (int) (6 * dp));
        row.setMinimumHeight((int) (52 * dp));
        android.util.TypedValue tv = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        android.widget.LinearLayout texts = new android.widget.LinearLayout(context);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView name = new android.widget.TextView(context);
        name.setText(title);
        name.setTextSize(17);
        texts.addView(name);
        if (!detailText.isEmpty()) {
            android.widget.TextView details = new android.widget.TextView(context);
            details.setText(detailText);
            details.setTextSize(13);
            details.setAlpha(0.6f);
            texts.addView(details);
        }
        row.addView(texts, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (onEdit != null) {
            android.widget.ImageButton edit = new android.widget.ImageButton(context);
            edit.setImageResource(R.drawable.ic_edit);
            edit.setContentDescription(context.getString(R.string.pin_edit_title));
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            edit.setBackgroundResource(tv.resourceId);
            edit.setColorFilter(name.getCurrentTextColor());
            edit.setAlpha(0.7f);
            int pad = (int) (12 * dp);
            edit.setPadding(pad, pad, pad, pad);
            edit.setOnClickListener(v -> onEdit.run());
            row.addView(edit);
        }
        row.setOnClickListener(v -> onClick.run());
        list.addView(row);
    }

    private void showRenameDialog(Context context, MapSource m) {
        float dp = context.getResources().getDisplayMetrics().density;
        android.widget.EditText input = new android.widget.EditText(context);
        input.setSingleLine(true);
        input.setText(displayName(m));
        input.setSelectAllOnFocus(true);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context);
        frame.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), 0);
        frame.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.dlg_rename_title)
                .setView(frame)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty() || text.equals(m.name))
                        prefs.edit().remove("mapName_" + m.key()).apply();
                    else
                        prefs.edit().putString("mapName_" + m.key(), text).apply();
                    showMapMenu(context);
                })
                .setNegativeButton(R.string.btn_cancel, (d, w) -> showMapMenu(context))
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            if (dialog.getWindow() != null)
                dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        });
        dialog.show();
    }

    // ------------------------------------------------------------------ overlays (several at once)

    @Nullable
    private static MapSource overlay(String key) {
        for (MapSource o : OVERLAYS)
            if (o.key().equals(key)) return o;
        return null;
    }

    private static List<String> split(String s) {
        List<String> list = new ArrayList<>();
        for (String k : s.split("\\|"))
            if (!k.isEmpty() && !list.contains(k)) list.add(k);
        return list;
    }

    /** Keys of the overlays shown at the moment. */
    public List<String> shownOverlays() {
        List<String> keys = new ArrayList<>();
        for (String k : split(prefs.getString(PREF_OVERLAYS, "")))
            if (overlay(k) != null) keys.add(k);
        return keys;
    }

    public boolean anyOverlayShown() {
        return !shownOverlays().isEmpty();
    }

    private void setShown(List<String> keys) {
        prefs.edit().putString(PREF_OVERLAYS, android.text.TextUtils.join("|", keys)).apply();
        applyOverlays();
    }

    /** All overlays in the order of the overlay list (first = on top). */
    public List<String> orderedOverlays() {
        List<String> order = new ArrayList<>();
        for (String k : split(prefs.getString(PREF_OVERLAY_ORDER, "")))
            if (overlay(k) != null) order.add(k);
        for (MapSource o : OVERLAYS)
            if (!order.contains(o.key())) order.add(o.key());
        return order;
    }

    /** Moves an overlay one place up (-1) or down (+1) in the list. */
    public void moveOverlay(String key, int direction) {
        List<String> order = orderedOverlays();
        int i = order.indexOf(key), j = i + direction;
        if (i < 0 || j < 0 || j >= order.size()) return;
        order.set(i, order.get(j));
        order.set(j, key);
        prefs.edit().putString(PREF_OVERLAY_ORDER, android.text.TextUtils.join("|", order)).apply();
        applyOverlays();
    }

    /** Opacity of one overlay in percent (10-100). */
    public int overlayOpacity(String key) {
        return Math.max(10, Math.min(100, prefs.getInt("overlayOpacity_" + key, 100)));
    }

    public void setOverlayOpacity(String key, int percent) {
        prefs.edit().putInt("overlayOpacity_" + key, percent).apply();
        applyOverlays();
    }

    /** Hands the shown overlays to the map in drawing order: the bottom one of the list first. */
    public void applyOverlays() {
        List<String> shown = shownOverlays();
        List<String> order = orderedOverlays();
        List<ShownOverlay> result = new ArrayList<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            String k = order.get(i);
            MapSource o = overlay(k);
            if (o != null && shown.contains(k))
                result.add(new ShownOverlay(o, overlayOpacity(k) / 100f));
        }
        setOverlays.set(result);
    }

    /** Overlay button hidden in the settings: overlays off. */
    public void hideOverlays() {
        if (!shownOverlays().isEmpty()) setShown(new ArrayList<>());
    }

    /** Short press on the overlay button: switches all shown overlays off, or the ones shown last on again. */
    public void toggleOverlay() {
        List<String> shown = shownOverlays();
        if (!shown.isEmpty()) {
            prefs.edit().putString(PREF_LAST_OVERLAYS, android.text.TextUtils.join("|", shown)).apply();
            setShown(new ArrayList<>());
            return;
        }
        List<String> last = new ArrayList<>();
        for (String k : split(prefs.getString(PREF_LAST_OVERLAYS, "")))
            if (overlay(k) != null) last.add(k);
        if (last.isEmpty())
            last.add(OVERLAYS[0].key());
        setShown(last);
    }

    /**
     * Long press on the overlay button: all overlays in their order (top of the list = drawn on top),
     * each with a check box (shown), arrows to change the order and a slider for its opacity.
     */
    public void selectOverlay(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout list = new android.widget.LinearLayout(context);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(list);
        fillOverlayList(context, list, dp);
        new AlertDialog.Builder(context)
                .setTitle(R.string.dlg_select_overlay_source_title)
                .setView(scroll)
                .setPositiveButton(R.string.btn_ok, null)
                .show();
    }

    private void fillOverlayList(Context context, android.widget.LinearLayout list, float dp) {
        list.removeAllViews();
        android.widget.TextView hint = new android.widget.TextView(context);
        hint.setText(R.string.overlay_list_hint);
        hint.setTextSize(13);
        hint.setAlpha(0.6f);
        hint.setPadding((int) (24 * dp), (int) (4 * dp), (int) (24 * dp), (int) (8 * dp));
        list.addView(hint);

        List<String> order = orderedOverlays();
        List<String> shown = shownOverlays();
        for (int idx = 0; idx < order.size(); idx++) {
            String key = order.get(idx);
            boolean on = shown.contains(key);

            android.widget.LinearLayout row = new android.widget.LinearLayout(context);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int) (12 * dp), (int) (4 * dp), (int) (4 * dp), 0);

            android.widget.CheckBox check = new android.widget.CheckBox(context);
            check.setChecked(on);
            check.setOnCheckedChangeListener((b, isChecked) -> {
                List<String> now = shownOverlays();
                if (isChecked && !now.contains(key)) now.add(key);
                if (!isChecked) now.remove(key);
                setShown(now);
                list.post(() -> fillOverlayList(context, list, dp)); // after this click is handled
            });
            row.addView(check);

            android.widget.LinearLayout texts = new android.widget.LinearLayout(context);
            texts.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.TextView title = new android.widget.TextView(context);
            title.setText(key.substring(key.indexOf(':') + 1));
            title.setTextSize(16);
            texts.addView(title);
            android.widget.TextView kind = new android.widget.TextView(context);
            kind.setText(R.string.overlay_kind_online);
            kind.setTextSize(12);
            kind.setAlpha(0.6f);
            texts.addView(kind);
            row.addView(texts, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(arrow(context, "▲", idx > 0, dp, () -> { moveOverlay(key, -1); list.post(() -> fillOverlayList(context, list, dp)); }));
            row.addView(arrow(context, "▼", idx < order.size() - 1, dp, () -> { moveOverlay(key, 1); list.post(() -> fillOverlayList(context, list, dp)); }));
            list.addView(row);

            // opacity slider (only for shown overlays, to keep the list short)
            if (on) {
                android.widget.LinearLayout op = new android.widget.LinearLayout(context);
                op.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                op.setGravity(android.view.Gravity.CENTER_VERTICAL);
                op.setPadding((int) (56 * dp), 0, (int) (16 * dp), (int) (6 * dp));
                android.widget.TextView label = new android.widget.TextView(context);
                label.setTextSize(13);
                label.setMinWidth((int) (92 * dp));
                int value = overlayOpacity(key);
                label.setText(context.getString(R.string.overlay_opacity_label, value));
                android.widget.SeekBar bar = new android.widget.SeekBar(context);
                bar.setMax(9); // 10 % .. 100 % in steps of 10
                bar.setProgress(Math.round(value / 10f) - 1);
                bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                        int pct = (p + 1) * 10;
                        label.setText(context.getString(R.string.overlay_opacity_label, pct));
                        if (fromUser) setOverlayOpacity(key, pct); // live on the map
                    }
                    @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                    @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
                });
                op.addView(label);
                op.addView(bar, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                list.addView(op);
            }
        }
    }

    private android.view.View arrow(Context context, String text, boolean enabled, float dp, Runnable action) {
        android.widget.TextView b = new android.widget.TextView(context);
        b.setText(text);
        b.setTextSize(18);
        b.setGravity(android.view.Gravity.CENTER);
        b.setMinWidth((int) (44 * dp));
        b.setMinHeight((int) (44 * dp));
        b.setAlpha(enabled ? 0.85f : 0.2f);
        if (enabled) {
            android.util.TypedValue tv = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            b.setBackgroundResource(tv.resourceId);
            b.setOnClickListener(v -> action.run());
        }
        return b;
    }
}
