package zw.haulmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

@Entity @Table(name = "payments") @Getter @Setter
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long loadId;
    private Long payerId;
    private BigDecimal amount;
    private BigDecimal commission = BigDecimal.ZERO;
    private String currency = "USD";
    @Enumerated(EnumType.STRING) private PaymentMethod method;
    private String mode;
    private String reference;
    private String paynowReference;
    @com.fasterxml.jackson.annotation.JsonIgnore private String pollUrl;
    private String browserUrl;
    private String instructions;
    @Enumerated(EnumType.STRING) private PaymentStatus status = PaymentStatus.CREATED;
    private String rawStatus;
    private boolean released;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
