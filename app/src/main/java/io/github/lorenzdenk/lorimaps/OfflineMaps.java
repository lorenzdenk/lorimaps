package io.github.lorenzdenk.lorimaps;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Maps on the device, in the folder Android/data/&lt;app&gt;/files/maps:
 *
 *  - Vector maps of lorimaps-vector-render: a set of files with the same name
 *      &lt;name&gt;-vector.mbtiles     vector tiles (required)
 *      &lt;name&gt;-style.zip         style package: style.json, sprite, glyphs (required; unpacked to &lt;name&gt;-style/)
 *      &lt;name&gt;-hillshade.mbtiles, &lt;name&gt;-relief.mbtiles   hillshade and relief colours (optional)
 *    The style refers to its data as lorimaps://…; the app points these addresses to the files
 *    (mbtiles:// and file:// are read by MapLibre itself, without network).
 *  - Raster MBTiles files (e.g. of lorimaps-render): base maps, or overlays if their metadata says type = overlay.
 *    A map split into several files ("name-part1of6.mbtiles", …) is shown as one.
 *
 * Maps can be imported from any file (document picker), copied by USB into the folder, or downloaded from
 * the releases of lorimaps-vector-render (Android's download manager; also files split into ".partNN").
 */
public class OfflineMaps {
    private static final String TAG = "LoriMaps";
    static final String RELEASES_URL = "https://api.github.com/repos/lorenzdenk/lorimaps-vector-render/releases?per_page=30";
    private static final String PREF_DOWNLOADS = "offlineDownloads";

    /** files of a vector map: group 1 = name, group 2 = kind */
    private static final Pattern VECTOR_FILE = Pattern.compile("^(.+)-(vector|hillshade|relief)\\.mbtiles$");
    /** raster map split into several files */
    private static final Pattern PART = Pattern.compile("^(.*)-part(\\d+)of(\\d+)\\.mbtiles$", Pattern.CASE_INSENSITIVE);
    /** release asset: name, kind, optional ".partNN" (files over 2 GB are split by the workflow) */
    private static final Pattern ASSET = Pattern.compile("^(.+)-(vector\\.mbtiles|hillshade\\.mbtiles|relief\\.mbtiles|style\\.zip)(?:\\.part(\\d+))?$");

    private final Context context;
    private final SharedPreferences prefs;
    public final File dir;
    private final List<MapSource> maps = new ArrayList<>();
    private final List<MapSource> overlays = new ArrayList<>();
    /** raster maps in several files: map key -> all files */
    private final Map<String, List<File>> partFiles = new HashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public OfflineMaps(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        File d = context.getExternalFilesDir("maps");
        if (d == null) // external storage not available
            d = new File(context.getFilesDir(), "maps");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        dir = d;
    }

    public synchronized List<MapSource> maps() {
        return new ArrayList<>(maps);
    }

    public synchronized List<MapSource> overlays() {
        return new ArrayList<>(overlays);
    }

    /** all files of a raster map (several for a map split into parts) */
    public synchronized List<File> files(MapSource s) {
        List<File> l = partFiles.get(s.key());
        return l != null ? l : s.file != null ? Arrays.asList(s.file) : new ArrayList<>();
    }

    // ------------------------------------------------------------------ reading the folder

    /** Reads the maps folder (style packages not unpacked yet are unpacked: a few MB). */
    public synchronized void scan() {
        maps.clear();
        overlays.clear();
        partFiles.clear();
        File[] files = dir.listFiles(File::isFile);
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        for (File f : files)
            if (f.getName().endsWith("-style.zip"))
                try {
                    unpackStyle(f);
                } catch (Exception e) {
                    Log.e(TAG, "style package " + f + ": " + e);
                }

        Set<String> partGroups = new HashSet<>();
        for (File f : files) {
            String name = f.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".mbtiles")) continue;
            Matcher v = VECTOR_FILE.matcher(name);
            if (v.matches()) {
                if (!"vector".equals(v.group(2))) continue; // hillshade and relief belong to the vector map
                String base = v.group(1);
                File style = new File(dir, base + "-style");
                if (!new File(style, "style.json").isFile()) {
                    Log.w(TAG, "vector map " + name + " without style package " + base + "-style.zip");
                    continue;
                }
                Map<String, String> meta = metadata(f);
                String title = nonEmpty(meta.get("name"), base);
                long size = 0;
                for (String kind : new String[]{"vector", "hillshade", "relief"})
                    size += new File(dir, base + "-" + kind + ".mbtiles").length();
                maps.add(MapSource.offlineVector(title,
                        context.getString(R.string.offline_details_vector, Formatter.formatShortFileSize(context, size)), f, style));
                continue;
            }
            Map<String, String> meta = metadata(f);
            if (meta == null) continue; // not an MBTiles file
            String format = nonEmpty(meta.get("format"), "png").toLowerCase(Locale.ROOT);
            if (format.equals("pbf") || format.equals("mvt") || format.equals("mlt")) {
                Log.w(TAG, "vector tiles without style: " + name);
                continue;
            }
            Matcher p = PART.matcher(name);
            List<File> group = new ArrayList<>();
            String title;
            if (p.matches()) {
                String key = (p.group(1) + "|" + p.group(3)).toLowerCase(Locale.ROOT);
                if (!partGroups.add(key)) continue; // listed once, by its first file
                for (File o : files) {
                    Matcher q = PART.matcher(o.getName());
                    if (q.matches() && key.equals((q.group(1) + "|" + q.group(3)).toLowerCase(Locale.ROOT))) group.add(o);
                }
                title = nonEmpty(meta.get("name"), p.group(1));
            } else {
                group.add(f);
                title = nonEmpty(meta.get("name"), name.substring(0, name.length() - ".mbtiles".length()));
            }
            long size = 0;
            for (File o : group) size += o.length();
            String details = context.getString(R.string.offline_details_raster, Formatter.formatShortFileSize(context, size))
                    + (group.size() > 1 ? ", " + group.size() + (p.matches() ? "/" + p.group(3) : "") + " Teile" : "");
            MapSource s = MapSource.offlineRaster(title, details, f, toInt(meta.get("minzoom"), 0), toInt(meta.get("maxzoom"), 22),
                    256, nonEmpty(meta.get("attribution"), ""));
            if (group.size() > 1) partFiles.put(s.key(), group);
            ("overlay".equalsIgnoreCase(nonEmpty(meta.get("type"), "").trim()) ? overlays : maps).add(s);
        }
    }

    /** metadata table of an MBTiles file; null if it is no SQLite file with a tiles table */
    @Nullable
    static Map<String, String> metadata(File f) {
        Map<String, String> out = new HashMap<>();
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            try (Cursor c = db.rawQuery("SELECT name, value FROM metadata", null)) {
                while (c.moveToNext())
                    if (c.getString(0) != null) out.put(c.getString(0).toLowerCase(Locale.ROOT), c.getString(1));
            } catch (Exception ignored) {
                // no metadata table: MapLibre still reads the tiles
            }
            try (Cursor c = db.rawQuery("SELECT 1 FROM tiles LIMIT 1", null)) {
                c.moveToFirst();
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "not an MBTiles file: " + f + ": " + e);
            return null;
        }
    }

    /** west, south, east, north of the map, or null */
    @Nullable
    public double[] bounds(MapSource s) {
        if (s.file == null) return null;
        Map<String, String> meta = metadata(s.file);
        if (meta == null || meta.get("bounds") == null) return null;
        try {
            String[] p = meta.get("bounds").split(",");
            double[] b = new double[4];
            for (int i = 0; i < 4; i++) b[i] = Double.parseDouble(p[i].trim());
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ vector maps: style

    /**
     * The MapLibre style of a vector map, with the placeholders lorimaps://… pointing to the files on this
     * device. Written next to the style (style-device.json) and only made again when the files change.
     */
    public File deviceStyle(MapSource s) throws Exception {
        if (s.styleDir == null || s.file == null) throw new IllegalArgumentException("no vector map");
        File src = new File(s.styleDir, "style.json");
        File out = new File(s.styleDir, "style-device.json");
        File sig = new File(s.styleDir, "style-device.txt");
        String base = s.file.getName().substring(0, s.file.getName().length() - "-vector.mbtiles".length());
        StringBuilder state = new StringBuilder(src.lastModified() + ":" + src.length() + ":" + s.file.getAbsolutePath());
        for (String kind : new String[]{"hillshade", "relief"})
            state.append(':').append(new File(dir, base + "-" + kind + ".mbtiles").isFile());
        if (out.isFile() && sig.isFile() && state.toString().equals(readText(sig)))
            return out;

        JSONObject style = new JSONObject(readText(src));
        JSONObject sources = style.getJSONObject("sources");
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = sources.keys(); it.hasNext(); ) keys.add(it.next());
        Set<String> dropped = new HashSet<>();
        for (String key : keys) {
            JSONObject so = sources.getJSONObject(key);
            JSONArray tiles = so.optJSONArray("tiles");
            String t = tiles != null && tiles.length() > 0 ? tiles.getString(0) : "";
            if (!t.startsWith("lorimaps://")) continue;
            String what = t.substring("lorimaps://".length());
            what = what.contains("/") ? what.substring(0, what.indexOf('/')) : what;
            File f = "map".equals(what) ? s.file : new File(dir, base + "-" + what + ".mbtiles");
            if (f.isFile()) {
                so.remove("tiles");
                so.put("url", "mbtiles://" + f.getAbsolutePath());
            } else { // e.g. without hillshade: the map is drawn without it
                sources.remove(key);
                dropped.add(key);
            }
        }
        if (!dropped.isEmpty()) {
            JSONArray layers = style.getJSONArray("layers"), kept = new JSONArray();
            for (int i = 0; i < layers.length(); i++)
                if (!dropped.contains(layers.getJSONObject(i).optString("source")))
                    kept.put(layers.getJSONObject(i));
            style.put("layers", kept);
        }
        String sd = s.styleDir.getAbsolutePath();
        if (style.optString("sprite").startsWith("lorimaps://"))
            style.put("sprite", "file://" + sd + "/sprite");
        if (style.optString("glyphs").startsWith("lorimaps://"))
            style.put("glyphs", "file://" + sd + "/glyphs/{fontstack}/{range}.pbf");
        writeText(out, style.toString());
        writeText(sig, state.toString());
        return out;
    }

    /** Unpacks &lt;name&gt;-style.zip into &lt;name&gt;-style/ (again when the zip file changed). */
    private void unpackStyle(File zip) throws IOException {
        String base = zip.getName().substring(0, zip.getName().length() - ".zip".length());
        File target = new File(dir, base);
        File stamp = new File(target, ".unpacked");
        String state = zip.length() + ":" + zip.lastModified();
        if (stamp.isFile() && state.equals(readText(stamp)) && new File(target, "style.json").isFile())
            return;
        File tmp = new File(dir, "." + base + ".unpacking");
        deleteRecursive(tmp);
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        String root = tmp.getCanonicalPath() + File.separator;
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = in.getNextEntry()) != null) {
                File out = new File(tmp, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("bad zip entry " + e.getName());
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
        // a package zipped with its folder: use the folder that holds style.json
        File content = tmp;
        if (!new File(tmp, "style.json").isFile()) {
            File[] sub = tmp.listFiles(File::isDirectory);
            if (sub != null && sub.length == 1 && new File(sub[0], "style.json").isFile()) content = sub[0];
            else {
                deleteRecursive(tmp);
                throw new IOException(context.getString(R.string.offline_no_style_json, zip.getName()));
            }
        }
        writeText(new File(content, ".unpacked"), state);
        deleteRecursive(target);
        if (!content.renameTo(target)) throw new IOException("cannot rename " + content);
        deleteRecursive(tmp);
    }

    // ------------------------------------------------------------------ import

    public interface ImportListener {
        void onProgress(long copied, long total); // total < 0 if unknown
        /** names of the files imported (empty if none), error message or null */
        void onDone(List<String> names, @Nullable String error);
    }

    /** Copies the picked files into the maps folder, on a background thread; callbacks on the main thread. */
    public void importFiles(List<Uri> uris, AtomicBoolean cancel, ImportListener listener) {
        WORKER.execute(() -> {
            List<String> names = new ArrayList<>();
            String error = null;
            ContentResolver cr = context.getContentResolver();
            for (Uri uri : uris) {
                if (cancel.get()) break;
                File part = new File(dir, ".import.part");
                try {
                    String name = null;
                    long size = -1;
                    try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            if (!c.isNull(0)) name = c.getString(0);
                            if (!c.isNull(1)) size = c.getLong(1);
                        }
                    }
                    if (name == null) name = uri.getLastPathSegment();
                    if (name == null) name = "offline-map";
                    // file names as stored by browsers for a second download ("x-vector (1).mbtiles", "x-style-1.zip")
                    name = name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s*\\(\\d+\\)(?=\\.[A-Za-z]+$)", "")
                            .replaceAll("-style-\\d+\\.zip$", "-style.zip");
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (size > 0 && dir.getUsableSpace() < size + 10L * 1024 * 1024) {
                        error = context.getString(R.string.toast_offline_no_space, Formatter.formatShortFileSize(context, size));
                        continue;
                    }
                    final long total = size;
                    long copied = 0, lastReport = 0;
                    try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(part)) {
                        if (in == null) throw new IOException("cannot open file");
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (cancel.get()) throw new InterruptedException();
                            out.write(buf, 0, n);
                            copied += n;
                            long now = System.currentTimeMillis();
                            if (now - lastReport > 200) {
                                lastReport = now;
                                final long c = copied;
                                MAIN.post(() -> listener.onProgress(c, total));
                            }
                        }
                    }
                    if (lower.endsWith(".zip")) {
                        if (!lower.endsWith("-style.zip")) throw new IOException(context.getString(R.string.offline_bad_zip_name, name));
                    } else {
                        if (!lower.endsWith(".mbtiles")) {
                            if (!isSQLite(part)) throw new IOException(context.getString(R.string.toast_offline_unsupported));
                            name = name + ".mbtiles";
                        }
                        checkDatabase(part); // incomplete downloads are common with large files
                    }
                    File target = new File(dir, name);
                    //noinspection ResultOfMethodCallIgnored
                    target.delete(); // a newer version of the same file replaces the old one
                    if (!part.renameTo(target)) throw new IOException("cannot rename imported file");
                    if (name.endsWith("-style.zip")) {
                        try {
                            unpackStyle(target);
                        } catch (IOException e) {
                            //noinspection ResultOfMethodCallIgnored
                            target.delete();
                            throw e;
                        }
                    }
                    names.add(name);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    error = context.getString(R.string.toast_offline_import_failed, e.getMessage());
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
            }
            scan();
            final String err = error;
            MAIN.post(() -> listener.onDone(names, err));
        });
    }

    /** Throws if the SQLite file is damaged or incomplete (quick integrity check plus one tile read). */
    private static void checkDatabase(File f) throws IOException {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            try (Cursor c = db.rawQuery("PRAGMA quick_check", null)) {
                if (!c.moveToFirst() || !"ok".equalsIgnoreCase(c.getString(0)))
                    throw new IOException("Datei ist beschädigt oder unvollständig – bitte nochmals herunterladen");
            }
            try (Cursor c = db.rawQuery("SELECT count(*) FROM tiles", null)) {
                if (!c.moveToFirst() || c.getLong(0) == 0)
                    throw new IOException("Die Datei enthält keine Kacheln");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Datei ist beschädigt oder unvollständig – bitte nochmals herunterladen (" + e.getMessage() + ")");
        }
    }

    private static boolean isSQLite(File f) {
        byte[] header = new byte[16];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(header) != 16) return false;
        } catch (IOException e) {
            return false;
        }
        return new String(header, 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3");
    }

    // ------------------------------------------------------------------ delete

    /** Deletes all files of the map. */
    public void delete(MapSource s) {
        if (s.styleDir != null && s.file != null) {
            String base = s.file.getName().substring(0, s.file.getName().length() - "-vector.mbtiles".length());
            for (String suffix : new String[]{"-vector.mbtiles", "-hillshade.mbtiles", "-relief.mbtiles", "-style.zip"})
                //noinspection ResultOfMethodCallIgnored
                new File(dir, base + suffix).delete();
            deleteRecursive(s.styleDir);
        } else {
            for (File f : files(s))
                //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
        scan();
    }

    // ------------------------------------------------------------------ download from GitHub

    public static class Asset {
        public final String name, url;
        public final long size;

        Asset(String name, String url, long size) {
            this.name = name; this.url = url; this.size = size;
        }
    }

    /** One map of a release: all its files. */
    public static class Region {
        public final String name, release;
        public final List<Asset> assets = new ArrayList<>();
        public long size;

        Region(String name, String release) {
            this.name = name; this.release = release;
        }

        /** the vector tiles and the style package are there (hillshade and relief are optional) */
        boolean complete() {
            boolean vector = false, style = false;
            for (Asset a : assets) {
                vector |= a.name.startsWith(name + "-vector.mbtiles");
                style |= a.name.startsWith(name + "-style.zip");
            }
            return vector && style;
        }
    }

    /** The maps of the newest release of lorimaps-vector-render with a complete vector map. Network: not on the main thread. */
    public List<Region> fetchRegions() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
        c.setRequestProperty("User-Agent", MainActivity.USER_AGENT);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        String text;
        try (InputStream in = c.getInputStream()) {
            text = new String(readAll(in), StandardCharsets.UTF_8);
        }
        JSONArray releases = new JSONArray(text); // newest first
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.getJSONObject(i);
            if (r.optBoolean("draft") || !r.optString("tag_name").startsWith("vector-")) continue;
            Map<String, Region> regions = new LinkedHashMap<>();
            JSONArray assets = r.getJSONArray("assets");
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.getJSONObject(j);
                Matcher m = ASSET.matcher(a.getString("name"));
                if (!m.matches()) continue;
                Region region = regions.get(m.group(1));
                if (region == null) regions.put(m.group(1), region = new Region(m.group(1), r.optString("tag_name")));
                region.assets.add(new Asset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size")));
                region.size += a.optLong("size");
            }
            List<Region> out = new ArrayList<>();
            for (Region region : regions.values())
                if (region.complete()) out.add(region);
            if (!out.isEmpty()) return out;
        }
        return new ArrayList<>();
    }

    /** Starts the download of all files of the map (Android's download manager: runs in the background). */
    public void download(Region region) throws Exception {
        if (context.getExternalFilesDir("maps") == null) throw new IOException("kein externer Speicher");
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dir.getUsableSpace() < region.size * 2 + 10L * 1024 * 1024 && hasParts(region)
                || dir.getUsableSpace() < region.size + 10L * 1024 * 1024)
            throw new IOException(context.getString(R.string.toast_offline_no_space, Formatter.formatShortFileSize(context, region.size)));
        synchronized (this) {
            JSONArray pending = pending();
            for (Asset a : region.assets) {
                File dest = new File(new File(dir, ".download"), a.name);
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                DownloadManager.Request q = new DownloadManager.Request(Uri.parse(a.url))
                        .setTitle(region.name + ": " + a.name)
                        .setDescription(context.getString(R.string.app_name))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(context, "maps", ".download/" + a.name)
                        .addRequestHeader("User-Agent", MainActivity.USER_AGENT);
                long id = dm.enqueue(q);
                pending.put(new JSONObject().put("id", id).put("name", a.name).put("region", region.name));
            }
            prefs.edit().putString(PREF_DOWNLOADS, pending.toString()).apply();
        }
    }

    private static boolean hasParts(Region region) {
        for (Asset a : region.assets) if (a.name.matches(".*\\.part\\d+$")) return true;
        return false;
    }

    public synchronized boolean downloading() {
        return pending().length() > 0;
    }

    private JSONArray pending() {
        try {
            return new JSONArray(prefs.getString(PREF_DOWNLOADS, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public interface DownloadListener {
        /** maps that are now complete, and errors (both may be empty) */
        void onChanged(List<String> ready, List<String> errors);
    }

    /**
     * Looks at the downloads started by download(): files whose parts are all there are put together and moved
     * into the maps folder, style packages unpacked. Background thread; callback on the main thread if anything changed.
     */
    public void checkDownloads(DownloadListener listener) {
        WORKER.execute(() -> {
            List<String> ready = new ArrayList<>(), errors = new ArrayList<>();
            synchronized (this) {
                JSONArray pending = pending();
                if (pending.length() == 0) return;
                DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                // file name in the maps folder -> its downloads
                Map<String, List<JSONObject>> byTarget = new LinkedHashMap<>();
                Set<String> failedRegions = new HashSet<>();
                try {
                    for (int i = 0; i < pending.length(); i++) {
                        JSONObject p = pending.getJSONObject(i);
                        int status = -1;
                        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(p.getLong("id")))) {
                            if (c != null && c.moveToFirst())
                                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        }
                        if (status == DownloadManager.STATUS_SUCCESSFUL) p.put("done", true);
                        else if (status == DownloadManager.STATUS_FAILED || status == -1) failedRegions.add(p.getString("region"));
                        String name = p.getString("name");
                        String target = name.replaceAll("\\.part\\d+$", "");
                        List<JSONObject> l = byTarget.get(target);
                        if (l == null) byTarget.put(target, l = new ArrayList<>());
                        l.add(p);
                    }
                    JSONArray keep = new JSONArray();
                    Set<String> regionsLeft = new HashSet<>();
                    for (Map.Entry<String, List<JSONObject>> e : byTarget.entrySet()) {
                        List<JSONObject> parts = e.getValue();
                        String region = parts.get(0).getString("region");
                        if (failedRegions.contains(region)) { // one file failed: the map is incomplete, stop all of it
                            for (JSONObject p : parts) {
                                dm.remove(p.getLong("id"));
                                //noinspection ResultOfMethodCallIgnored
                                new File(new File(dir, ".download"), p.getString("name")).delete();
                            }
                            if (!errors.contains(region)) errors.add(region);
                            continue;
                        }
                        boolean all = true;
                        for (JSONObject p : parts) all &= p.optBoolean("done");
                        if (!all) {
                            for (JSONObject p : parts) keep.put(p);
                            regionsLeft.add(region);
                            continue;
                        }
                        try {
                            assemble(e.getKey(), parts);
                        } catch (Exception ex) {
                            Log.e(TAG, "download " + e.getKey() + ": " + ex);
                            if (!errors.contains(region)) errors.add(region);
                        }
                        for (JSONObject p : parts) dm.remove(p.getLong("id")); // the file is moved already: only the entry goes
                    }
                    for (Map.Entry<String, List<JSONObject>> e : byTarget.entrySet()) {
                        String region = e.getValue().get(0).getString("region");
                        if (!regionsLeft.contains(region) && !errors.contains(region) && !ready.contains(region)) ready.add(region);
                    }
                    prefs.edit().putString(PREF_DOWNLOADS, keep.toString()).apply();
                } catch (Exception ex) {
                    Log.e(TAG, "downloads: " + ex);
                    return;
                }
                if (ready.isEmpty() && errors.isEmpty()) return;
                scan();
            }
            MAIN.post(() -> listener.onChanged(ready, errors));
        });
    }

    /** Moves a downloaded file into the maps folder; parts (".part00", ".part01", …) are joined first. */
    private void assemble(String target, List<JSONObject> parts) throws Exception {
        File dl = new File(dir, ".download");
        File out = new File(dir, target);
        File tmp = new File(dir, "." + target + ".joining");
        if (parts.size() == 1 && parts.get(0).getString("name").equals(target)) {
            File f = new File(dl, target);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            if (!f.renameTo(out)) throw new IOException("cannot move " + f);
        } else {
            List<String> names = new ArrayList<>();
            for (JSONObject p : parts) names.add(p.getString("name"));
            java.util.Collections.sort(names); // .part00, .part01, …
            byte[] buf = new byte[1024 * 1024];
            try (OutputStream os = new FileOutputStream(tmp)) {
                for (String n : names) {
                    try (InputStream in = new FileInputStream(new File(dl, n))) {
                        int k;
                        while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
                    }
                }
            }
            for (String n : names)
                //noinspection ResultOfMethodCallIgnored
                new File(dl, n).delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            if (!tmp.renameTo(out)) throw new IOException("cannot move " + tmp);
        }
        if (target.endsWith("-style.zip")) unpackStyle(out);
    }

    // ------------------------------------------------------------------ helpers

    private static String nonEmpty(@Nullable String s, String fallback) {
        return s != null && !s.trim().isEmpty() ? s.trim() : fallback;
    }

    private static int toInt(@Nullable String s, int fallback) {
        try {
            return s != null ? (int) Math.round(Double.parseDouble(s.trim())) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null)
            for (File c : children) deleteRecursive(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
