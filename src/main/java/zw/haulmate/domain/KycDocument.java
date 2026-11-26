package zw.haulmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

@Entity @Table(name = "kyc_documents") @Getter @Setter
public class KycDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long userId;
    @Enumerated(EnumType.STRING) private DocType docType;
    @Enumerated(EnumType.STRING) private CaptureMode captureMode;
    private String filePath;
    private String livenessChallenge;
    private String ocrText;
    private String extractedJson;
    private Double faceMatchScore;
    private Boolean livenessPassed;
    private String autoChecks;
    @Enumerated(EnumType.STRING) private DocStatus status = DocStatus.PENDING;
    private Long reviewerId;
    private String reviewNote;
    private Instant createdAt = Instant.now();
    private Instant processedAt;
}
