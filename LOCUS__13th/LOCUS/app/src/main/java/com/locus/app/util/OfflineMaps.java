package com.locus.app.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.locus.app.model.OfflineArea;
import com.locus.app.model.TileMath;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.modules.SqlTileWriter;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.MapTileIndex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads map tiles of a chosen area into osmdroid's own tile cache (a SQLite file on the phone), so the map shows
 * without internet. Two downloads run at a time, politely, as the public tile servers ask.
 */
public final class OfflineMaps {
    private OfflineMaps() {
    }

    public interface Progress {
        /** Called from a background thread. */
        void onProgress(int done, int total);
    }

    /** A running download: {@link #cancel()} stops it. */
    public static final class Job {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    public static boolean onWifi(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    /** Bytes used on the phone by every saved and cached map tile. */
    public static long storageUsed() {
        return size(Configuration.getInstance().getOsmdroidTileCache());
    }

    public static long storageLimit() {
        return Configuration.getInstance().getTileFileSystemCacheMaxBytes();
    }

    private static long size(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += size(k);
        return total;
    }

    public static boolean wifiOnly(Context c) {
        return c.getSharedPreferences("locus_prefs", Context.MODE_PRIVATE).getBoolean("offline_wifi_only", true);
    }

    public static void setWifiOnly(Context c, boolean on) {
        c.getSharedPreferences("locus_prefs", Context.MODE_PRIVATE).edit().putBoolean("offline_wifi_only", on).apply();
    }

    /**
     * Blocking: downloads every tile of the area. Returns the number of bytes stored, or -1 when it was cancelled.
     * Tiles that failed are skipped (the map simply stays blank there when offline).
     */
    public static long download(final OfflineArea a, final Job job, final Progress progress) {
        final OnlineTileSourceBase source = TileSourceFactory.MAPNIK;
        final SqlTileWriter writer = new SqlTileWriter();
        final String agent = Configuration.getInstance().getUserAgentValue();
        final int total = (int) TileMath.count(a.north, a.south, a.east, a.west, a.zoomMin, a.zoomMax);
        final AtomicInteger done = new AtomicInteger();
        final AtomicLong bytes = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int z = a.zoomMin; z <= a.zoomMax; z++) {
                final int zoom = z;
                int x0 = TileMath.tileX(a.west, z), x1 = TileMath.tileX(a.east, z);
                int y0 = TileMath.tileY(a.north, z), y1 = TileMath.tileY(a.south, z);
                for (int x = x0; x <= x1; x++) {
                    for (int y = y0; y <= y1; y++) {
                        if (job.isCancelled()) break;
                        final int fx = x, fy = y;
                        pool.execute(() -> {
                            if (job.isCancelled()) return;
                            long index = MapTileIndex.getTileIndex(zoom, fx, fy);
                            byte[] data = fetch(source.getTileURLString(index), agent);
                            if (data != null) {
                                writer.saveFile(source, index, new ByteArrayInputStream(data),
                                        System.currentTimeMillis() + 365L * 24 * 3600 * 1000);
                                bytes.addAndGet(data.length);
                            }
                            progress.onProgress(done.incrementAndGet(), total);
                        });
                    }
                }
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(2, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writer.onDetach();
        }
        return job.isCancelled() ? -1 : bytes.get();
    }

    private static byte[] fetch(String url, String agent) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            con.setRequestProperty("User-Agent", agent);
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Blocking: removes the tiles of the area, except those another saved area still needs. */
    public static void remove(OfflineArea a, List<OfflineArea> others) {
        final OnlineTileSourceBase source = TileSourceFactory.MAPNIK;
        SqlTileWriter writer = new SqlTileWriter();
        try {
            for (int z = a.zoomMin; z <= a.zoomMax; z++) {
                int x0 = TileMath.tileX(a.west, z), x1 = TileMath.tileX(a.east, z);
                int y0 = TileMath.tileY(a.north, z), y1 = TileMath.tileY(a.south, z);
                for (int x = x0; x <= x1; x++) {
                    for (int y = y0; y <= y1; y++) {
                        boolean needed = false;
                        for (OfflineArea o : others) {
                            if (o.id.equals(a.id) || z < o.zoomMin || z > o.zoomMax) continue;
                            if (TileMath.tileInBox(z, x, y, o.north, o.south, o.east, o.west)) {
                                needed = true;
                                break;
                            }
                        }
                        if (!needed) writer.remove(source, MapTileIndex.getTileIndex(z, x, y));
                    }
                }
            }
        } finally {
            writer.onDetach();
        }
    }
}
