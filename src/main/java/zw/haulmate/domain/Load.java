package zw.haulmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

@Entity @Table(name = "loads") @Getter @Setter
public class Load {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String ref;
    private Long shipperId;
    private String pickupAddress;
    private double pickupLat;
    private double pickupLng;
    private String dropoffAddress;
    private double dropoffLat;
    private double dropoffLng;
    private double distanceKm;
    private String cargoType;
    private String cargoDescription;
    private BigDecimal weightTonnes;
    @Enumerated(EnumType.STRING) private TruckClass truckClass;
    @Enumerated(EnumType.STRING) private BodyType bodyType;
    private boolean hazardous;
    private boolean fragile;
    private boolean refrigerated;
    private boolean urgent;
    private Instant pickupFrom;
    private Instant pickupTo;
    private BigDecimal recommendedPrice;
    private BigDecimal offeredPrice;
    private BigDecimal agreedPrice;
    private String currency = "USD";
    @Enumerated(EnumType.STRING) private LoadStatus status = LoadStatus.OPEN;
    private int searchRadiusKm;
    private int driversAlerted;
    private Long driverId;
    private Long truckId;
    private Integer etaPickupMinutes;
    private Integer etaDropoffMinutes;
    @Enumerated(EnumType.STRING) private PaymentMethod paymentMethod = PaymentMethod.CASH;
    private boolean paid;
    @com.fasterxml.jackson.annotation.JsonIgnore private String deliveryPin;
    private String pickupPhoto;
    private String deliveryPhoto;
    private String cancelReason;
    private Instant createdAt = Instant.now();
    private Instant assignedAt;
    private Instant pickedUpAt;
    private Instant deliveredAt;
}
