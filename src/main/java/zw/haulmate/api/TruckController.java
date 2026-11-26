package zw.haulmate.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import zw.haulmate.config.AuthUser;
import zw.haulmate.domain.*;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.*;
import zw.haulmate.service.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/trucks")
public class TruckController {
    private final TruckRepo trucks; private final UserRepo users;
    public TruckController(TruckRepo trucks, UserRepo users) { this.trucks = trucks; this.users = users; }

    public record TruckReq(String regNumber, String make, String model, TruckClass truckClass, BodyType bodyType,
                           BigDecimal capacityTonnes, Long driverId) {}

    @PostMapping
    public Truck add(@AuthenticationPrincipal AuthUser me, @RequestBody TruckReq r) {
        if (!"DRIVER".equals(me.role()) && !"FLEET_OWNER".equals(me.role())) throw ApiException.forbidden("Drivers and fleet owners only");
        String reg = r.regNumber() == null ? "" : r.regNumber().replaceAll("\\s", "").toUpperCase();
        if (reg.isBlank() || trucks.existsByRegNumber(reg)) throw ApiException.bad("Registration missing or already listed");
        Truck t = new Truck();
        t.setOwnerId(me.id()); t.setRegNumber(reg); t.setMake(r.make()); t.setModel(r.model());
        t.setTruckClass(r.truckClass()); t.setBodyType(r.bodyType() == null ? BodyType.OPEN : r.bodyType());
        t.setCapacityTonnes(r.capacityTonnes() == null ? BigDecimal.valueOf(r.truckClass().maxTonnes) : r.capacityTonnes());
        t.setDriverId("DRIVER".equals(me.role()) ? me.id() : r.driverId());
        return trucks.save(t);
    }

    @GetMapping
    public List<Truck> mine(@AuthenticationPrincipal AuthUser me) { return trucks.findByOwnerId(me.id()); }

    /** Fleet owner assigns one of their trucks to a driver in their fleet. */
    @PutMapping("/{id}/driver/{driverId}")
    public Truck assign(@AuthenticationPrincipal AuthUser me, @PathVariable long id, @PathVariable long driverId) {
        Truck t = trucks.findById(id).filter(x -> x.getOwnerId().equals(me.id())).orElseThrow(() -> ApiException.notFound("Truck not found"));
        AppUser d = users.findById(driverId).orElseThrow(() -> ApiException.notFound("Driver not found"));
        if (!Objects.equals(d.getFleetOwnerId(), me.id()) && d.getId() != me.id()) throw ApiException.forbidden("Driver is not in your fleet");
        t.setDriverId(driverId);
        return trucks.save(t);
    }
}
