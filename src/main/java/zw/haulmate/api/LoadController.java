package zw.haulmate.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.haulmate.config.AuthUser;
import zw.haulmate.domain.*;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.*;
import zw.haulmate.service.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
public class LoadController {
    private final LoadService loads; private final LoadRepo loadRepo; private final PricingService pricing;
    private final GeoIndexService geo; private final TrackingService tracking; private final UserRepo users; private final TruckRepo trucks;

    public LoadController(LoadService loads, LoadRepo loadRepo, PricingService pricing, GeoIndexService geo,
                          TrackingService tracking, UserRepo users, TruckRepo trucks) {
        this.loads = loads; this.loadRepo = loadRepo; this.pricing = pricing; this.geo = geo; this.tracking = tracking;
        this.users = users; this.trucks = trucks;
    }

    /** Recommended price shown while the shipper fills in the load form. Public so the app can show it pre-signup. */
    @PostMapping("/api/pricing/estimate")
    public PricingService.Quote estimate(@RequestBody PricingService.Input in) { return pricing.quote(in); }

    /** "12 trucks near you" preview before posting. Positions are coarsened for driver privacy. */
    @GetMapping("/api/drivers/nearby")
    public Map<String, Object> nearby(@RequestParam double lat, @RequestParam double lng,
                                      @RequestParam(required = false) TruckClass truckClass, @RequestParam(defaultValue = "25") double radiusKm) {
        var list = geo.nearby(lat, lng, Math.min(radiusKm, 200), truckClass, 50).stream().filter(d -> !d.busy()).toList();
        return Map.of("count", list.size(), "drivers", list.stream().map(d -> Map.of(
                "lat", Math.round(d.lat() * 200) / 200.0, "lng", Math.round(d.lng() * 200) / 200.0,
                "truckClass", d.truckClass(), "distanceKm", Math.round(d.distanceKm() * 10) / 10.0)).toList());
    }

    // ----- shipper -----
    @PostMapping("/api/loads")
    public Load create(@AuthenticationPrincipal AuthUser me, @RequestBody LoadService.CreateLoadReq r) { return loads.create(me.id(), r); }

    @GetMapping("/api/loads/mine")
    public List<Load> mine(@AuthenticationPrincipal AuthUser me) {
        return "SHIPPER".equals(me.role()) ? loadRepo.findByShipperIdOrderByCreatedAtDesc(me.id()) : loadRepo.findByDriverIdOrderByCreatedAtDesc(me.id());
    }

    @GetMapping("/api/loads/{id}")
    public Map<String, Object> one(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        Load l = loads.getForParticipant(id, me.id(), me.role());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("load", l);
        if (l.getShipperId().equals(me.id())) m.put("deliveryPin", l.getDeliveryPin()); // shipper shares it with the recipient
        if (l.getDriverId() != null) {
            users.findById(l.getDriverId()).ifPresent(d -> m.put("driver", Map.of("id", d.getId(), "name", d.getFullName(),
                    "rating", d.getRatingAvg(), "trips", d.getRatingCount())));
            trucks.findById(l.getTruckId()).ifPresent(t -> m.put("truck", t));
            geo.position(l.getDriverId()).ifPresent(p -> m.put("driverPosition", Map.of("lat", p[0], "lng", p[1])));
        }
        users.findById(l.getShipperId()).ifPresent(s -> m.put("shipper", Map.of("id", s.getId(), "name", s.getFullName(), "rating", s.getRatingAvg())));
        return m;
    }

    @PutMapping("/api/loads/{id}/price")
    public Load price(@AuthenticationPrincipal AuthUser me, @PathVariable long id, @RequestBody Map<String, BigDecimal> b) {
        return loads.updateOfferedPrice(me.id(), id, b.get("offeredPrice"));
    }

    @GetMapping("/api/loads/{id}/offers")
    public List<Offer> offers(@AuthenticationPrincipal AuthUser me, @PathVariable long id) { return loads.offersFor(me.id(), id); }

    @PostMapping("/api/loads/{id}/offers/{offerId}/accept")
    public Load accept(@AuthenticationPrincipal AuthUser me, @PathVariable long id, @PathVariable long offerId) {
        return loads.acceptOffer(me.id(), id, offerId);
    }

    @PostMapping("/api/loads/{id}/cancel")
    public Load cancel(@AuthenticationPrincipal AuthUser me, @PathVariable long id, @RequestBody(required = false) Map<String, String> b) {
        return loads.cancel(me.id(), id, b == null ? "" : b.getOrDefault("reason", ""));
    }

    @PostMapping("/api/loads/{id}/rate")
    public Map<String, Boolean> rate(@AuthenticationPrincipal AuthUser me, @PathVariable long id, @RequestBody Map<String, Object> b) {
        loads.rate(me.id(), id, ((Number) b.get("stars")).intValue(), (String) b.get("comment"));
        return Map.of("ok", true);
    }

    /** Live tracking overview: every active load of mine that has a truck, with the truck's last position. */
    @GetMapping("/api/loads/tracking")
    public List<Map<String, Object>> tracking(@AuthenticationPrincipal AuthUser me) {
        var active = EnumSet.of(LoadStatus.ASSIGNED, LoadStatus.EN_ROUTE_PICKUP, LoadStatus.AT_PICKUP, LoadStatus.LOADED, LoadStatus.IN_TRANSIT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Load l : mine(me)) {
            if (l.getDriverId() == null || !active.contains(l.getStatus())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId()); m.put("ref", l.getRef()); m.put("status", l.getStatus());
            m.put("pickupLat", l.getPickupLat()); m.put("pickupLng", l.getPickupLng());
            m.put("dropoffLat", l.getDropoffLat()); m.put("dropoffLng", l.getDropoffLng());
            m.put("dropoffAddress", l.getDropoffAddress());
            boolean toPickup = l.getStatus().ordinal() < LoadStatus.LOADED.ordinal();
            m.put("etaMinutes", toPickup ? l.getEtaPickupMinutes() : l.getEtaDropoffMinutes());
            users.findById(l.getDriverId()).ifPresent(u -> m.put("driverName", u.getFullName()));
            tracking.last(l.getId()).ifPresent(p -> { m.put("lat", p.getLat()); m.put("lng", p.getLng()); m.put("at", p.getRecordedAt()); });
            out.add(m);
        }
        return out;
    }

    @GetMapping("/api/loads/{id}/trail")
    public List<LocationPing> trail(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        loads.getForParticipant(id, me.id(), me.role());
        return tracking.trail(id);
    }

    // ----- driver -----
    @GetMapping("/api/driver/loads/available")
    public List<LoadService.AvailableLoad> available(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "100") double radiusKm) {
        return loads.available(me.id(), Math.min(radiusKm, 1500));
    }

    /** Body {} = accept at the shipper's price; {"amount": 250} = counter-offer. */
    @PostMapping("/api/driver/loads/{id}/offer")
    public Offer offer(@AuthenticationPrincipal AuthUser me, @PathVariable long id, @RequestBody(required = false) Map<String, BigDecimal> b) {
        return loads.offer(me.id(), id, b == null ? null : b.get("amount"));
    }

    @PostMapping(value = "/api/driver/loads/{id}/status", consumes = {"multipart/form-data", "application/x-www-form-urlencoded"})
    public Load status(@AuthenticationPrincipal AuthUser me, @PathVariable long id, @RequestParam LoadStatus status,
                       @RequestParam(required = false) String pin, @RequestPart(required = false) MultipartFile photo) {
        return loads.advance(me.id(), id, status, pin, photo);
    }

    @GetMapping("/api/driver/trip")
    public Optional<Load> trip(@AuthenticationPrincipal AuthUser me) { return loads.activeTripFor(me.id()); }
}
