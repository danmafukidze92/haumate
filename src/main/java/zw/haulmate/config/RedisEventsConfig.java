package zw.haulmate.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Bridges events published by the Celery worker (Redis PUBLISH on haulmate:events) to WebSocket clients.
 * Event JSON: {"dest":"/topic/...", "user": 12 (optional), "payload": {...}}
 *
 * The listener container is deliberately NOT a Spring bean, so a missing Redis never stops the app
 * from booting: it is started after startup and retried every 10 s until Redis is reachable.
 */
@Configuration
public class RedisEventsConfig {
    private static final Logger log = LoggerFactory.getLogger(RedisEventsConfig.class);

    @Bean
    ApplicationListener<ApplicationReadyEvent> startRedisEvents(RedisConnectionFactory cf, SimpMessagingTemplate ws,
                                                                ObjectMapper om, AppProps props) {
        return ev -> Thread.ofVirtual().name("redis-events-starter").start(() -> {
            while (true) {
                RedisMessageListenerContainer c = new RedisMessageListenerContainer();
                try {
                    c.setConnectionFactory(cf);
                    c.addMessageListener((msg, pattern) -> {
                        try {
                            JsonNode n = om.readTree(msg.getBody());
                            Object payload = om.treeToValue(n.get("payload"), Object.class);
                            if (n.hasNonNull("user")) ws.convertAndSendToUser(n.get("user").asText(), n.get("dest").asText(), payload);
                            else ws.convertAndSend(n.get("dest").asText(), payload);
                        } catch (Exception e) { log.warn("Bad worker event: {}", e.getMessage()); }
                    }, new ChannelTopic(props.eventsChannel()));
                    c.afterPropertiesSet();
                    c.start();
                    log.info("Subscribed to worker events on Redis");
                    return;
                } catch (Exception e) {
                    try { c.destroy(); } catch (Exception ignored) { }
                    log.warn("Redis not reachable ({}). Live alerts from the worker are paused; retrying in 10 s", e.getMessage());
                    try { Thread.sleep(10_000); } catch (InterruptedException ie) { return; }
                }
            }
        });
    }
}
