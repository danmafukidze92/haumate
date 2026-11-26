package zw.haulmate.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import zw.haulmate.config.AuthUser;
import zw.haulmate.domain.Payment;
import zw.haulmate.domain.Types.PaymentMethod;
import zw.haulmate.repo.PaymentRepo;
import zw.haulmate.service.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
public class PaymentController {
    private final PaymentService payments; private final PaymentRepo repo;
    public PaymentController(PaymentService payments, PaymentRepo repo) { this.payments = payments; this.repo = repo; }

    public record PayReq(long loadId, PaymentMethod method, String phone) {}

    /** PAYNOW_WEB returns browserUrl to open; ECOCASH/ONEMONEY/INNBUCKS push a USSD prompt to the phone. */
    @PostMapping("/api/payments")
    public Payment pay(@AuthenticationPrincipal AuthUser me, @RequestBody PayReq r) {
        return payments.initiate(me.id(), r.loadId(), r.method(), r.phone());
    }

    @GetMapping("/api/payments/{id}")
    public Payment status(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        Payment p = repo.findById(id).orElseThrow(() -> ApiException.notFound("Payment not found"));
        if (!p.getPayerId().equals(me.id()) && !"ADMIN".equals(me.role())) throw ApiException.forbidden("Not yours");
        return payments.refresh(id);
    }

    /** Paynow server-to-server callback. Body is url-encoded; read raw to keep field order for the hash. */
    @PostMapping("/api/payments/paynow/result")
    public ResponseEntity<String> result(HttpServletRequest req) throws Exception {
        String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        LinkedHashMap<String, String> fields = PaynowClient.parse(body);
        if (fields.isEmpty()) req.getParameterMap().forEach((k, v) -> fields.put(k.toLowerCase(Locale.ROOT), v[0]));
        payments.handleResult(fields);
        return ResponseEntity.ok("OK");
    }
}
