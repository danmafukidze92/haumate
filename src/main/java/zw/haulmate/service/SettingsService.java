package zw.haulmate.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.haulmate.config.CryptoService;
import zw.haulmate.domain.Setting;
import zw.haulmate.repo.SettingRepo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime-editable platform settings (Paynow credentials, pricing, matching). Secrets are AES-GCM encrypted. */
@Service
public class SettingsService {
    private final SettingRepo repo;
    private final CryptoService crypto;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SettingsService(SettingRepo repo, CryptoService crypto) { this.repo = repo; this.crypto = crypto; }

    public String get(String key) {
        return cache.computeIfAbsent(key, k -> repo.findById(k)
                .map(s -> s.isSecret() ? Objects.toString(crypto.decrypt(s.getValue()), "") : Objects.toString(s.getValue(), ""))
                .orElse(""));
    }
    public String get(String key, String def) { String v = get(key); return v.isBlank() ? def : v; }
    public double dbl(String key, double def) { try { return Double.parseDouble(get(key)); } catch (Exception e) { return def; } }
    public int integer(String key, int def) { try { return Integer.parseInt(get(key).trim()); } catch (Exception e) { return def; } }
    public boolean bool(String key) { return Boolean.parseBoolean(get(key)); }
    public BigDecimal money(String key, String def) { return new BigDecimal(get(key, def)); }

    public List<Setting> all() { return repo.findAllByOrderByKeyAsc(); }

    /** Secret values left blank in the form are kept unchanged. */
    @Transactional
    public void save(Map<String, String> values, String by) {
        values.forEach((k, v) -> repo.findById(k).ifPresent(s -> {
            if (s.isSecret() && (v == null || v.isBlank())) return;
            s.setValue(s.isSecret() ? crypto.encrypt(v.trim()) : (v == null ? "" : v.trim()));
            s.setUpdatedAt(Instant.now());
            s.setUpdatedBy(by);
            repo.save(s);
        }));
        cache.clear();
    }
}
