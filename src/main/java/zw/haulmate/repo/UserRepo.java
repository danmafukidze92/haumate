package zw.haulmate.repo;

import org.springframework.data.jpa.repository.*;
import zw.haulmate.domain.*;
import java.util.*;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

public interface UserRepo extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByPhone(String phone);
    long countByRole(Role role);
    long countByKycStatus(KycStatus s);
    List<AppUser> findByKycStatusOrderByCreatedAtAsc(KycStatus s);
    List<AppUser> findAllByOrderByCreatedAtDesc();
}
