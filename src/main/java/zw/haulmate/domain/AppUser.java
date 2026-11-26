package zw.haulmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

@Entity @Table(name = "users") @Getter @Setter
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String fullName;
    private String phone;
    private String email;
    @com.fasterxml.jackson.annotation.JsonIgnore private String passwordHash;
    @Enumerated(EnumType.STRING) private Role role;
    @Enumerated(EnumType.STRING) private UserStatus status = UserStatus.PENDING_KYC;
    @Enumerated(EnumType.STRING) private KycStatus kycStatus = KycStatus.NONE;
    private String nationalIdNumber;
    private String licenceNumber;
    private String licenceClass;
    private String companyName;
    private Long fleetOwnerId;
    @com.fasterxml.jackson.annotation.JsonIgnore private String fcmToken;
    private BigDecimal ratingAvg = BigDecimal.ZERO;
    private int ratingCount;
    private BigDecimal walletBalance = BigDecimal.ZERO;
    private Instant createdAt = Instant.now();

    public boolean isDriverLike() { return role == Role.DRIVER || role == Role.FLEET_OWNER; }
}
