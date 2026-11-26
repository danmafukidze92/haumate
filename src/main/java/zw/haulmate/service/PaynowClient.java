package zw.haulmate.service;

import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Minimal Paynow Zimbabwe client (https://developers.paynow.co.zw).
 * Hash = uppercase hex SHA-512 of all field values (in order, excluding "hash") + integration key.
 */
@Component
public class PaynowClient {
    public static final String INITIATE_URL = "https://www.paynow.co.zw/interface/initiatetransaction";
    public static final String REMOTE_URL = "https://www.paynow.co.zw/interface/remotetransaction";

    public record Credentials(String integrationId, String integrationKey, String authEmail, String mode) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public static String hash(Map<String, String> fields, String key) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> { if (!"hash".equalsIgnoreCase(k)) sb.append(v == null ? "" : v); });
        sb.append(key);
        try {
            byte[] d = MessageDigest.getInstance("SHA-512").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02X", b));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static boolean verify(Map<String, String> fields, String key) {
        String h = fields.get("hash");
        return h != null && h.equalsIgnoreCase(hash(fields, key));
    }

    /** Parses a url-encoded Paynow response preserving field order (needed for hash verification). */
    public static LinkedHashMap<String, String> parse(String body) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (body == null) return m;
        for (String pair : body.trim().split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    /** Web checkout (card, EcoCash, OneMoney, InnBucks, ZimSwitch, etc. on Paynow's page). */
    public Map<String, String> initiateWeb(Credentials c, String reference, String amount, String info,
                                           String returnUrl, String resultUrl, String payerEmail) {
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("id", c.integrationId());
        f.put("reference", reference);
        f.put("amount", amount);
        f.put("additionalinfo", info);
        f.put("returnurl", returnUrl);
        f.put("resulturl", resultUrl);
        f.put("authemail", payerEmail == null ? "" : payerEmail);
        f.put("status", "Message");
        return post(INITIATE_URL, f, c.integrationKey());
    }

    /** Express checkout: USSD push to the payer's phone. method = ecocash | onemoney | innbucks. */
    public Map<String, String> initiateMobile(Credentials c, String reference, String amount, String info,
                                              String returnUrl, String resultUrl, String phone, String method) {
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("id", c.integrationId());
        f.put("reference", reference);
        f.put("amount", amount);
        f.put("additionalinfo", info);
        f.put("returnurl", returnUrl);
        f.put("resulturl", resultUrl);
        f.put("authemail", c.authEmail()); // Paynow requires the merchant email in test mode
        f.put("phone", phone);
        f.put("method", method);
        f.put("status", "Message");
        return post(REMOTE_URL, f, c.integrationKey());
    }

    public Map<String, String> poll(String pollUrl, String key) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(pollUrl)).timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            LinkedHashMap<String, String> m = parse(r.body());
            if (m.containsKey("hash") && !verify(m, key)) throw new IllegalStateException("Paynow poll hash mismatch");
            return m;
        } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalStateException("Paynow poll failed: " + e.getMessage(), e); }
    }

    private Map<String, String> post(String url, LinkedHashMap<String, String> f, String key) {
        f.put("hash", hash(f, key));
        String form = f.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            LinkedHashMap<String, String> m = parse(r.body());
            if ("ok".equalsIgnoreCase(m.get("status")) && !verify(m, key)) throw new IllegalStateException("Paynow response hash mismatch");
            return m;
        } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalStateException("Paynow unreachable: " + e.getMessage(), e); }
    }
}
