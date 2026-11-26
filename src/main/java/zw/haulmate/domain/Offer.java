package zw.haulmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

@Entity @Table(name = "offers") @Getter @Setter
public class Offer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long loadId;
    private Long driverId;
    private Long truckId;
    private BigDecimal amount;
    private boolean counter;
    @Enumerated(EnumType.STRING) private OfferStatus status = OfferStatus.PENDING;
    private Double distanceToPickupKm;
    private Integer etaMinutes;
    private Instant createdAt = Instant.now();
}
