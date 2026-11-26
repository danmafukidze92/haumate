package zw.haulmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

@Entity @Table(name = "ratings") @Getter @Setter
public class Rating {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long loadId;
    private Long fromUser;
    private Long toUser;
    private int stars;
    private String comment;
    private Instant createdAt = Instant.now();
}
