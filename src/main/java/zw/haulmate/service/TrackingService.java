package zw.haulmate.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.haulmate.domain.*;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Ingests phone GPS from drivers (single pings over WebSocket or offline batches over REST). */
@Service
public class TrackingService {
    public record Ping(double lat, double lng, Double speedKmh, Double heading, Long ts) {}

    private final GeoIndexService geo; private final LoadService loads; private final LoadRepo loadRepo;
    private final PingRepo pings; private final RoutingService routing; private final SimpMessagingTemplate ws;
    private final NotificationService notify; private final StringRedisTemplate redis;

    public TrackingService(GeoIndexService geo, LoadService loads, LoadRepo loadRepo, PingRepo pings, RoutingService routing,
                           SimpMessagingTemplate ws, NotificationService notify, StringRedisTemplate redis) {
        this.geo = geo; this.loads = loads; this.loadRepo = loadRepo; this.pings = pings; this.routing = routing;
        this.ws = ws; this.notify = notify; this.redis = redis;
    }

    @Transactional
    public void ingest(long driverId, List<Ping> batch) {
        if (batch == null || batch.isEmpty()) return;
        List<Ping> sorted = new ArrayList<>(batch);
        sorted.sort(Comparator.comparing(p -> p.ts() == null ? Long.MAX_VALUE : p.ts()));
        Ping last = sorted.get(sorted.size() - 1);
        geo.update(driverId, last.lat(), last.lng());

        Optional<Load> trip = loads.activeTripFor(driverId);
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("driverId", driverId); live.put("lat", last.lat()); live.put("lng", last.lng());
        live.put("heading", last.heading()); live.put("speedKmh", last.speedKmh());

        if (trip.isPresent()) {
            Load l = trip.get();
            for (Ping p : sorted) {
                LocationPing lp = new LocationPing();
                lp.setDriverId(driverId); lp.setLoadId(l.getId()); lp.setLat(p.lat()); lp.setLng(p.lng());
                lp.setSpeedKmh(p.speedKmh()); lp.setHeading(p.heading());
                lp.setRecordedAt(p.ts() == null ? Instant.now() : Instant.ofEpochMilli(p.ts()));
                pings.save(lp);
            }
            boolean toPickup = l.getStatus().ordinal() < LoadStatus.LOADED.ordinal();
            double tLat = toPickup ? l.getPickupLat() : l.getDropoffLat(), tLng = toPickup ? l.getPickupLng() : l.getDropoffLng();
            RoutingService.Route r = routing.route(last.lat(), last.lng(), tLat, tLng);
            if (toPickup) l.setEtaPickupMinutes(r.minutes()); else l.setEtaDropoffMinutes(r.minutes());
            loadRepo.save(l);
            live.put("loadId", l.getId()); live.put("status", l.getStatus());
            live.put("etaMinutes", r.minutes()); live.put("remainingKm", Math.round(r.km() * 10) / 10.0);
            live.put("target", toPickup ? "pickup" : "dropoff");
            ws.convertAndSend("/topic/loads/" + l.getId() + "/location", (Object) live);

            // One-off "arriving" alert when the truck is ~1 km out
            if (r.km() < 1.0) {
                String key = "arriving:" + l.getId() + ":" + (toPickup ? "p" : "d");
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", Duration.ofHours(12))))
                    notify.notify(l.getShipperId(), "Truck arriving", "Your driver is about 1 km from " +
                            (toPickup ? "pickup" : "the drop-off"), Map.of("loadId", l.getId()));
            }
        }
        ws.convertAndSend("/topic/admin/live", (Object) live);
    }

    public Optional<LocationPing> last(long loadId) { return pings.findFirstByLoadIdOrderByRecordedAtDesc(loadId); }

    public List<LocationPing> trail(long loadId) { return pings.findByLoadIdOrderByRecordedAtAsc(loadId); }
}
