package zw.haulmate.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import zw.haulmate.domain.*;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.*;
import java.util.*;

/** Per-load chat between shipper and assigned driver, plus WebRTC call signalling relay. */
@Service
public class ChatService {
    private final ChatRepo chats; private final LoadService loads; private final SimpMessagingTemplate ws;
    private final NotificationService notify; private final UserRepo users;

    public ChatService(ChatRepo chats, LoadService loads, SimpMessagingTemplate ws, NotificationService notify, UserRepo users) {
        this.chats = chats; this.loads = loads; this.ws = ws; this.notify = notify; this.users = users;
    }

    private Load chatLoad(long loadId, long userId, String role) {
        Load l = loads.getForParticipant(loadId, userId, role);
        if (l.getDriverId() == null && !"ADMIN".equals(role)) throw ApiException.bad("Chat opens once a driver is assigned");
        return l;
    }

    public long counterpart(Load l, long userId) { return userId == l.getShipperId() ? l.getDriverId() : l.getShipperId(); }

    public ChatMessage send(long loadId, long userId, String role, String body, MsgType type) {
        if (body == null || body.isBlank() || body.length() > 2000) throw ApiException.bad("Message must be 1-2000 characters");
        Load l = chatLoad(loadId, userId, role);
        ChatMessage m = new ChatMessage();
        m.setLoadId(loadId); m.setSenderId(userId); m.setBody(body.trim()); m.setMsgType(type == null ? MsgType.TEXT : type);
        chats.save(m);
        ws.convertAndSend("/topic/loads/" + loadId + "/chat", m);
        if (!"ADMIN".equals(role)) {
            String name = users.findById(userId).map(AppUser::getFullName).orElse("");
            notify.notify(counterpart(l, userId), name, m.getMsgType() == MsgType.IMAGE ? "Sent a photo" : m.getBody(),
                    Map.of("loadId", loadId, "type", "chat"));
        }
        return m;
    }

    public List<ChatMessage> history(long loadId, long userId, String role) {
        chatLoad(loadId, userId, role);
        return chats.findByLoadIdOrderByCreatedAtAsc(loadId);
    }

    /** Relays WebRTC signalling (ring/offer/answer/ice/hangup) to the other party on the load. */
    public void signal(long loadId, long userId, String role, Map<String, Object> msg) {
        Load l = chatLoad(loadId, userId, role);
        long to = counterpart(l, userId);
        Map<String, Object> out = new LinkedHashMap<>(msg);
        out.put("loadId", loadId); out.put("from", userId);
        ws.convertAndSendToUser(String.valueOf(to), "/queue/call", out);
        String type = String.valueOf(msg.get("type"));
        if ("ring".equals(type)) {
            String name = users.findById(userId).map(AppUser::getFullName).orElse("");
            notify.notify(to, "Incoming call", name + " is calling about " + l.getRef(),
                    Map.of("loadId", loadId, "type", "call", "from", userId));
        }
        if ("hangup".equals(type) && msg.get("durationSec") != null) {
            ChatMessage m = new ChatMessage();
            m.setLoadId(loadId); m.setSenderId(userId); m.setMsgType(MsgType.CALL_LOG);
            m.setBody("Voice call, " + msg.get("durationSec") + "s");
            chats.save(m);
            ws.convertAndSend("/topic/loads/" + loadId + "/chat", m);
        }
    }
}
