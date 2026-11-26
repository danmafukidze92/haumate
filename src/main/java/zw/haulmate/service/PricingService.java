package zw.haulmate.service;

import org.springframework.stereotype.Service;
import zw.haulmate.domain.Types.TruckClass;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recommended price engine (rule based; v2 will learn from accepted prices per corridor).
 * price = (base + km * perKm * fuelIndex + tolls) * (1 + emptyReturn) * (1 + surcharges)
 */
@Service
public class PricingService {
    public record Quote(BigDecimal recommended, BigDecimal low, BigDecimal high, double distanceKm,
                        int durationMinutes, TruckClass truckClass, Map<String, BigDecimal> breakdown) {}

    public record Input(double pickupLat, double pickupLng, double dropLat, double dropLng, double weightTonnes,
                        TruckClass truckClass, boolean hazardous, boolean fragile, boolean refrigerated, boolean urgent) {}

    private final SettingsService s;
    private final RoutingService routing;

    public PricingService(SettingsService s, RoutingService routing) { this.s = s; this.routing = routing; }

    public Quote quote(Input in) {
        TruckClass tc = in.truckClass() != null ? in.truckClass() : TruckClass.forWeight(in.weightTonnes());
        if (in.weightTonnes() > tc.maxTonnes) tc = TruckClass.forWeight(in.weightTonnes());
        RoutingService.Route r = routing.route(in.pickupLat(), in.pickupLng(), in.dropLat(), in.dropLng());

        String[] p = s.get("pricing." + tc.name(), "40,1.3,5").split(",");
        double base = Double.parseDouble(p[0].trim()), perKm = Double.parseDouble(p[1].trim()), toll = Double.parseDouble(p[2].trim());
        double fuelIndex = s.dbl("pricing.current_diesel", 1.6) / s.dbl("pricing.reference_diesel", 1.6);
        int gates = (int) Math.floor(r.km() / s.dbl("pricing.km_per_toll_gate", 90));

        double distanceCost = r.km() * perKm * fuelIndex;
        double tolls = gates * toll;
        double emptyReturn = r.km() > 150 ? s.dbl("pricing.empty_return_factor", 0.15) : 0;
        double sur = 0;
        if (in.hazardous()) sur += s.dbl("pricing.surcharge.hazardous", 0.25);
        if (in.fragile()) sur += s.dbl("pricing.surcharge.fragile", 0.10);
        if (in.refrigerated()) sur += s.dbl("pricing.surcharge.refrigerated", 0.30);
        if (in.urgent()) sur += s.dbl("pricing.surcharge.urgent", 0.15);

        double subtotal = base + distanceCost + tolls;
        double total = subtotal * (1 + emptyReturn) * (1 + sur);

        Map<String, BigDecimal> b = new LinkedHashMap<>();
        b.put("base", money(base));
        b.put("distance", money(distanceCost));
        b.put("tolls", money(tolls));
        b.put("emptyReturn", money(subtotal * emptyReturn));
        b.put("surcharges", money(subtotal * (1 + emptyReturn) * sur));
        BigDecimal rec = roundTo(total, 5);
        return new Quote(rec, roundTo(total * 0.88, 5), roundTo(total * 1.12, 5),
                Math.round(r.km() * 10) / 10.0, r.minutes(), tc, b);
    }

    private static BigDecimal money(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal roundTo(double v, int step) {
        return BigDecimal.valueOf(Math.max(step, Math.round(v / step) * step)).setScale(2, RoundingMode.HALF_UP);
    }
}
