package com.locus.app.model;

/** Nearby-search categories with their OpenStreetMap (Overpass) tag filters. */
public enum PlaceCategory {
    PIZZA("Pizza", new String[]{
            "[\"amenity\"~\"restaurant|fast_food|cafe\"][\"cuisine\"~\"pizza\"]",
            "[\"amenity\"~\"restaurant|fast_food|cafe\"][\"name\"~\"[Pp]izza\"]"
    }),
    BURGER("Burger", new String[]{
            "[\"amenity\"~\"restaurant|fast_food|cafe\"][\"cuisine\"~\"burger\"]",
            "[\"amenity\"~\"restaurant|fast_food|cafe\"][\"name\"~\"[Bb]urger\"]"
    }),
    PASTRY("Pastry", new String[]{
            "[\"shop\"~\"bakery|pastry|confectionery\"]",
            "[\"amenity\"~\"cafe|restaurant\"][\"name\"~\"[Bb]akery|[Pp]astry|[Cc]ake\"]"
    }),
    HOSPITAL("Hospital", new String[]{"[\"amenity\"~\"hospital|clinic|doctors\"]"}),
    SUPERSHOP("Supershop", new String[]{"[\"shop\"~\"supermarket|convenience|department_store|mall\"]"}),
    PHARMACY("Pharmacy", new String[]{"[\"amenity\"~\"pharmacy|clinic\"]", "[\"shop\"~\"chemist|pharmacy\"]"}),
    ATM("ATM", new String[]{"[\"amenity\"~\"atm|bank\"]"});

    public final String label;
    public final String[] filters;

    PlaceCategory(String label, String[] filters) {
        this.label = label;
        this.filters = filters;
    }

    public static String getLabel(String category) {
        if (category == null || category.trim().isEmpty() || "SEARCH".equalsIgnoreCase(category)) {
            return "Place";
        }
        for (PlaceCategory c : values()) {
            if (c.name().equalsIgnoreCase(category) || c.label.equalsIgnoreCase(category)) {
                return c.label;
            }
        }
        String formatted = category.replaceAll("[_-]", " ").trim();
        if (!formatted.isEmpty()) {
            return formatted.substring(0, 1).toUpperCase(java.util.Locale.US) +
                    (formatted.length() > 1 ? formatted.substring(1) : "");
        }
        return "Place";
    }

    public static PlaceCategory fromName(String name) {
        if (name != null) {
            for (PlaceCategory c : values()) {
                if (c.name().equalsIgnoreCase(name) || c.label.equalsIgnoreCase(name)) {
                    return c;
                }
            }
        }
        return PIZZA;
    }
}
