package com.locus.app.model;

/** Slippy-map tile arithmetic (plain Java, unit-tested): which tiles cover an area, and how big a download is. */
public final class TileMath {
    /** Average size of one OpenStreetMap standard tile (PNG), for the size estimate. */
    public static final int AVERAGE_TILE_BYTES = 20 * 1024;
    /** One download may not exceed this many tiles: the public tile servers are shared by everyone. */
    public static final int MAX_TILES = 2500;

    private TileMath() {
    }

    public static int tileX(double lon, int zoom) {
        double n = Math.pow(2, zoom);
        int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
        return (int) Math.max(0, Math.min(n - 1, x));
    }

    public static int tileY(double lat, int zoom) {
        double n = Math.pow(2, zoom);
        double latR = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)));
        int y = (int) Math.floor((1.0 - Math.log(Math.tan(latR) + 1.0 / Math.cos(latR)) / Math.PI) / 2.0 * n);
        return (int) Math.max(0, Math.min(n - 1, y));
    }

    /** Number of tiles needed for the box at one zoom level. */
    public static long tilesAt(double north, double south, double east, double west, int zoom) {
        long w = tileX(east, zoom) - tileX(west, zoom) + 1;
        long h = tileY(south, zoom) - tileY(north, zoom) + 1;
        return Math.max(0, w) * Math.max(0, h);
    }

    /** Number of tiles for all zoom levels from zoomMin to zoomMax. */
    public static long count(double north, double south, double east, double west, int zoomMin, int zoomMax) {
        long total = 0;
        for (int z = zoomMin; z <= zoomMax; z++) total += tilesAt(north, south, east, west, z);
        return total;
    }

    public static long estimateBytes(long tiles) {
        return tiles * AVERAGE_TILE_BYTES;
    }

    /** A box of {@code radiusKm} around a point: {south, north, west, east}. */
    public static double[] boxAround(double lat, double lon, double radiusKm) {
        double dLat = radiusKm / 111.32;
        double dLon = radiusKm / (111.32 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        return new double[]{lat - dLat, lat + dLat, lon - dLon, lon + dLon};
    }

    /** True when the tile (zoom, x, y) lies inside the box (used so deleting one area keeps tiles another area still needs). */
    public static boolean tileInBox(int zoom, int x, int y, double north, double south, double east, double west) {
        return x >= tileX(west, zoom) && x <= tileX(east, zoom) && y >= tileY(north, zoom) && y <= tileY(south, zoom);
    }
}
