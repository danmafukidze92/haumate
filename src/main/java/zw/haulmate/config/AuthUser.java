package zw.haulmate.config;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.security.Principal;
import java.util.List;

/** Authenticated app user. getName() returns the user id so STOMP user destinations route by id. */
public record AuthUser(Long id, String role) implements Principal {
    @Override public String getName() { return String.valueOf(id); }

    public UsernamePasswordAuthenticationToken toAuthentication() {
        return new UsernamePasswordAuthenticationToken(this, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
