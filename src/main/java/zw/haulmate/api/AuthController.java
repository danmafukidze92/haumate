package zw.haulmate.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import zw.haulmate.config.AuthUser;
import zw.haulmate.domain.AppUser;
import zw.haulmate.repo.UserRepo;
import zw.haulmate.service.AuthService;
import java.util.Map;

@RestController
public class AuthController {
    private final AuthService auth; private final UserRepo users;
    public AuthController(AuthService auth, UserRepo users) { this.auth = auth; this.users = users; }

    @PostMapping("/api/auth/register")
    public AuthService.TokenRes register(@RequestBody AuthService.RegisterReq r) { return auth.register(r); }

    public record LoginReq(String phone, String password) {}
    @PostMapping("/api/auth/login")
    public AuthService.TokenRes login(@RequestBody LoginReq r) { return auth.login(r.phone(), r.password()); }

    @GetMapping("/api/me")
    public AppUser me(@AuthenticationPrincipal AuthUser me) { return users.findById(me.id()).orElseThrow(); }

    /** Mobile app registers its Firebase Cloud Messaging token after login / token refresh. */
    @PutMapping("/api/me/fcm-token")
    public Map<String, Boolean> fcm(@AuthenticationPrincipal AuthUser me, @RequestBody Map<String, String> body) {
        AppUser u = users.findById(me.id()).orElseThrow();
        u.setFcmToken(body.get("token"));
        users.save(u);
        return Map.of("ok", true);
    }
}
