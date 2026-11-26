package zw.haulmate.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import zw.haulmate.domain.*;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.*;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class LoadService {
    public record CreateLoadReq(String pickupAddress, double pickupLat, double pickupLng,
                                String dropoffAddress, double dropoffLat, double dropoffLng,
                                String cargoType, String cargoDescription, double weightTonnes,
                                TruckClass truckClass, BodyType bodyType, boolean hazardous, boolean fragile,
                                boolean refrigerated, boolean urgent, Instant pickupFrom, Instant pickupTo,
                                BigDecimal offeredPrice, PaymentMethod paymentMethod) {}

    public record AvailableLoad(Load load, double distanceToPickupKm, int etaToPickupMinutes, boolean alreadyOffered) {}

    private static final SecureRandom RND = new SecureRandom();
    private static final List<LoadStatus> ACTIVE = List.of(LoadStatus.ASSIGNED, LoadStatus.EN_ROUTE_PICKUP,
            LoadStatus.AT_PICKUP, LoadStatus.LOADED, LoadStatus.IN_TRANSIT);

    private final LoadRepo loads; private final OfferRepo offers; private final UserRepo users; private final TruckRepo trucks;
    private final RatingRepo ratings; private final ChatRepo chats; private final PricingService pricing;
    private final RoutingService routing; private final GeoIndexService geo; private final CeleryClient celery;
    private final NotificationService notify; private final SimpMessagingTemplate ws; private final StorageService storage;
    private final PaymentService payments;

    public LoadService(LoadRepo loads, OfferRepo offers, UserRepo users, TruckRepo trucks, RatingRepo ratings,
                       ChatRepo chats, PricingService pricing, RoutingService routing, GeoIndexService geo,
                       CeleryClient celery, NotificationService notify, SimpMessagingTemplate ws,
                       StorageService storage, PaymentService payments) {
        this.loads = loads; this.offers = offers; this.users = users; this.trucks = trucks; this.ratings = ratings;
        this.chats = chats; this.pricing = pricing; this.routing = routing; this.geo = geo; this.celery = celery;
        this.notify = notify; this.ws = ws; this.storage = storage; this.payments = payments;
    }

    // ---------- helpers ----------
    public Load get(long id) { return loads.findById(id).orElseThrow(() -> ApiException.notFound("Load not found")); }

    public Load getForParticipant(long loadId, long userId, String role) {
        Load l = get(loadId);
        boolean ok = "ADMIN".equals(role) || Objects.equals(l.getShipperId(), userId) || Objects.equals(l.getDriverId(), userId)
                || (l.getStatus() == LoadStatus.OPEN && ("DRIVER".equals(role) || "FLEET_OWNER".equals(role)));
        if (!ok) throw ApiException.forbidden("Not your load");
        return l;
    }

    private AppUser verifiedUser(long id) {
        AppUser u = users.findById(id).orElseThrow();
        if (u.getKycStatus() != KycStatus.VERIFIED) throw ApiException.forbidden("Complete identity verification first");
        if (u.getStatus() == UserStatus.SUSPENDED) throw ApiException.forbidden("Account suspended");
        return u;
    }

    private void event(Load l, String type, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type); m.put("loadId", l.getId()); m.put("status", l.getStatus()); m.put("data", data);
        ws.convertAndSend("/topic/loads/" + l.getId() + "/status", (Object) m);
        ws.convertAndSend("/topic/admin/loads", (Object) m);
    }

    private void system(Load l, String text) {
        ChatMessage c = new ChatMessage();
        c.setLoadId(l.getId()); c.setMsgType(MsgType.SYSTEM); c.setBody(text);
        chats.save(c);
        ws.convertAndSend("/topic/loads/" + l.getId() + "/chat", c);
    }

    // ---------- shipper ----------
    @Transactional
    public Load create(long shipperId, CreateLoadReq r) {
        verifiedUser(shipperId);
        if (!Geo.inZimbabwe(r.pickupLat(), r.pickupLng())) throw ApiException.bad("Pickup must be in Zimbabwe");
        if (r.weightTonnes() <= 0 || r.weightTonnes() > 40) throw ApiException.bad("Weight must be between 0 and 40 tonnes");
        PricingService.Quote q = pricing.quote(new PricingService.Input(r.pickupLat(), r.pickupLng(), r.dropoffLat(),
                r.dropoffLng(), r.weightTonnes(), r.truckClass(), r.hazardous(), r.fragile(), r.refrigerated(), r.urgent()));
        BigDecimal offered = r.offeredPrice() != null ? r.offeredPrice() : q.recommended();
        if (offered.compareTo(q.recommended().multiply(BigDecimal.valueOf(0.5))) < 0)
            throw ApiException.bad("Offer is far below the market rate (" + q.recommended() + "). Drivers are unlikely to accept.");

        Load l = new Load();
        l.setRef("HM" + Long.toString(System.currentTimeMillis() % 100_000_000L, 36).toUpperCase() + (char) ('A' + RND.nextInt(26)));
        l.setShipperId(shipperId);
        l.setPickupAddress(r.pickupAddress()); l.setPickupLat(r.pickupLat()); l.setPickupLng(r.pickupLng());
        l.setDropoffAddress(r.dropoffAddress()); l.setDropoffLat(r.dropoffLat()); l.setDropoffLng(r.dropoffLng());
        l.setDistanceKm(q.distanceKm()); l.setEtaDropoffMinutes(q.durationMinutes());
        l.setCargoType(r.cargoType()); l.setCargoDescription(r.cargoDescription());
        l.setWeightTonnes(BigDecimal.valueOf(r.weightTonnes()));
        l.setTruckClass(q.truckClass()); l.setBodyType(r.bodyType());
        l.setHazardous(r.hazardous()); l.setFragile(r.fragile()); l.setRefrigerated(r.refrigerated()); l.setUrgent(r.urgent());
        l.setPickupFrom(r.pickupFrom()); l.setPickupTo(r.pickupTo());
        l.setRecommendedPrice(q.recommended()); l.setOfferedPrice(offered);
        l.setPaymentMethod(r.paymentMethod() == null ? PaymentMethod.CASH : r.paymentMethod());
        l.setDeliveryPin(String.format("%04d", RND.nextInt(10000)));
        loads.save(l);
        // Expanding-radius search runs in the worker: 10 km -> 25 -> 50 -> 150 -> 400 -> nationwide
        celery.send("haulmate.matching.expand_search", List.of(l.getId(), 0));
        event(l, "created", null);
        return l;
    }

    @Transactional
    public Load updateOfferedPrice(long shipperId, long loadId, BigDecimal price) {
        Load l = get(loadId);
        if (!l.getShipperId().equals(shipperId) || l.getStatus() != LoadStatus.OPEN) throw ApiException.forbidden("Cannot edit");
        l.setOfferedPrice(price);
        event(l, "price_changed", Map.of("offeredPrice", price));
        celery.send("haulmate.matching.expand_search", List.of(l.getId(), 0)); // re-alert with the better price
        return l;
    }

    public List<Offer> offersFor(long shipperId, long loadId) {
        Load l = get(loadId);
        if (!l.getShipperId().equals(shipperId)) throw ApiException.forbidden("Not your load");
        return offers.findByLoadIdOrderByAmountAsc(loadId);
    }

    /** Shipper picks a driver: the load is locked to that driver at the agreed amount. */
    @Transactional
    public Load acceptOffer(long shipperId, long loadId, long offerId) {
        Load l = get(loadId);
        if (!l.getShipperId().equals(shipperId)) throw ApiException.forbidden("Not your load");
        if (l.getStatus() != LoadStatus.OPEN) throw ApiException.conflict("Load already assigned");
        Offer o = offers.findById(offerId).filter(x -> x.getLoadId().equals(loadId) && x.getStatus() == OfferStatus.PENDING)
                .orElseThrow(() -> ApiException.notFound("Offer no longer available"));
        if (loads.findFirstByDriverIdAndStatusIn(o.getDriverId(), ACTIVE).isPresent())
            throw ApiException.conflict("That driver just took another load");

        o.setStatus(OfferStatus.ACCEPTED);
        offers.findByLoadIdAndStatus(loadId, OfferStatus.PENDING).forEach(x -> { if (!x.getId().equals(offerId)) {
            x.setStatus(OfferStatus.REJECTED);
            notify.notify(x.getDriverId(), "Load taken", "Load " + l.getRef() + " went to another driver", Map.of("loadId", loadId));
        }});
        l.setStatus(LoadStatus.ASSIGNED);
        l.setDriverId(o.getDriverId()); l.setTruckId(o.getTruckId()); l.setAgreedPrice(o.getAmount());
        l.setAssignedAt(Instant.now());
        geo.position(o.getDriverId()).ifPresent(p ->
                l.setEtaPickupMinutes(routing.route(p[0], p[1], l.getPickupLat(), l.getPickupLng()).minutes()));
        geo.setBusy(o.getDriverId(), true);
        loads.save(l);
        AppUser d = users.findById(o.getDriverId()).orElseThrow();
        system(l, d.getFullName() + " will carry this load for " + l.getCurrency() + " " + l.getAgreedPrice()
                + (l.getEtaPickupMinutes() != null ? ". ETA to pickup ~" + l.getEtaPickupMinutes() + " min" : ""));
        notify.notify(o.getDriverId(), "You got the load!", l.getRef() + ": head to " + l.getPickupAddress(),
                Map.of("loadId", loadId, "type", "assigned"));
        event(l, "assigned", Map.of("driverId", d.getId(), "driverName", d.getFullName(), "eta", Objects.toString(l.getEtaPickupMinutes(), "")));
        return l;
    }

    // ---------- driver ----------
    public List<AvailableLoad> available(long driverId, double radiusKm) {
        double[] pos = geo.position(driverId).orElseThrow(() -> ApiException.bad("Go online and share location first"));
        Truck t = trucks.findFirstByDriverIdAndActiveTrue(driverId).orElseThrow(() -> ApiException.bad("No truck assigned to you"));
        double dLat = radiusKm / 111.0, dLng = radiusKm / (111.0 * Math.cos(Math.toRadians(pos[0])));
        List<AvailableLoad> out = new ArrayList<>();
        for (Load l : loads.findOpenInBox(pos[0] - dLat, pos[0] + dLat, pos[1] - dLng, pos[1] + dLng)) {
            if (!t.getTruckClass().canCarry(l.getTruckClass())) continue;
            if (l.isRefrigerated() && t.getBodyType() != BodyType.REFRIGERATED) continue;
            double km = Geo.haversineKm(pos[0], pos[1], l.getPickupLat(), l.getPickupLng());
            if (km > radiusKm) continue;
            boolean offered = offers.findByLoadIdAndDriverIdAndStatus(l.getId(), driverId, OfferStatus.PENDING).isPresent();
            out.add(new AvailableLoad(l, Math.round(km * 10) / 10.0, routing.route(pos[0], pos[1], l.getPickupLat(), l.getPickupLng()).minutes(), offered));
        }
        out.sort(Comparator.comparingDouble(AvailableLoad::distanceToPickupKm));
        return out;
    }

    /** Driver accepts at the shipper's price (amount null) or makes a counter-offer. */
    @Transactional
    public Offer offer(long driverId, long loadId, BigDecimal amount) {
        verifiedUser(driverId);
        Load l = get(loadId);
        if (l.getStatus() != LoadStatus.OPEN) throw ApiException.conflict("Load is no longer open");
        if (loads.findFirstByDriverIdAndStatusIn(driverId, ACTIVE).isPresent()) throw ApiException.conflict("Finish your current trip first");
        Truck t = trucks.findFirstByDriverIdAndActiveTrue(driverId).orElseThrow(() -> ApiException.bad("No truck assigned to you"));
        if (!t.isVerified()) throw ApiException.forbidden("Your truck documents are still being verified");
        if (!t.getTruckClass().canCarry(l.getTruckClass())) throw ApiException.bad("Your truck class does not fit this load");

        Offer o = offers.findByLoadIdAndDriverIdAndStatus(loadId, driverId, OfferStatus.PENDING).orElseGet(Offer::new);
        o.setLoadId(loadId); o.setDriverId(driverId); o.setTruckId(t.getId());
        o.setAmount(amount == null ? l.getOfferedPrice() : amount);
        o.setCounter(amount != null && amount.compareTo(l.getOfferedPrice()) != 0);
        geo.position(driverId).ifPresent(p -> {
            RoutingService.Route r = routing.route(p[0], p[1], l.getPickupLat(), l.getPickupLng());
            o.setDistanceToPickupKm(Math.round(r.km() * 10) / 10.0); o.setEtaMinutes(r.minutes());
        });
        offers.save(o);
        AppUser d = users.findById(driverId).orElseThrow();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("offerId", o.getId()); data.put("driverName", d.getFullName()); data.put("rating", d.getRatingAvg());
        data.put("amount", o.getAmount()); data.put("counter", o.isCounter()); data.put("etaMinutes", o.getEtaMinutes());
        data.put("truck", t.getMake() + " " + t.getModel() + " (" + t.getRegNumber() + ")");
        event(l, "offer", data);
        notify.notify(l.getShipperId(), o.isCounter() ? "Counter-offer received" : "A driver accepted your price",
                d.getFullName() + " - " + l.getCurrency() + " " + o.getAmount()
                        + (o.getEtaMinutes() != null ? ", " + o.getEtaMinutes() + " min away" : ""),
                Map.of("loadId", loadId, "type", "offer"));
        return o;
    }

    private static final Map<LoadStatus, Set<LoadStatus>> NEXT = Map.of(
            LoadStatus.ASSIGNED, Set.of(LoadStatus.EN_ROUTE_PICKUP),
            LoadStatus.EN_ROUTE_PICKUP, Set.of(LoadStatus.AT_PICKUP),
            LoadStatus.AT_PICKUP, Set.of(LoadStatus.LOADED),
            LoadStatus.LOADED, Set.of(LoadStatus.IN_TRANSIT),
            LoadStatus.IN_TRANSIT, Set.of(LoadStatus.DELIVERED));

    @Transactional
    public Load advance(long driverId, long loadId, LoadStatus to, String pin, MultipartFile photo) {
        Load l = get(loadId);
        if (!Objects.equals(l.getDriverId(), driverId)) throw ApiException.forbidden("Not your trip");
        if (!NEXT.getOrDefault(l.getStatus(), Set.of()).contains(to))
            throw ApiException.bad("Cannot go from " + l.getStatus() + " to " + to);
        if (to == LoadStatus.LOADED) {
            if (photo != null && !photo.isEmpty()) l.setPickupPhoto(storage.save(photo, "pod/" + loadId));
            l.setPickedUpAt(Instant.now());
        }
        if (to == LoadStatus.DELIVERED) {
            if (pin == null || !pin.equals(l.getDeliveryPin())) throw ApiException.bad("Wrong delivery PIN. Ask the recipient.");
            if (photo != null && !photo.isEmpty()) l.setDeliveryPhoto(storage.save(photo, "pod/" + loadId));
            l.setDeliveredAt(Instant.now());
            geo.setBusy(driverId, false);
            payments.onDelivered(l);
        }
        l.setStatus(to);
        loads.save(l);
        String label = switch (to) {
            case EN_ROUTE_PICKUP -> "Driver is on the way to pickup";
            case AT_PICKUP -> "Driver has arrived at pickup";
            case LOADED -> "Cargo loaded";
            case IN_TRANSIT -> "Cargo in transit";
            case DELIVERED -> "Delivered. Please rate your driver.";
            default -> to.name();
        };
        system(l, label);
        notify.notify(l.getShipperId(), l.getRef(), label, Map.of("loadId", loadId, "type", "status", "status", to.name()));
        event(l, "status", null);
        return l;
    }

    @Transactional
    public Load cancel(long userId, long loadId, String reason) {
        Load l = get(loadId);
        boolean shipper = l.getShipperId().equals(userId), driver = Objects.equals(l.getDriverId(), userId);
        if (!shipper && !driver) throw ApiException.forbidden("Not your load");
        if (l.getStatus().ordinal() >= LoadStatus.LOADED.ordinal()) throw ApiException.conflict("Cargo already loaded. Open a dispute instead.");
        if (driver) {
            // Driver backs out: reopen the load and restart the search
            geo.setBusy(userId, false);
            offers.findByLoadIdAndStatus(loadId, OfferStatus.ACCEPTED).forEach(o -> o.setStatus(OfferStatus.WITHDRAWN));
            l.setStatus(LoadStatus.OPEN); l.setDriverId(null); l.setTruckId(null); l.setAgreedPrice(null); l.setEtaPickupMinutes(null);
            system(l, "Driver cancelled (" + reason + "). Searching for another truck...");
            notify.notify(l.getShipperId(), "Driver cancelled", "We're finding you another truck for " + l.getRef(), Map.of("loadId", loadId));
            celery.send("haulmate.matching.expand_search", List.of(loadId, 0));
        } else {
            if (l.getDriverId() != null) {
                geo.setBusy(l.getDriverId(), false);
                notify.notify(l.getDriverId(), "Load cancelled", l.getRef() + " was cancelled by the shipper", Map.of("loadId", loadId));
            }
            l.setStatus(LoadStatus.CANCELLED);
            l.setCancelReason(reason);
            offers.findByLoadIdAndStatus(loadId, OfferStatus.PENDING).forEach(o -> o.setStatus(OfferStatus.EXPIRED));
            payments.onCancelled(l);
        }
        loads.save(l);
        event(l, "cancelled", Map.of("by", shipper ? "shipper" : "driver"));
        return l;
    }

    @Transactional
    public void rate(long userId, long loadId, int stars, String comment) {
        Load l = get(loadId);
        if (l.getStatus() != LoadStatus.DELIVERED && l.getStatus() != LoadStatus.COMPLETED) throw ApiException.bad("Rate after delivery");
        Long to = l.getShipperId().equals(userId) ? l.getDriverId() : Objects.equals(l.getDriverId(), userId) ? l.getShipperId() : null;
        if (to == null) throw ApiException.forbidden("Not your load");
        if (ratings.existsByLoadIdAndFromUser(loadId, userId)) throw ApiException.conflict("Already rated");
        Rating r = new Rating();
        r.setLoadId(loadId); r.setFromUser(userId); r.setToUser(to); r.setStars(Math.max(1, Math.min(5, stars))); r.setComment(comment);
        ratings.save(r);
        AppUser u = users.findById(to).orElseThrow();
        BigDecimal total = u.getRatingAvg().multiply(BigDecimal.valueOf(u.getRatingCount())).add(BigDecimal.valueOf(r.getStars()));
        u.setRatingCount(u.getRatingCount() + 1);
        u.setRatingAvg(total.divide(BigDecimal.valueOf(u.getRatingCount()), 2, java.math.RoundingMode.HALF_UP));
        users.save(u);
        if (l.getStatus() == LoadStatus.DELIVERED) { l.setStatus(LoadStatus.COMPLETED); loads.save(l); }
    }

    public Optional<Load> activeTripFor(long driverId) { return loads.findFirstByDriverIdAndStatusIn(driverId, ACTIVE); }
}
