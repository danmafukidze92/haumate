package zw.haulmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

@Entity @Table(name = "settings") @Getter @Setter
public class Setting {
    @Id private String key;
    private String value;
    private boolean secret;
    private Instant updatedAt = Instant.now();
    private String updatedBy;
}
