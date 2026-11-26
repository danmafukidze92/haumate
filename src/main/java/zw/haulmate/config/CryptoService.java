package zw.haulmate.config;

import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM for secrets stored in the settings table (Paynow keys, TURN password). */
@Component
public class CryptoService {
    private static final String PREFIX = "enc:";
    private final SecretKeySpec key;
    private final SecureRandom rnd = new SecureRandom();

    public CryptoService(AppProps props) throws Exception {
        byte[] k = MessageDigest.getInstance("SHA-256").digest(props.encryptionKey().getBytes(StandardCharsets.UTF_8));
        this.key = new SecretKeySpec(k, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[12]; rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Cannot decrypt setting (encryption key changed?)", e); }
    }
}
