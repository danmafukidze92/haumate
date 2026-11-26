package zw.haulmate.service;

public final class Geo {
    private Geo() {}
    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0, dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
    /** Rough check that a coordinate is inside Zimbabwe's bounding box (plus a margin for border posts). */
    public static boolean inZimbabwe(double lat, double lng) {
        return lat > -22.9 && lat < -15.3 && lng > 25.0 && lng < 33.3;
    }
}
