package zw.haulmate.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Local development: starts an in-process Redis on REDIS_PORT so the app runs without Docker.
 * Skipped when a Redis is already listening on that port, and absent in production
 * (embedded-redis is an optional dependency, so the class is not in the deployed jar).
 */
@Configuration
@ConditionalOnClass(name = "redis.embedded.RedisServer")
@ConditionalOnProperty(name = "haulmate.embedded-redis", havingValue = "true", matchIfMissing = true)
public class EmbeddedRedisConfig {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedRedisConfig.class);

    @Bean(destroyMethod = "stop")
    static EmbeddedRedis embeddedRedis(Environment env) {
        String host = env.getProperty("spring.data.redis.host", "localhost");
        int port = env.getProperty("spring.data.redis.port", Integer.class, 6379);
        return new EmbeddedRedis(host, port);
    }

    /** Make sure Redis is up before Spring creates the Redis connection factory. */
    @Bean
    static BeanFactoryPostProcessor redisStartsFirst() {
        return bf -> {
            for (String name : bf.getBeanNamesForType(org.springframework.data.redis.connection.RedisConnectionFactory.class, true, false)) {
                var def = bf.getBeanDefinition(name);
                String[] deps = def.getDependsOn();
                String[] all = deps == null ? new String[]{"embeddedRedis"} : java.util.stream.Stream.concat(
                        java.util.Arrays.stream(deps), java.util.stream.Stream.of("embeddedRedis")).toArray(String[]::new);
                def.setDependsOn(all);
            }
        };
    }

    public static class EmbeddedRedis {
        private redis.embedded.RedisServer server;

        EmbeddedRedis(String host, int port) {
            boolean local = host.equals("localhost") || host.equals("127.0.0.1");
            if (!local || inUse(port)) {
                log.info("Redis already available on {}:{} - embedded Redis not started", host, port);
                return;
            }
            try {
                server = new redis.embedded.RedisServer(port);
                server.start();
                log.info("Embedded Redis started on port {} (local development)", port);
            } catch (Exception e) {
                server = null;
                log.warn("Could not start embedded Redis on port {}: {}", port, e.getMessage());
            }
        }

        public void stop() {
            try { if (server != null) server.stop(); } catch (Exception ignored) { }
        }

        private static boolean inUse(int port) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
