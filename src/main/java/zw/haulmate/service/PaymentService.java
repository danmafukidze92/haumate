package zw.haulmate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.haulmate.config.AppProps;
import zw.haulmate.domain.*;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Escrow-style payments through Paynow. Credentials (TEST and LIVE sets) and the active mode
 * are managed in Admin > Settings; switching to LIVE needs no redeploy.
 *  - Shipper pays after a driver is assigned -> funds held (load.paid = true)
 *  - On delivery (PIN confirmed) -> released to driver wallet minus commission
 *  - CASH loads: commission is debited from the driver's wallet on delivery
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepo payments; private final LoadRepo loads; private final UserRepo users;
    private final PaynowClient paynow; private final SettingsService settings; private final AppProps props;
    private final SimpMessagingTemplate ws;

    public PaymentService(PaymentRepo payments, LoadRepo loads, UserRepo users, PaynowClient paynow,
                          SettingsService settings, AppProps props, SimpMessagingTemplate ws) {
        this.payments = payments; this.loads = loads; this.users = users; this.paynow = paynow;
        this.settings = settings; this.props = props; this.ws = ws;
    }

    public PaynowClient.Credentials credentials() {
        String mode = settings.get("paynow.mode", "TEST").toUpperCase(Locale.ROOT);
        String p = "paynow." + ("LIVE".equals(mode) ? "live" : "test") + ".";
        PaynowClient.Credentials c = new PaynowClient.Credentials(settings.get(p + "integration_id"),
                settings.get(p + "integration_key"), settings.get(p + "auth_email"), mode);
        if (c.integrationId().isBlank() || c.integrationKey().isBlank())
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Online payments are not configured yet (" + mode + " credentials missing in Admin > Settings)");
        return c;
    }

    private BigDecimal commission(BigDecimal amount) {
        return amount.multiply(new BigDecimal(settings.get("commission.percent", "10")))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    @Transactional
    public Payment initiate(long payerId, long loadId, PaymentMethod method, String phone) {
        Load l = loads.findById(loadId).orElseThrow(() -> ApiException.notFound("Load not found"));
        if (!l.getShipperId().equals(payerId)) throw ApiException.forbidden("Only the shipper pays for a load");
        if (l.getAgreedPrice() == null) throw ApiException.bad("Pay after a driver has been assigned");
        if (l.isPaid()) throw ApiException.conflict("Already paid");
        if (method == PaymentMethod.CASH) throw ApiException.bad("Cash is paid to the driver on delivery");
        PaynowClient.Credentials c = credentials();
        AppUser payer = users.findById(payerId).orElseThrow();

        Payment p = new Payment();
        p.setLoadId(loadId); p.setPayerId(payerId); p.setAmount(l.getAgreedPrice()); p.setCurrency(l.getCurrency());
        p.setCommission(commission(l.getAgreedPrice())); p.setMethod(method); p.setMode(c.mode());
        p.setReference(l.getRef() + "-" + Long.toString(System.currentTimeMillis() % 1_000_000, 36).toUpperCase());

        String base = props.publicBaseUrl().replaceAll("/$", "");
        String returnUrl = base + "/payment/return?ref=" + p.getReference();
        String resultUrl = base + "/api/payments/paynow/result";
        String amount = l.getAgreedPrice().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String info = "HaulMate load " + l.getRef();

        Map<String, String> r = method == PaymentMethod.PAYNOW_WEB
                ? paynow.initiateWeb(c, p.getReference(), amount, info, returnUrl, resultUrl, payer.getEmail())
                : paynow.initiateMobile(c, p.getReference(), amount, info, returnUrl, resultUrl,
                    toLocalPhone(phone != null ? phone : payer.getPhone()), method.name().toLowerCase(Locale.ROOT));
        if (!"ok".equalsIgnoreCase(r.get("status"))) {
            p.setStatus(PaymentStatus.FAILED);
            p.setRawStatus(r.getOrDefault("error", "Error"));
            payments.save(p);
            throw ApiException.bad("Paynow: " + r.getOrDefault("error", "payment could not be started"));
        }
        p.setStatus(PaymentStatus.SENT);
        p.setPollUrl(r.get("pollurl"));
        p.setBrowserUrl(r.get("browserurl"));
        p.setInstructions(r.get("instructions"));
        p.setPaynowReference(r.get("paynowreference"));
        l.setPaymentMethod(method);
        loads.save(l);
        return payments.save(p);
    }

    private static String toLocalPhone(String phone) {
        String d = phone.replaceAll("[^0-9]", "");
        if (d.startsWith("263")) d = "0" + d.substring(3);
        return d;
    }

    /** Called by Paynow's server-to-server result URL. */
    @Transactional
    public void handleResult(LinkedHashMap<String, String> fields) {
        Payment p = payments.findByReference(fields.getOrDefault("reference", ""))
                .orElseThrow(() -> ApiException.notFound("Unknown reference"));
        String key = credentialsFor(p.getMode()).integrationKey();
        if (!PaynowClient.verify(fields, key)) throw ApiException.forbidden("Hash mismatch");
        apply(p, fields);
    }

    private PaynowClient.Credentials credentialsFor(String mode) {
        String pre = "paynow." + ("LIVE".equals(mode) ? "live" : "test") + ".";
        return new PaynowClient.Credentials(settings.get(pre + "integration_id"), settings.get(pre + "integration_key"),
                settings.get(pre + "auth_email"), mode);
    }

    @Transactional
    public Payment refresh(long paymentId) {
        Payment p = payments.findById(paymentId).orElseThrow();
        if (p.getPollUrl() == null) return p;
        apply(p, paynow.poll(p.getPollUrl(), credentialsFor(p.getMode()).integrationKey()));
        return p;
    }

    private void apply(Payment p, Map<String, String> f) {
        String raw = f.getOrDefault("status", "");
        p.setRawStatus(raw);
        if (f.get("paynowreference") != null) p.setPaynowReference(f.get("paynowreference"));
        PaymentStatus st = switch (raw.toLowerCase(Locale.ROOT)) {
            case "paid" -> PaymentStatus.PAID;
            case "awaiting delivery" -> PaymentStatus.AWAITING_DELIVERY;
            case "delivered" -> PaymentStatus.DELIVERED;
            case "cancelled" -> PaymentStatus.CANCELLED;
            case "refunded" -> PaymentStatus.REFUNDED;
            case "disputed" -> PaymentStatus.DISPUTED;
            case "created" -> PaymentStatus.CREATED;
            default -> p.getStatus();
        };
        boolean newlyPaid = (st == PaymentStatus.PAID || st == PaymentStatus.AWAITING_DELIVERY || st == PaymentStatus.DELIVERED)
                && !(p.getStatus() == PaymentStatus.PAID || p.getStatus() == PaymentStatus.AWAITING_DELIVERY || p.getStatus() == PaymentStatus.DELIVERED);
        p.setStatus(st);
        p.setUpdatedAt(Instant.now());
        payments.save(p);
        if (newlyPaid) {
            loads.findById(p.getLoadId()).ifPresent(l -> {
                l.setPaid(true);
                loads.save(l);
                if (l.getStatus() == LoadStatus.DELIVERED || l.getStatus() == LoadStatus.COMPLETED) release(l, p);
                ws.convertAndSend("/topic/loads/" + l.getId() + "/status",
                        (Object) Map.of("type", "paid", "loadId", l.getId(), "status", l.getStatus(), "data", Map.of("reference", p.getReference())));
            });
        }
        ws.convertAndSend("/topic/admin/payments", (Object) Map.of("reference", p.getReference(), "status", st));
    }

    /** Delivery confirmed: release escrow or charge commission for cash trips. */
    @Transactional
    public void onDelivered(Load l) {
        if (l.getPaymentMethod() == PaymentMethod.CASH) {
            AppUser d = users.findById(l.getDriverId()).orElseThrow();
            d.setWalletBalance(d.getWalletBalance().subtract(commission(l.getAgreedPrice())));
            users.save(d);
            return;
        }
        payments.findByLoadId(l.getId()).stream()
                .filter(p -> !p.isReleased() && (p.getStatus() == PaymentStatus.PAID || p.getStatus() == PaymentStatus.AWAITING_DELIVERY))
                .findFirst().ifPresent(p -> release(l, p));
    }

    private void release(Load l, Payment p) {
        if (p.isReleased() || l.getDriverId() == null) return;
        AppUser d = users.findById(l.getDriverId()).orElseThrow();
        d.setWalletBalance(d.getWalletBalance().add(p.getAmount().subtract(p.getCommission())));
        users.save(d);
        p.setReleased(true);
        payments.save(p);
    }

    @Transactional
    public void onCancelled(Load l) {
        payments.findByLoadId(l.getId()).stream().filter(p -> p.getStatus() == PaymentStatus.PAID && !p.isReleased())
                .forEach(p -> { p.setRawStatus("REFUND_REQUIRED"); payments.save(p); });
    }

    /** Safety net in case Paynow's result callback is missed. */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    public void pollPending() {
        for (Payment p : payments.findByStatusInAndCreatedAtAfter(List.of(PaymentStatus.SENT, PaymentStatus.CREATED),
                Instant.now().minus(2, ChronoUnit.DAYS))) {
            try { refresh(p.getId()); } catch (Exception e) { log.debug("poll {} failed: {}", p.getReference(), e.getMessage()); }
        }
    }
}
