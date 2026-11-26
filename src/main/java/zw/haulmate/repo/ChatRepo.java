package zw.haulmate.repo;

import org.springframework.data.jpa.repository.*;
import zw.haulmate.domain.*;
import java.util.*;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

public interface ChatRepo extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByLoadIdOrderByCreatedAtAsc(Long loadId);
}
