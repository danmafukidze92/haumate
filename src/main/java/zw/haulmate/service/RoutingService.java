package zw.haulmate.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/** Road distance and ETA. Uses a self-hosted OSRM if routing.osrm_url is set, else haversine x road factor. */
@Service
public class RoutingService {
    public record Route(double km, int minutes, boolean estimated) {}

    private final SettingsService settings;
    private final ObjectMapper om;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public RoutingService(SettingsService settings, ObjectMapper om) { this.settings = settings; this.om = om; }

    public Route route(double lat1, double lng1, double lat2, double lng2) {
        String osrm = settings.get("routing.osrm_url");
        if (!osrm.isBlank()) {
            try {
                String url = String.format(Locale.ROOT, "%s/route/v1/driving/%f,%f;%f,%f?overview=false",
                        osrm.replaceAll("/$", ""), lng1, lat1, lng2, lat2);
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode route = om.readTree(r.body()).path("routes").path(0);
                if (!route.isMissingNode()) {
                    // OSRM car profile is optimistic for trucks: stretch duration by 25%
                    return new Route(route.get("distance").asDouble() / 1000.0,
                            (int) Math.ceil(route.get("duration").asDouble() * 1.25 / 60.0), false);
                }
            } catch (Exception ignored) { /* fall back */ }
        }
        double km = Geo.haversineKm(lat1, lng1, lat2, lng2) * settings.dbl("pricing.road_factor", 1.25);
        double speed = settings.dbl("routing.avg_speed_kmh", 55);
        if (km < 15) speed = Math.min(speed, 30); // urban
        return new Route(km, (int) Math.ceil(km / speed * 60) + 2, true);
    }
}
