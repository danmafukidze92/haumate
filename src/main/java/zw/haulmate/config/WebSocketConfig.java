package zw.haulmate.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket at /ws.
 *  - /topic/loads/{id}/location  live truck position + ETA
 *  - /topic/loads/{id}/chat      chat messages
 *  - /topic/loads/{id}/status    status changes, offers, search progress
 *  - /user/queue/alerts          per-user alerts (new nearby loads, offer accepted)
 *  - /user/queue/call            WebRTC call signalling
 *  - /topic/admin/live           admin live map feed
 * Clients send CONNECT with header Authorization: Bearer <jwt>.
 * For scale-out across several API nodes, swap enableSimpleBroker for a STOMP relay (RabbitMQ).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtService jwt;
    public WebSocketConfig(JwtService jwt) { this.jwt = jwt; }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry r) {
        r.addEndpoint("/ws").setAllowedOriginPatterns("*");
        r.addEndpoint("/ws-sockjs").setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry r) {
        r.enableSimpleBroker("/topic", "/queue").setHeartbeatValue(new long[]{10000, 10000})
         .setTaskScheduler(new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler() {{ initialize(); }});
        r.setApplicationDestinationPrefixes("/app");
        r.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration reg) {
        reg.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (acc != null && StompCommand.CONNECT.equals(acc.getCommand())) {
                    String h = acc.getFirstNativeHeader("Authorization");
                    if (h == null || !h.startsWith("Bearer ")) throw new IllegalArgumentException("Missing token");
                    acc.setUser(jwt.parse(h.substring(7)));
                }
                return message;
            }
        });
    }
}
