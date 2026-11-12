package io.github.lorenzdenk.lorimaps;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * One map or overlay:
 *  - online raster tiles ({z}/{x}/{y} URLs) or an online vector style (styleUrl),
 *  - offline: a raster MBTiles file, or a vector map of lorimaps-vector-render (vector MBTiles plus style package,
 *    see OfflineMaps).
 */
public class MapSource {
    public final String name;
    public final String details;
    /** online raster: tile URL templates; empty otherwise */
    public final String[] tileUrls;
    public final int minZoom, maxZoom;
    /** pixel size of the raster tiles */
    public final int tilePixels;
    /** HTML; the texts of its links are listed behind the "i" button on the map (other text is not shown) */
    public final String attribution;
    /** online vector: URL of a MapLibre style JSON; null otherwise */
    @Nullable public final String styleUrl;
    public final int icon;
    /** offline: the MBTiles file (raster tiles, or the vector tiles of a vector map); null for online maps */
    @Nullable public final File file;
    /** offline vector map: directory of the unpacked style package (style.json, sprite, glyphs) */
    @Nullable public final File styleDir;

    private MapSource(String name, String details, String[] tileUrls, int minZoom, int maxZoom, int tilePixels,
                      String attribution, @Nullable String styleUrl, int icon, @Nullable File file, @Nullable File styleDir) {
        this.name = name; this.details = details; this.tileUrls = tileUrls; this.minZoom = minZoom; this.maxZoom = maxZoom;
        this.tilePixels = tilePixels; this.attribution = attribution; this.styleUrl = styleUrl; this.icon = icon;
        this.file = file; this.styleDir = styleDir;
    }

    public static MapSource raster(String name, int minZoom, int maxZoom, String attribution, int icon, String... tileUrls) {
        return new MapSource(name, "online", tileUrls, minZoom, maxZoom, 256, attribution, null, icon, null, null);
    }

    public static MapSource vector(String name, String styleUrl, int icon) {
        return new MapSource(name, "online, Vektor", new String[0], 0, 22, 512, "", styleUrl, icon, null, null);
    }

    /** raster MBTiles file on the device (map or overlay) */
    public static MapSource offlineRaster(String name, String details, File file, int minZoom, int maxZoom, int tilePixels,
                                         String attribution) {
        return new MapSource(name, details, new String[0], minZoom, maxZoom, tilePixels, attribution, null,
                R.drawable.ic_map_offline, file, null);
    }

    /** vector map on the device: vector tiles plus the unpacked style package */
    public static MapSource offlineVector(String name, String details, File vectorFile, File styleDir) {
        return new MapSource(name, details, new String[0], 0, 22, 512, "", null, R.drawable.ic_map_offline, vectorFile, styleDir);
    }

    public boolean isVector() {
        return styleUrl != null || styleDir != null;
    }

    public boolean isOffline() {
        return file != null;
    }

    public String key() {
        return file != null ? "offline:" + file.getName() : "online:" + name;
    }
}
