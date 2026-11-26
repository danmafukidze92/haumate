package zw.haulmate.repo;

import org.springframework.data.jpa.repository.*;
import zw.haulmate.domain.*;
import java.util.*;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

public interface LoadRepo extends JpaRepository<Load, Long> {
    List<Load> findByShipperIdOrderByCreatedAtDesc(Long shipperId);
    List<Load> findByDriverIdOrderByCreatedAtDesc(Long driverId);
    List<Load> findByStatusIn(Collection<LoadStatus> s);
    List<Load> findAllByOrderByCreatedAtDesc();
    long countByStatus(LoadStatus s);
    Optional<Load> findFirstByDriverIdAndStatusIn(Long driverId, Collection<LoadStatus> s);

    @Query("select l from Load l where l.status = zw.haulmate.domain.Types.LoadStatus.OPEN " +
           "and l.pickupLat between :minLat and :maxLat and l.pickupLng between :minLng and :maxLng")
    List<Load> findOpenInBox(double minLat, double maxLat, double minLng, double maxLng);

    @Query("select coalesce(sum(l.agreedPrice),0) from Load l where l.status in :st and l.deliveredAt >= :since")
    java.math.BigDecimal gmvSince(Collection<LoadStatus> st, Instant since);
}
