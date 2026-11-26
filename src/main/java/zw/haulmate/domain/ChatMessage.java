package zw.haulmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import static zw.haulmate.domain.Types.*;

@Entity @Table(name = "chat_messages") @Getter @Setter
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long loadId;
    private Long senderId;
    @Enumerated(EnumType.STRING) private MsgType msgType = MsgType.TEXT;
    private String body;
    private Instant createdAt = Instant.now();
    private Instant readAt;
}
