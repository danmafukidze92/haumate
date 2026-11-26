package zw.haulmate.repo;

import org.springframework.data.jpa.repository.*;
import zw.haulmate.domain.*;
import java.util.*;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

public interface TruckRepo extends JpaRepository<Truck, Long> {
    List<Truck> findByOwnerId(Long ownerId);
    Optional<Truck> findFirstByDriverIdAndActiveTrue(Long driverId);
    boolean existsByRegNumber(String reg);
}
