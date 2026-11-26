package zw.haulmate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "haulmate")
public record AppProps(String publicBaseUrl, String jwtSecret, int jwtTtlHours, String encryptionKey,
                       String storageDir, Admin admin, Celery celery, String eventsChannel) {
    public record Admin(String phone, String password, String name) {}
    public record Celery(String queue) {}
}
