package zw.haulmate.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Lets the mobile app find this server on the local network without a fixed IP.
 * The app broadcasts "HAULMATE?" on UDP 8671; we answer with our own address on that
 * network (e.g. http://192.168.40.10:8670), whatever IP the PC has been given today.
 * Off in Docker/production (DISCOVERY_ENABLED=false) where the public domain is used.
 */
@Slf4j
@Component
public class LanDiscovery {
    @Value("${haulmate.discovery.enabled:true}") boolean enabled;
    @Value("${haulmate.discovery.port:8671}") int udpPort;
    @Value("${server.port:8670}") int httpPort;
    private volatile DatagramSocket socket;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) return;
        Thread t = new Thread(this::run, "lan-discovery");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        try (DatagramSocket s = new DatagramSocket(null)) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(udpPort));
            socket = s;
            log.info("LAN discovery listening on UDP {}", udpPort);
            byte[] buf = new byte[64];
            while (!s.isClosed()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                if (!msg.startsWith("HAULMATE?")) continue;
                String ip = localAddressFacing(p.getAddress());
                if (ip == null) continue;
                byte[] reply = ("http://" + ip + ":" + httpPort).getBytes(StandardCharsets.UTF_8);
                s.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
            }
        } catch (Exception e) {
            if (socket != null && !socket.isClosed()) log.warn("LAN discovery stopped: {}", e.getMessage());
        }
    }

    /** The IP of this machine on the same network as the phone that asked. */
    private static String localAddressFacing(InetAddress phone) {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(phone, 9);
            InetAddress a = probe.getLocalAddress();
            return a == null || a.isAnyLocalAddress() ? null : a.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    @PreDestroy
    void stop() {
        if (socket != null) socket.close();
    }
}
