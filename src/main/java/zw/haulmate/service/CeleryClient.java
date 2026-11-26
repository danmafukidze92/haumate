package zw.haulmate.service;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import zw.haulmate.config.AppProps;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Enqueues tasks for the Python Celery worker by writing Celery protocol v2 messages
 * directly to the Redis broker list (same format kombu uses). No Python needed on the Java side.
 */
@Component
public class CeleryClient {
    private static final Logger log = LoggerFactory.getLogger(CeleryClient.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final String queue;

    public CeleryClient(StringRedisTemplate redis, ObjectMapper om, AppProps props) {
        this.redis = redis; this.om = om; this.queue = props.celery().queue();
    }

    public String send(String task, List<?> args) { return send(task, args, 0); }

    public String send(String task, List<?> args, int countdownSeconds) {
        String id = UUID.randomUUID().toString();
        try {
            List<Object> body = List.of(args, Map.of(), Map.of("callbacks", "", "errbacks", "", "chain", "", "chord", ""));
            String bodyJson = om.writeValueAsString(body).replace("\"\"", "null");
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("lang", "py");
            headers.put("task", task);
            headers.put("id", id);
            headers.put("root_id", id);
            headers.put("parent_id", null);
            headers.put("group", null);
            headers.put("retries", 0);
            headers.put("eta", countdownSeconds > 0 ? Instant.now().plusSeconds(countdownSeconds).toString() : null);
            headers.put("expires", null);
            headers.put("timelimit", Arrays.asList(null, null));
            headers.put("argsrepr", om.writeValueAsString(args));
            headers.put("kwargsrepr", "{}");
            headers.put("origin", "haulmate-java");
            headers.put("ignore_result", true);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("correlation_id", id);
            props.put("reply_to", "");
            props.put("delivery_mode", 2);
            props.put("delivery_info", Map.of("exchange", "", "routing_key", queue));
            props.put("priority", 0);
            props.put("body_encoding", "base64");
            props.put("delivery_tag", UUID.randomUUID().toString());
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("body", Base64.getEncoder().encodeToString(bodyJson.getBytes(StandardCharsets.UTF_8)));
            msg.put("content-encoding", "utf-8");
            msg.put("content-type", "application/json");
            msg.put("headers", headers);
            msg.put("properties", props);
            redis.opsForList().leftPush(queue, om.writeValueAsString(msg));
        } catch (Exception e) {
            log.error("Failed to enqueue {}: {}", task, e.getMessage());
        }
        return id;
    }
}
