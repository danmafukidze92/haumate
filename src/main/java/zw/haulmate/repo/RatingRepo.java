package zw.haulmate.repo;

import org.springframework.data.jpa.repository.*;
import zw.haulmate.domain.*;
import java.util.*;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

public interface RatingRepo extends JpaRepository<Rating, Long> {
    boolean existsByLoadIdAndFromUser(Long loadId, Long fromUser);
}
