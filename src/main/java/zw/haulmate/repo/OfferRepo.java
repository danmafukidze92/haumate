package zw.haulmate.repo;

import org.springframework.data.jpa.repository.*;
import zw.haulmate.domain.*;
import java.util.*;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

public interface OfferRepo extends JpaRepository<Offer, Long> {
    List<Offer> findByLoadIdOrderByAmountAsc(Long loadId);
    Optional<Offer> findByLoadIdAndDriverIdAndStatus(Long loadId, Long driverId, OfferStatus st);
    List<Offer> findByLoadIdAndStatus(Long loadId, OfferStatus st);
}
