package zw.haulmate.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** In-app (WebSocket) + push (FCM via Celery worker) + SMS fallback (worker). */
@Service
public class NotificationService {
    private final SimpMessagingTemplate ws;
    private final CeleryClient celery;

    public NotificationService(SimpMessagingTemplate ws, CeleryClient celery) { this.ws = ws; this.celery = celery; }

    public void notify(long userId, String title, String body, Map<String, Object> data) {
        ws.convertAndSendToUser(String.valueOf(userId), "/queue/alerts", Map.of("title", title, "body", body, "data", data));
        celery.send("haulmate.notify.push", List.of(userId, title, body, data));
    }
}
