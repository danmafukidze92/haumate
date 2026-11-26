package zw.haulmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

@Entity @Table(name = "location_pings") @Getter @Setter
public class LocationPing {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long driverId;
    private Long loadId;
    private double lat;
    private double lng;
    private Double speedKmh;
    private Double heading;
    private Instant recordedAt;
}
