package zw.haulmate.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import zw.haulmate.domain.AppUser;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Component
public class JwtService {
    private final SecretKey key;
    private final int ttlHours;

    public JwtService(AppProps props) {
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.ttlHours = props.jwtTtlHours();
    }

    public String issue(AppUser u) {
        Instant now = Instant.now();
        return Jwts.builder().subject(String.valueOf(u.getId())).claim("role", u.getRole().name())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttlHours, ChronoUnit.HOURS)))
                .signWith(key).compact();
    }

    public AuthUser parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthUser(Long.parseLong(c.getSubject()), c.get("role", String.class));
    }
}
