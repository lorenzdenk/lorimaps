package io.github.lorenzdenk.lorimaps;

import androidx.annotation.Nullable;

/** One online map or overlay: raster tiles ({z}/{x}/{y} URLs) or a complete vector style (styleUrl). */
public class MapSource {
    public final String name;
    public final String details;
    /** raster: tile URL templates; empty for vector styles */
    public final String[] tileUrls;
    public final int minZoom, maxZoom;
    /** pixel size of the raster tiles */
    public final int tilePixels;
    /** HTML; the texts of its links are listed behind the "i" button on the map (other text is not shown) */
    public final String attribution;
    /** vector: URL of a MapLibre style JSON; null for raster maps */
    @Nullable public final String styleUrl;
    public final int icon;

    private MapSource(String name, String details, String[] tileUrls, int minZoom, int maxZoom, int tilePixels,
                      String attribution, @Nullable String styleUrl, int icon) {
        this.name = name; this.details = details; this.tileUrls = tileUrls; this.minZoom = minZoom; this.maxZoom = maxZoom;
        this.tilePixels = tilePixels; this.attribution = attribution; this.styleUrl = styleUrl; this.icon = icon;
    }

    public static MapSource raster(String name, int minZoom, int maxZoom, String attribution, int icon, String... tileUrls) {
        return new MapSource(name, "online", tileUrls, minZoom, maxZoom, 256, attribution, null, icon);
    }

    public static MapSource vector(String name, String styleUrl, int icon) {
        return new MapSource(name, "online, Vektor", new String[0], 0, 22, 512, "", styleUrl, icon);
    }

    public boolean isVector() {
        return styleUrl != null;
    }

    public String key() {
        return "online:" + name;
    }
}
