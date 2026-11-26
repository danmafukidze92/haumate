package zw.haulmate.repo;

import org.springframework.data.jpa.repository.*;
import zw.haulmate.domain.*;
import java.util.*;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

public interface PaymentRepo extends JpaRepository<Payment, Long> {
    Optional<Payment> findByReference(String ref);
    List<Payment> findByLoadId(Long loadId);
    List<Payment> findByStatusInAndCreatedAtAfter(Collection<PaymentStatus> st, Instant after);
    List<Payment> findAllByOrderByCreatedAtDesc();
}
