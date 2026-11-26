package zw.haulmate.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Lets the app check that an address really is a HaulMate server. */
@RestController
public class PingController {
    @GetMapping("/api/ping")
    public Map<String, String> ping() {
        return Map.of("app", "haulmate");
    }
}
