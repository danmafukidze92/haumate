package zw.haulmate.api;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import zw.haulmate.config.AuthUser;
import zw.haulmate.domain.*;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.*;
import zw.haulmate.service.*;
import java.security.Principal;
import java.util.*;

@Controller
public class DriverController {
    private final GeoIndexService geo; private final TrackingService tracking; private final TruckRepo trucks; private final UserRepo users;

    public DriverController(GeoIndexService geo, TrackingService tracking, TruckRepo trucks, UserRepo users) {
        this.geo = geo; this.tracking = tracking; this.trucks = trucks; this.users = users;
    }

    @PostMapping("/api/driver/online") @ResponseBody
    public Map<String, Object> online(@AuthenticationPrincipal AuthUser me, @RequestBody(required = false) TrackingService.Ping at) {
        AppUser u = users.findById(me.id()).orElseThrow();
        if (u.getKycStatus() != KycStatus.VERIFIED) throw ApiException.forbidden("Complete verification before going online");
        Truck t = trucks.findFirstByDriverIdAndActiveTrue(me.id()).orElseThrow(() -> ApiException.bad("Add or get assigned a truck first"));
        geo.goOnline(me.id(), t.getId(), t.getTruckClass());
        if (at != null && (at.lat() != 0 || at.lng() != 0)) tracking.ingest(me.id(), List.of(at));
        return Map.of("online", true, "truckClass", t.getTruckClass());
    }

    @PostMapping("/api/driver/offline") @ResponseBody
    public Map<String, Object> offline(@AuthenticationPrincipal AuthUser me) {
        geo.goOffline(me.id());
        return Map.of("online", false);
    }

    /** Batched upload: phones buffer GPS while offline (e.g. dead zones on the Beitbridge road) and flush here. */
    @PostMapping("/api/driver/location") @ResponseBody
    public Map<String, Object> location(@AuthenticationPrincipal AuthUser me, @RequestBody List<TrackingService.Ping> pings) {
        tracking.ingest(me.id(), pings);
        return Map.of("ok", true, "received", pings.size());
    }

    /** Low-latency single ping over STOMP: SEND /app/location {lat,lng,speedKmh,heading,ts} */
    @MessageMapping("/location")
    public void wsLocation(Principal p, @Payload TrackingService.Ping ping) {
        tracking.ingest(((AuthUser) p).id(), List.of(ping));
    }
}
