package zw.haulmate.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import zw.haulmate.domain.AppUser;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.UserRepo;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(AppProps.class)
public class DataSeeder {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    ApplicationRunner seedAdmin(UserRepo users, PasswordEncoder enc, AppProps props) {
        return args -> {
            if (users.findByPhone(props.admin().phone()).isEmpty()) {
                AppUser a = new AppUser();
                a.setFullName(props.admin().name());
                a.setPhone(props.admin().phone());
                a.setPasswordHash(enc.encode(props.admin().password()));
                a.setRole(Role.ADMIN);
                a.setStatus(UserStatus.ACTIVE);
                a.setKycStatus(KycStatus.VERIFIED);
                users.save(a);
                log.warn("Seeded admin {} - change the password after first login", props.admin().phone());
            }
        };
    }
}
