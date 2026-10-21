package com.locus.app.net;

import android.util.JsonReader;
import android.util.JsonToken;

import com.locus.app.graph.Graph;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Streams an Overpass "out geom" JSON response straight into a {@link Graph} (JSON parsing without
 * building the whole document in memory, which matters for large city areas).
 */
public final class RoadNetworkParser {
    private RoadNetworkParser() {
    }

    public static Graph parse(InputStream in) throws IOException {
        Graph g = new Graph();
        try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                if ("elements".equals(r.nextName())) {
                    r.beginArray();
                    while (r.hasNext()) readWay(r, g);
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        return g;
    }

    private static void readWay(JsonReader r, Graph g) throws IOException {
        String type = null, highway = null, oneway = null, junction = null;
        List<Long> ids = new ArrayList<>();
        List<Double> lats = new ArrayList<>();
        List<Double> lons = new ArrayList<>();

        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            switch (name) {
                case "type":
                    type = r.nextString();
                    break;
                case "nodes":
                    r.beginArray();
                    while (r.hasNext()) ids.add(r.nextLong());
                    r.endArray();
                    break;
                case "geometry":
                    r.beginArray();
                    while (r.hasNext()) {
                        if (r.peek() == JsonToken.NULL) {
                            r.nextNull();
                            lats.add(Double.NaN);
                            lons.add(Double.NaN);
                            continue;
                        }
                        double la = Double.NaN, lo = Double.NaN;
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if ("lat".equals(k)) la = r.nextDouble();
                            else if ("lon".equals(k)) lo = r.nextDouble();
                            else r.skipValue();
                        }
                        r.endObject();
                        lats.add(la);
                        lons.add(lo);
                    }
                    r.endArray();
                    break;
                case "tags":
                    r.beginObject();
                    while (r.hasNext()) {
                        String k = r.nextName();
                        if ("highway".equals(k)) highway = r.nextString();
                        else if ("oneway".equals(k)) oneway = r.nextString();
                        else if ("junction".equals(k)) junction = r.nextString();
                        else r.skipValue();
                    }
                    r.endObject();
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();

        if (!"way".equals(type) || highway == null || ids.size() < 2 || ids.size() != lats.size()) return;
        for (int i = 0; i < lats.size(); i++) {
            if (Double.isNaN(lats.get(i)) || Double.isNaN(lons.get(i))) return; // incomplete geometry
        }
        g.addWay(ids, lats, lons, highway, onewayCode(oneway, junction, highway));
    }

    /** 0 = two-way, 1 = forward only, -1 = reverse only. */
    private static int onewayCode(String oneway, String junction, String highway) {
        if ("-1".equals(oneway) || "reverse".equals(oneway)) return -1;
        if ("yes".equals(oneway) || "true".equals(oneway) || "1".equals(oneway)) return 1;
        if ("no".equals(oneway)) return 0;
        if ("roundabout".equals(junction)) return 1;
        if ("motorway".equals(highway) || "motorway_link".equals(highway)) return 1;
        return 0;
    }
}
