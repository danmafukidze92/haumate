package zw.haulmate.api;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import zw.haulmate.config.AuthUser;
import zw.haulmate.domain.ChatMessage;
import zw.haulmate.domain.Types.MsgType;
import zw.haulmate.service.*;
import java.security.Principal;
import java.util.*;

@Controller
public class ChatController {
    private final ChatService chat; private final SettingsService settings;
    public ChatController(ChatService chat, SettingsService settings) { this.chat = chat; this.settings = settings; }

    @GetMapping("/api/loads/{id}/chat") @ResponseBody
    public List<ChatMessage> history(@AuthenticationPrincipal AuthUser me, @PathVariable long id) { return chat.history(id, me.id(), me.role()); }

    @PostMapping("/api/loads/{id}/chat") @ResponseBody
    public ChatMessage send(@AuthenticationPrincipal AuthUser me, @PathVariable long id, @RequestBody Map<String, String> b) {
        return chat.send(id, me.id(), me.role(), b.get("body"), MsgType.TEXT);
    }

    /** SEND /app/loads/{id}/chat {"body":"..."} */
    @MessageMapping("/loads/{id}/chat")
    public void wsSend(Principal p, @DestinationVariable long id, @Payload Map<String, String> b) {
        AuthUser u = (AuthUser) p;
        chat.send(id, u.id(), u.role(), b.get("body"), MsgType.TEXT);
    }

    /**
     * In-app voice calls (WebRTC, peer-to-peer audio, numbers stay private).
     * SEND /app/loads/{id}/call {"type":"ring|offer|answer|ice|accept|reject|hangup", "sdp":..., "candidate":..., "durationSec":..}
     * The other party receives it on /user/queue/call.
     */
    @MessageMapping("/loads/{id}/call")
    public void signal(Principal p, @DestinationVariable long id, @Payload Map<String, Object> msg) {
        AuthUser u = (AuthUser) p;
        chat.signal(id, u.id(), u.role(), msg);
    }

    /** ICE servers for WebRTC. A TURN server (e.g. coturn on the VPS) is needed for calls across mobile carrier NAT. */
    @GetMapping("/api/calls/ice-servers") @ResponseBody
    public List<Map<String, Object>> ice() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of("urls", settings.get("webrtc.stun_url", "stun:stun.l.google.com:19302")));
        if (!settings.get("webrtc.turn_url").isBlank())
            out.add(Map.of("urls", settings.get("webrtc.turn_url"), "username", settings.get("webrtc.turn_username"),
                    "credential", settings.get("webrtc.turn_password")));
        return out;
    }
}
