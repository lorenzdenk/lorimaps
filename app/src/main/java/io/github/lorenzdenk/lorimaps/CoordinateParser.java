package io.github.lorenzdenk.lorimaps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads coordinates in as many common ways of writing as possible. Result: {latitude, longitude} (WGS84) or null.
 * <ul>
 * <li>decimal degrees: "-42.96245, 146.72793", "-42.96245 146.72793", "-42.96245;146.72793", "-42.96245/146.72793",
 *     with decimal commas "-42,96245 146,72793" / "-42,96245; 146,72793", with letters "42.96245 S 146.72793 E",
 *     "S42.96245 E146.72793", "42.96245°S, 146.72793°E"</li>
 * <li>degrees and decimal minutes: "42°57.747'S 146°43.676'E", "S 42 57.747 E 146 43.676"</li>
 * <li>degrees, minutes, seconds: "42°57'44.8\"S 146°43'40.6\"E", "42 57 44.8 S 146 43 40.6 E", "42d57m44.8s S ..."</li>
 * <li>longitude first when the letters say so ("146.72793 E 42.96245 S")</li>
 * <li>links: "geo:-42.96,146.72", Google Maps ("…/@-42.96,146.72,15z", "…?q=-42.96,146.72"), OpenStreetMap ("#map=15/-42.96/146.72")</li>
 * <li>UTM / MGA (GDA94, GDA2020, practically the same as WGS84): "55G 482345 5243567", "55 482345E 5243567N",
 *     "Zone 55 E 482345 N 5243567", and without zone ("482345 5243567", "E 482345 N 5243567") as zone 55 south (Tasmania)</li>
 * </ul>
 */
public final class CoordinateParser {

    private CoordinateParser() {
    }

    /** UTM zone assumed when none is given (Tasmania: MGA zone 55, southern hemisphere) */
    public static final int DEFAULT_ZONE = 55;

    public static double[] parse(String text) {
        if (text == null) return null;
        String s = normalize(text);
        if (s.isEmpty()) return null;

        double[] link = parseLink(s);
        if (link != null) return valid(link);

        double[] utm = parseUtm(s);
        if (utm != null) return valid(utm);

        return valid(parseDegrees(s));
    }

    private static double[] valid(double[] p) {
        if (p == null || Double.isNaN(p[0]) || Double.isNaN(p[1])) return null;
        if (Math.abs(p[0]) > 90 || Math.abs(p[1]) > 180) return null;
        return p;
    }

    private static String normalize(String text) {
        return text.trim()
                .replace('′', '\'').replace('’', '\'').replace('´', '\'').replace('`', '\'').replace('‘', '\'')
                .replace('″', '"').replace('”', '"').replace('“', '"').replace("''", "\"")
                .replace('º', '°').replace('˚', '°').replace('⁰', '°')
                .replace('−', '-').replace('–', '-').replace('—', '-')
                .replace(' ', ' ');
    }

    // ------------------------------------------------------------------ links

    private static final Pattern NUM = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern LINK_AT = Pattern.compile("@([-+]?\\d+\\.\\d+),([-+]?\\d+\\.\\d+)");
    private static final Pattern LINK_Q = Pattern.compile("[?&](?:q|query|ll|center|daddr|destination|mlat)=([-+]?\\d+(?:\\.\\d+)?)(?:,|%2C|&mlon=)\\s*([-+]?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_OSM = Pattern.compile("map=\\d+(?:\\.\\d+)?/([-+]?\\d+(?:\\.\\d+)?)/([-+]?\\d+(?:\\.\\d+)?)");

    private static double[] parseLink(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("geo:")) {
            String body = s.substring(4).split("[?;]")[0];
            String[] p = body.split(",");
            if (p.length >= 2) {
                try {
                    return new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())};
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }
        if (!lower.contains("http") && !lower.contains("www.") && !lower.contains("maps") && !lower.contains("map=")) return null;
        for (Pattern p : new Pattern[]{LINK_Q, LINK_AT, LINK_OSM}) {
            Matcher m = p.matcher(s);
            if (m.find()) return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
        }
        return null;
    }

    // ------------------------------------------------------------------ UTM / MGA

    private static final Pattern UTM = Pattern.compile(
            "^(?:(?:zone|zn|z)\\s*)?(?:(\\d{1,2})\\s*([C-HJ-NP-X])?\\s*[,;]?\\s*)?"
                    + "(?:[EeOo]\\s*:?\\s*)?(\\d{6}(?:\\.\\d+)?)\\s*(?:m\\s*)?(?:[EeOo]\\b)?\\s*[,;/]?\\s*"
                    + "(?:[Nn]\\s*:?\\s*)?(\\d{7}(?:\\.\\d+)?)\\s*(?:m\\s*)?(?:[Nn]\\b)?$",
            Pattern.CASE_INSENSITIVE);

    private static double[] parseUtm(String s) {
        String t = s.replaceAll("(?i)\\b(utm|mga|gda94|gda2020|gda|wgs84|easting|northing|zone)(?=\\d|\\b)\\s*:?", " ")
                .replaceAll("\\s+", " ").trim();
        // "Zone 55" was removed above: keep its number in front
        Matcher m = UTM.matcher(t);
        if (!m.matches()) return null;
        int zone = m.group(1) != null ? Integer.parseInt(m.group(1)) : DEFAULT_ZONE;
        if (zone < 1 || zone > 60) return null;
        boolean south = true; // Tasmania; a latitude band letter decides otherwise
        if (m.group(2) != null) south = Character.toUpperCase(m.group(2).charAt(0)) < 'N';
        double e = Double.parseDouble(m.group(3)), n = Double.parseDouble(m.group(4));
        if (e < 100000 || e > 900000) return null;
        return utmToLatLon(zone, south, e, n);
    }

    /** UTM to latitude/longitude on the WGS84 (= GRS80) ellipsoid (Krüger series, mm accuracy). */
    static double[] utmToLatLon(int zone, boolean south, double easting, double northing) {
        double a = 6378137.0, f = 1 / 298.257223563, k0 = 0.9996;
        double x = easting - 500000.0, y = south ? northing - 10000000.0 : northing;
        double n = f / (2 - f);
        double A = a / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64);
        double[] beta = {
                n / 2 - 2 * n * n / 3 + 37 * n * n * n / 96,
                n * n / 48 + n * n * n / 15,
                17 * n * n * n / 480};
        double xi = y / (k0 * A), eta = x / (k0 * A);
        double xi1 = xi, eta1 = eta;
        for (int j = 1; j <= 3; j++) {
            xi1 -= beta[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
            eta1 -= beta[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
        }
        double chi = Math.asin(Math.sin(xi1) / Math.cosh(eta1));
        double[] delta = {
                2 * n - 2 * n * n / 3 - 2 * n * n * n,
                7 * n * n / 3 - 8 * n * n * n / 5,
                56 * n * n * n / 15};
        double phi = chi;
        for (int j = 1; j <= 3; j++) phi += delta[j - 1] * Math.sin(2 * j * chi);
        double lon0 = Math.toRadians(zone * 6 - 183);
        double lambda = lon0 + Math.atan(Math.sinh(eta1) / Math.cos(xi1));
        return new double[]{Math.toDegrees(phi), Math.toDegrees(lambda)};
    }

    // ------------------------------------------------------------------ degrees

    private static final Pattern TOKEN = Pattern.compile(
            "([-+]?\\d+(?:\\.\\d+)?)|(°|\\bdeg\\b|d(?=\\s*\\d))|('|\\bmin\\b|m(?=\\s*\\d))|(\"|\\bsec\\b|s(?=\\s|$|[NSEWO,;]))|(?<![A-Za-z])([NSEWOnsewo])(?![A-Za-z])",
            Pattern.CASE_INSENSITIVE);

    private static double[] parseDegrees(String input) {
        String s = input;
        // decimal commas: with ";" as separator, or when there are no points and commas sit between digits
        if (s.contains(";")) s = s.replaceAll("(\\d),(\\d)", "$1.$2");
        else if (!s.contains(".") && Pattern.compile("\\d,\\d").matcher(s).find()) s = s.replaceAll("(\\d),(\\d)", "$1.$2");

        // tokens: numbers, unit marks (° ' "), hemisphere letters
        List<Object> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            if (m.group(1) != null) tokens.add(m.group(1));
            else if (m.group(2) != null) tokens.add('D');
            else if (m.group(3) != null) tokens.add('M');
            else if (m.group(4) != null) tokens.add('S');
            else tokens.add(Character.toUpperCase(m.group(5).charAt(0)) == 'O' ? 'E' : Character.toUpperCase(m.group(5).charAt(0)));
        }
        // split into the two coordinates
        List<List<Object>> groups = split(tokens);
        if (groups == null || groups.size() != 2) return null;
        double[] v = new double[2];
        char[] h = new char[2];
        for (int g = 0; g < 2; g++) {
            Double val = value(groups.get(g));
            if (val == null) return null;
            v[g] = val;
            h[g] = hemisphere(groups.get(g));
        }
        boolean lonFirst = h[0] == 'E' || h[0] == 'W' || h[1] == 'N' || h[1] == 'S';
        return lonFirst ? new double[]{v[1], v[0]} : new double[]{v[0], v[1]};
    }

    /** Groups the tokens of the two coordinates, by hemisphere letters, by degree marks or by the number of numbers. */
    private static List<List<Object>> split(List<Object> t) {
        // hemisphere letters; 'S' can also mean seconds (see isSouth)
        List<Integer> hemi = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            Object o = t.get(i);
            if (!(o instanceof Character)) continue;
            char c = (Character) o;
            if (c == 'N' || c == 'E' || c == 'W') hemi.add(i);
            if (c == 'S' && isSouth(t, i)) hemi.add(i);
        }
        if (hemi.size() == 2) {
            boolean before = hemi.get(0) == 0; // letters in front: "S 42.9 E 146.7"
            List<List<Object>> g = new ArrayList<>();
            if (before) {
                g.add(new ArrayList<>(t.subList(hemi.get(0), hemi.get(1))));
                g.add(new ArrayList<>(t.subList(hemi.get(1), t.size())));
            } else {
                g.add(new ArrayList<>(t.subList(0, hemi.get(0) + 1)));
                g.add(new ArrayList<>(t.subList(hemi.get(0) + 1, hemi.get(1) + 1)));
            }
            return g;
        }
        if (hemi.size() == 1 || hemi.size() > 2) return null;
        // degree marks: each coordinate starts with "number °"
        List<Integer> deg = new ArrayList<>();
        for (int i = 1; i < t.size(); i++) if (Character.valueOf('D').equals(t.get(i)) && t.get(i - 1) instanceof String) deg.add(i - 1);
        if (deg.size() == 2) {
            List<List<Object>> g = new ArrayList<>();
            g.add(new ArrayList<>(t.subList(0, deg.get(1))));
            g.add(new ArrayList<>(t.subList(deg.get(1), t.size())));
            return g;
        }
        // only numbers: 2 = degrees, 4 = degrees + minutes, 6 = degrees + minutes + seconds
        List<Object> nums = new ArrayList<>();
        for (Object o : t) if (o instanceof String) nums.add(o);
        if (nums.size() == 2 || nums.size() == 4 || nums.size() == 6) {
            int k = nums.size() / 2;
            List<List<Object>> g = new ArrayList<>();
            g.add(new ArrayList<>(nums.subList(0, k)));
            g.add(new ArrayList<>(nums.subList(k, nums.size())));
            return g;
        }
        return null;
    }

    /** 'S' at position i means south (not seconds) unless it directly follows the third number of a coordinate with a unit mark before. */
    private static boolean isSouth(List<Object> t, int i) {
        if (i == 0) return true;
        Object prev = t.get(i - 1);
        if (!(prev instanceof String)) return true; // after a unit mark or a letter: a hemisphere
        // "44.8s" after degrees and minutes: seconds
        int marks = 0;
        for (int j = i - 2; j >= 0 && !(t.get(j) instanceof Character && "NEW".indexOf((Character) t.get(j)) >= 0); j--) {
            if (Character.valueOf('D').equals(t.get(j)) || Character.valueOf('M').equals(t.get(j))) marks++;
        }
        return marks < 2;
    }

    private static char hemisphere(List<Object> g) {
        for (int i = 0; i < g.size(); i++) {
            Object o = g.get(i);
            if (o instanceof Character) {
                char c = (Character) o;
                if (c == 'N' || c == 'E' || c == 'W') return c;
                if (c == 'S' && isSouth(g, i)) return c;
            }
        }
        return 0;
    }

    /** degrees (+ minutes (+ seconds)) of one coordinate, negative for S and W or a minus sign */
    private static Double value(List<Object> g) {
        List<Double> nums = new ArrayList<>();
        boolean negative = false;
        for (Object o : g) {
            if (o instanceof String) {
                String n = (String) o;
                if (nums.isEmpty() && n.startsWith("-")) negative = true;
                nums.add(Math.abs(Double.parseDouble(n)));
            }
        }
        if (nums.isEmpty() || nums.size() > 3) return null;
        double v = nums.get(0);
        if (nums.size() >= 2) {
            if (nums.get(1) >= 60 || nums.get(0) != Math.floor(nums.get(0))) return null;
            v += nums.get(1) / 60;
        }
        if (nums.size() == 3) {
            if (nums.get(2) >= 60 || nums.get(1) != Math.floor(nums.get(1))) return null;
            v += nums.get(2) / 3600;
        }
        char h = hemisphere(g);
        if (h == 'S' || h == 'W') negative = true;
        return negative ? -v : v;
    }
}
