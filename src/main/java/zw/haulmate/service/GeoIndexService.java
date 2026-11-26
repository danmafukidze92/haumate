package zw.haulmate.service;

import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import zw.haulmate.domain.Types.TruckClass;
import java.time.Instant;
import java.util.*;

/**
 * Live driver positions in Redis.
 *   drivers:geo        GEO set of online drivers (member = driverId)
 *   driver:{id}        hash: lat,lng,truckClass,truckId,online,busy,seen
 * The Celery matching task reads the same keys.
 */
@Service
public class GeoIndexService {
    public static final String GEO_KEY = "drivers:geo";
    private static final long STALE_SECONDS = 600;
    private final StringRedisTemplate redis;

    public record NearbyDriver(long driverId, double lat, double lng, double distanceKm, String truckClass, boolean busy) {}

    public GeoIndexService(StringRedisTemplate redis) { this.redis = redis; }

    public void goOnline(long driverId, long truckId, TruckClass tc) {
        redis.opsForHash().putAll("driver:" + driverId, Map.of(
                "online", "1", "truckId", String.valueOf(truckId), "truckClass", tc.name(), "busy", "0"));
    }

    public void goOffline(long driverId) {
        redis.opsForHash().put("driver:" + driverId, "online", "0");
        redis.opsForGeo().remove(GEO_KEY, String.valueOf(driverId));
    }

    public void setBusy(long driverId, boolean busy) { redis.opsForHash().put("driver:" + driverId, "busy", busy ? "1" : "0"); }

    public boolean isOnline(long driverId) { return "1".equals(redis.opsForHash().get("driver:" + driverId, "online")); }

    public void update(long driverId, double lat, double lng) {
        String id = String.valueOf(driverId);
        redis.opsForHash().putAll("driver:" + id, Map.of("lat", String.valueOf(lat), "lng", String.valueOf(lng),
                "seen", String.valueOf(Instant.now().getEpochSecond())));
        if (isOnline(driverId)) redis.opsForGeo().add(GEO_KEY, new Point(lng, lat), id);
    }

    public Optional<double[]> position(long driverId) {
        List<Object> v = redis.opsForHash().multiGet("driver:" + driverId, List.of("lat", "lng"));
        if (v.get(0) == null || v.get(1) == null) return Optional.empty();
        return Optional.of(new double[]{Double.parseDouble((String) v.get(0)), Double.parseDouble((String) v.get(1))});
    }

    public List<NearbyDriver> nearby(double lat, double lng, double radiusKm, TruckClass required, int limit) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> res = redis.opsForGeo().radius(GEO_KEY,
                new Circle(new Point(lng, lat), new Distance(radiusKm, Metrics.KILOMETERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeCoordinates().includeDistance().sortAscending().limit(limit * 3L));
        List<NearbyDriver> out = new ArrayList<>();
        long now = Instant.now().getEpochSecond();
        if (res == null) return out;
        for (var r : res) {
            String id = r.getContent().getName();
            List<Object> h = redis.opsForHash().multiGet("driver:" + id, List.of("truckClass", "busy", "seen", "online"));
            if (!"1".equals(h.get(3))) continue;
            if (h.get(2) != null && now - Long.parseLong((String) h.get(2)) > STALE_SECONDS) continue;
            String tc = (String) h.get(0);
            if (required != null && (tc == null || !TruckClass.valueOf(tc).canCarry(required))) continue;
            Point p = r.getContent().getPoint();
            out.add(new NearbyDriver(Long.parseLong(id), p.getY(), p.getX(), r.getDistance().getValue(), tc, "1".equals(h.get(1))));
            if (out.size() >= limit) break;
        }
        return out;
    }

    public List<NearbyDriver> all() { return nearby(-19.0, 29.8, 1500, null, 5000); }
}
