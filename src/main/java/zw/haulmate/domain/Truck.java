package zw.haulmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

@Entity @Table(name = "trucks") @Getter @Setter
public class Truck {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long ownerId;
    private Long driverId;
    private String regNumber;
    private String make;
    private String model;
    @Enumerated(EnumType.STRING) private TruckClass truckClass;
    @Enumerated(EnumType.STRING) private BodyType bodyType;
    private BigDecimal capacityTonnes;
    private boolean verified;
    private boolean active = true;
    private Instant createdAt = Instant.now();
}
