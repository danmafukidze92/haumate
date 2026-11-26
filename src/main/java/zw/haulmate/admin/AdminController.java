package zw.haulmate.admin;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import zw.haulmate.domain.*;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.*;
import zw.haulmate.service.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Admin panel (StarAdmin 2 UI). Works on PC and installs as a PWA on Android/iOS. */
@Controller
public class AdminController {
    private final UserRepo users; private final KycRepo kycDocs; private final LoadRepo loads; private final OfferRepo offers;
    private final PaymentRepo payments; private final TruckRepo trucks; private final ChatRepo chats;
    private final KycService kyc; private final SettingsService settings; private final PaymentService paymentService;
    private final GeoIndexService geo; private final StorageService storage; private final JdbcTemplate jdbc;
    private final LoadService loadService; private final zw.haulmate.config.JwtService jwt;

    public AdminController(UserRepo users, KycRepo kycDocs, LoadRepo loads, OfferRepo offers, PaymentRepo payments,
                           TruckRepo trucks, ChatRepo chats, KycService kyc, SettingsService settings,
                           PaymentService paymentService, GeoIndexService geo, StorageService storage, JdbcTemplate jdbc,
                           LoadService loadService, zw.haulmate.config.JwtService jwt) {
        this.jwt = jwt;
        this.users = users; this.kycDocs = kycDocs; this.loads = loads; this.offers = offers; this.payments = payments;
        this.trucks = trucks; this.chats = chats; this.kyc = kyc; this.settings = settings; this.paymentService = paymentService;
        this.geo = geo; this.storage = storage; this.jdbc = jdbc; this.loadService = loadService;
    }

    private AppUser admin(Principal p) { return users.findByPhone(p.getName()).orElseThrow(); }

    @ModelAttribute
    void common(Model m, Principal p) {
        if (p != null) {
            m.addAttribute("adminName", users.findByPhone(p.getName()).map(AppUser::getFullName).orElse("Admin"));
            m.addAttribute("pendingKyc", users.countByKycStatus(KycStatus.PENDING));
            m.addAttribute("paynowMode", settings.get("paynow.mode", "TEST"));
            // Installable Android app, shown in the top bar and on Settings so admins can download or share it
            try {
                Path apk = storage.resolve("apps/haulmate.apk");
                boolean has = Files.exists(apk);
                m.addAttribute("hasApk", has);
                if (has) {
                    m.addAttribute("apkSizeMb", String.format("%.1f", Files.size(apk) / 1048576.0));
                    m.addAttribute("apkUpdated", Files.getLastModifiedTime(apk).toInstant());
                }
                m.addAttribute("apkVersion", apkVersion());
            } catch (Exception e) {
                m.addAttribute("hasApk", false);
            }
        }
    }

    /** Version of the APK being served: written by the Jenkins APK build, else the version typed at manual upload. */
    private String apkVersion() {
        try {
            Path v = storage.resolve("apps/haulmate.version");
            if (Files.exists(v)) { String s = Files.readString(v).trim(); if (!s.isEmpty()) return s; }
        } catch (Exception ignored) { }
        return settings.get("app.latest_version", "1.0.0");
    }

    /** Old admin sign-in address: everyone now signs in at /login and lands on the view for their role. */
    @GetMapping("/admin/login") public String adminLogin() { return "redirect:/login"; }
    /** Public website: what HaulMate does, how it works, and app downloads (APK, Play Store, App Store). */
    @GetMapping("/")
    public String landing(Model m) {
        boolean hasApk = false;
        String android = "", ios = "", version = "1.0.0";
        try {
            hasApk = Files.exists(storage.resolve("apps/haulmate.apk"));
            android = settings.get("app.android_url");
            ios = settings.get("app.ios_url");
            version = apkVersion();
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AdminController.class).warn("Landing page settings unavailable: {}", e.getMessage());
        }
        m.addAttribute("hasApk", hasApk);
        m.addAttribute("apkUrl", "/download/android");
        m.addAttribute("hasPlay", android != null && !android.isBlank());
        m.addAttribute("playUrl", android == null || android.isBlank() ? "#download" : android);
        m.addAttribute("hasIos", ios != null && !ios.isBlank());
        m.addAttribute("iosUrl", ios == null || ios.isBlank() ? "#download" : ios);
        m.addAttribute("version", version);
        return "public/landing";
    }

    @GetMapping("/privacy") public String privacy() { return "public/privacy"; }

    // ----- Customer web portal (shippers, drivers, fleet owners). Pages call the same JWT API as the app. -----
    @GetMapping("/login") public String login(Model m) { m.addAttribute("mode", "login"); return "public/auth"; }
    @GetMapping("/signup") public String signup(Model m) { m.addAttribute("mode", "signup"); return "public/auth"; }
    @GetMapping({"/account", "/account/"}) public String account() { return "public/account"; }

    @GetMapping("/download/android")
    public ResponseEntity<FileSystemResource> apk() {
        Path f = storage.resolve("apps/haulmate.apk");
        if (!Files.exists(f)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"HaulMate.apk\"")
                .body(new FileSystemResource(f));
    }

    @PostMapping("/admin/settings/apk")
    public String uploadApk(@RequestParam("apk") org.springframework.web.multipart.MultipartFile apk,
                            @RequestParam(required = false) String version, Principal p, RedirectAttributes ra) throws Exception {
        if (apk.isEmpty() || !String.valueOf(apk.getOriginalFilename()).toLowerCase().endsWith(".apk")) {
            ra.addFlashAttribute("err", "Choose the app-release.apk file");
            return "redirect:/admin/settings";
        }
        Path dir = storage.resolve("apps");
        Files.createDirectories(dir);
        apk.transferTo(dir.resolve("haulmate.apk"));
        Files.deleteIfExists(dir.resolve("haulmate.version")); // manual upload: use the version typed below
        if (version != null && !version.isBlank()) settings.save(Map.of("app.latest_version", version.trim()), p.getName());
        ra.addFlashAttribute("msg", "APK uploaded. It is now downloadable from the home page.");
        return "redirect:/admin/settings";
    }
    @GetMapping("/payment/return") @ResponseBody
    public String paymentReturn() { return "<html><body style='font-family:sans-serif;text-align:center;padding-top:20vh'>" +
            "<h2>Payment received</h2><p>You can return to the HaulMate app.</p></body></html>"; }

    @GetMapping("/admin")
    public String dashboard(Model m) {
        m.addAttribute("page", "dashboard");
        m.addAttribute("shippers", users.countByRole(Role.SHIPPER));
        m.addAttribute("drivers", users.countByRole(Role.DRIVER) + users.countByRole(Role.FLEET_OWNER));
        m.addAttribute("openLoads", loads.countByStatus(LoadStatus.OPEN));
        long active = 0;
        for (LoadStatus s : LoadStatus.values()) if (s.isActiveTrip()) active += loads.countByStatus(s);
        m.addAttribute("activeTrips", active);
        m.addAttribute("onlineDrivers", geo.all().size());
        BigDecimal gmv = loads.gmvSince(List.of(LoadStatus.DELIVERED, LoadStatus.COMPLETED), Instant.now().minus(30, ChronoUnit.DAYS));
        m.addAttribute("gmv30", gmv);
        m.addAttribute("commission30", gmv.multiply(new BigDecimal(settings.get("commission.percent", "10"))).divide(BigDecimal.valueOf(100)));
        List<Map<String, Object>> daily = jdbc.queryForList(
                "select to_char(d::date,'DD Mon') as day, " +
                "(select count(*) from loads l where l.created_at::date = d::date) as posted, " +
                "(select count(*) from loads l where l.delivered_at::date = d::date) as delivered " +
                "from generate_series(current_date - 13, current_date, interval '1 day') d order by d");
        m.addAttribute("chartDays", daily.stream().map(r -> r.get("day")).toList());
        m.addAttribute("chartPosted", daily.stream().map(r -> r.get("posted")).toList());
        m.addAttribute("chartDelivered", daily.stream().map(r -> r.get("delivered")).toList());
        m.addAttribute("byClass", jdbc.queryForList("select truck_class, count(*) as n from loads group by truck_class order by 1"));
        m.addAttribute("recent", loads.findAllByOrderByCreatedAtDesc().stream().limit(8).toList());
        return "admin/dashboard";
    }

    @GetMapping("/admin/map")
    public String map(Model m) { m.addAttribute("page", "map"); return "admin/map"; }

    @GetMapping("/admin/api/live") @ResponseBody
    public Map<String, Object> live() {
        List<Map<String, Object>> drivers = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        users.findAll().forEach(u -> names.put(u.getId(), u.getFullName()));
        for (var d : geo.all()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("driverId", d.driverId()); x.put("name", names.getOrDefault(d.driverId(), "?")); x.put("lat", d.lat()); x.put("lng", d.lng());
            x.put("truckClass", d.truckClass()); x.put("busy", d.busy());
            drivers.add(x);
        }
        List<Map<String, Object>> open = loads.findByStatusIn(List.of(LoadStatus.OPEN)).stream().map(l -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", l.getId()); x.put("ref", l.getRef()); x.put("lat", l.getPickupLat()); x.put("lng", l.getPickupLng());
            x.put("to", l.getDropoffAddress()); x.put("price", l.getOfferedPrice()); x.put("radius", l.getSearchRadiusKm());
            x.put("truckClass", l.getTruckClass());
            return x;
        }).toList();
        return Map.of("drivers", drivers, "openLoads", open);
    }

    @GetMapping("/admin/api/trail/{id}") @ResponseBody
    public List<Map<String, Object>> trail(@PathVariable long id) {
        return jdbc.queryForList("select lat, lng, recorded_at from location_pings where load_id = ? order by recorded_at", id);
    }

    /** Short-lived JWT so the admin browser can subscribe to the live WebSocket feed. */
    @GetMapping("/admin/api/ws-token") @ResponseBody
    public Map<String, String> wsToken(Principal p) { return Map.of("token", jwt.issue(admin(p))); }

    // ---------- KYC ----------
    @GetMapping("/admin/kyc")
    public String kycQueue(Model m, @RequestParam(defaultValue = "PENDING") KycStatus status) {
        m.addAttribute("page", "kyc"); m.addAttribute("status", status);
        m.addAttribute("people", users.findByKycStatusOrderByCreatedAtAsc(status));
        return "admin/kyc";
    }

    @GetMapping("/admin/kyc/{userId}")
    public String kycReview(Model m, @PathVariable long userId) {
        m.addAttribute("page", "kyc");
        m.addAttribute("u", users.findById(userId).orElseThrow());
        m.addAttribute("docs", kycDocs.findByUserIdOrderByCreatedAtAsc(userId));
        m.addAttribute("trucks", trucks.findByOwnerId(userId));
        return "admin/kyc-review";
    }

    @PostMapping("/admin/kyc/{userId}/decide")
    public String decide(@PathVariable long userId, @RequestParam boolean approve, @RequestParam(required = false) String note,
                         @RequestParam(required = false) List<Long> verifyTrucks, Principal p, RedirectAttributes ra) {
        kyc.decide(userId, approve, note, admin(p).getId());
        if (approve && verifyTrucks != null) verifyTrucks.forEach(id -> trucks.findById(id).ifPresent(t -> { t.setVerified(true); trucks.save(t); }));
        ra.addFlashAttribute("msg", approve ? "Approved" : "Rejected");
        return "redirect:/admin/kyc";
    }

    @GetMapping("/admin/files")
    public ResponseEntity<FileSystemResource> file(@RequestParam String path) throws Exception {
        Path f = storage.resolve(path);
        if (!Files.exists(f)) return ResponseEntity.notFound().build();
        String type = Files.probeContentType(f);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type == null ? "application/octet-stream" : type))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300").body(new FileSystemResource(f));
    }

    // ---------- users ----------
    @GetMapping("/admin/users")
    public String users(Model m) {
        m.addAttribute("page", "users");
        m.addAttribute("list", users.findAllByOrderByCreatedAtDesc());
        return "admin/users";
    }

    @PostMapping("/admin/users/{id}/status")
    public String setStatus(@PathVariable long id, @RequestParam UserStatus status, RedirectAttributes ra) {
        AppUser u = users.findById(id).orElseThrow();
        if (u.getRole() != Role.ADMIN) { u.setStatus(status); users.save(u); if (status == UserStatus.SUSPENDED) geo.goOffline(id); }
        ra.addFlashAttribute("msg", u.getFullName() + " is now " + status);
        return "redirect:/admin/users";
    }

    // ---------- loads ----------
    @GetMapping("/admin/loads")
    public String loads(Model m, @RequestParam(required = false) LoadStatus status) {
        m.addAttribute("page", "loads"); m.addAttribute("status", status);
        m.addAttribute("list", status == null ? loads.findAllByOrderByCreatedAtDesc() : loads.findByStatusIn(List.of(status)));
        m.addAttribute("statuses", LoadStatus.values());
        Map<Long, String> names = new HashMap<>();
        users.findAll().forEach(u -> names.put(u.getId(), u.getFullName()));
        m.addAttribute("names", names);
        return "admin/loads";
    }

    @GetMapping("/admin/loads/{id}")
    public String load(Model m, @PathVariable long id) {
        Load l = loadService.get(id);
        m.addAttribute("page", "loads");
        m.addAttribute("l", l);
        m.addAttribute("shipper", users.findById(l.getShipperId()).orElse(null));
        m.addAttribute("driver", l.getDriverId() == null ? null : users.findById(l.getDriverId()).orElse(null));
        m.addAttribute("offers", offers.findByLoadIdOrderByAmountAsc(id));
        m.addAttribute("chat", chats.findByLoadIdOrderByCreatedAtAsc(id));
        m.addAttribute("payments", payments.findByLoadId(id));
        Map<Long, String> names = new HashMap<>();
        users.findAll().forEach(u -> names.put(u.getId(), u.getFullName()));
        m.addAttribute("names", names);
        return "admin/load";
    }

    // ---------- payments ----------
    @GetMapping("/admin/payments")
    public String payments(Model m) {
        m.addAttribute("page", "payments");
        m.addAttribute("list", payments.findAllByOrderByCreatedAtDesc());
        return "admin/payments";
    }

    @PostMapping("/admin/payments/{id}/poll")
    public String poll(@PathVariable long id, RedirectAttributes ra) {
        try { Payment p = paymentService.refresh(id); ra.addFlashAttribute("msg", p.getReference() + ": " + p.getStatus()); }
        catch (Exception e) { ra.addFlashAttribute("err", e.getMessage()); }
        return "redirect:/admin/payments";
    }

    // ---------- settings ----------
    @GetMapping("/admin/settings")
    public String settings(Model m) {
        m.addAttribute("page", "settings");
        Map<String, Setting> s = new LinkedHashMap<>();
        settings.all().forEach(x -> s.put(x.getKey(), x));
        m.addAttribute("s", s);
        m.addAttribute("classes", TruckClass.values());
        return "admin/settings";
    }

    @PostMapping("/admin/settings")
    public String saveSettings(@RequestParam Map<String, String> form, Principal p, RedirectAttributes ra) {
        Map<String, String> values = new LinkedHashMap<>();
        form.forEach((k, v) -> { if (k.startsWith("s.")) values.put(k.substring(2), v); });
        String mode = values.getOrDefault("paynow.mode", "TEST");
        if ("LIVE".equals(mode)) {
            boolean idMissing = values.getOrDefault("paynow.live.integration_id", "").isBlank();
            boolean keyMissing = values.getOrDefault("paynow.live.integration_key", "").isBlank() && settings.get("paynow.live.integration_key").isBlank();
            if (idMissing || keyMissing) { ra.addFlashAttribute("err", "Enter LIVE integration ID and key before switching to LIVE"); return "redirect:/admin/settings"; }
        }
        settings.save(values, p.getName());
        ra.addFlashAttribute("msg", "Settings saved. Paynow is in " + mode + " mode.");
        return "redirect:/admin/settings";
    }

    @PostMapping("/admin/settings/paynow-test")
    public String testPaynow(RedirectAttributes ra) {
        try {
            var c = paymentService.credentials();
            ra.addFlashAttribute("msg", "Paynow " + c.mode() + " credentials loaded for integration " + c.integrationId()
                    + ". Make a small test payment from the app to confirm end to end.");
        } catch (Exception e) { ra.addFlashAttribute("err", e.getMessage()); }
        return "redirect:/admin/settings";
    }
}
