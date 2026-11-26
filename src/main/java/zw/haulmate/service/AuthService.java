package zw.haulmate.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import zw.haulmate.config.JwtService;
import zw.haulmate.domain.AppUser;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.UserRepo;

@Service
public class AuthService {
    public record RegisterReq(String fullName, String phone, String email, String password, Role role, String companyName) {}
    public record TokenRes(String token, AppUser user) {}

    private final UserRepo users;
    private final PasswordEncoder enc;
    private final JwtService jwt;

    public AuthService(UserRepo users, PasswordEncoder enc, JwtService jwt) { this.users = users; this.enc = enc; this.jwt = jwt; }

    /** Normalises 0771234567 / 263771234567 / +263 77 123 4567 to +263771234567. */
    public static String normalisePhone(String raw) {
        String d = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (d.startsWith("263")) d = d.substring(3);
        if (d.startsWith("0")) d = d.substring(1);
        if (!d.matches("7[1-8]\\d{7}")) throw ApiException.bad("Enter a valid Zimbabwe mobile number");
        return "+263" + d;
    }

    public TokenRes register(RegisterReq r) {
        if (r.role() == null || r.role() == Role.ADMIN) throw ApiException.bad("Role must be SHIPPER, DRIVER or FLEET_OWNER");
        if (r.password() == null || r.password().length() < 8) throw ApiException.bad("Password must be at least 8 characters");
        if (r.fullName() == null || r.fullName().isBlank()) throw ApiException.bad("Full name is required");
        String phone = normalisePhone(r.phone());
        if (users.findByPhone(phone).isPresent()) throw ApiException.conflict("Phone already registered");
        AppUser u = new AppUser();
        u.setFullName(r.fullName().trim());
        u.setPhone(phone);
        u.setEmail(r.email());
        u.setCompanyName(r.companyName());
        u.setRole(r.role());
        u.setPasswordHash(enc.encode(r.password()));
        users.save(u);
        return new TokenRes(jwt.issue(u), u);
    }

    public TokenRes login(String phone, String password) {
        AppUser u = users.findByPhone(normalisePhone(phone))
                .filter(x -> enc.matches(password, x.getPasswordHash()))
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid phone or password"));
        if (u.getStatus() == UserStatus.SUSPENDED) throw ApiException.forbidden("Account suspended. Contact support.");
        return new TokenRes(jwt.issue(u), u);
    }
}
