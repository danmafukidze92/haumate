package zw.haulmate.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.haulmate.config.AuthUser;
import zw.haulmate.domain.*;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.KycRepo;
import zw.haulmate.service.KycService;
import java.util.List;

@RestController
@RequestMapping("/api/kyc")
public class KycController {
    private final KycService kyc; private final KycRepo docs;
    public KycController(KycService kyc, KycRepo docs) { this.kyc = kyc; this.docs = docs; }

    /** multipart: file, docType, captureMode=LIVE|UPLOAD, livenessChallenge (for LIVENESS_FRAME) */
    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    public KycDocument upload(@AuthenticationPrincipal AuthUser me, @RequestParam DocType docType,
                              @RequestParam(defaultValue = "UPLOAD") CaptureMode captureMode,
                              @RequestParam(required = false) String livenessChallenge,
                              @RequestPart MultipartFile file) {
        return kyc.upload(me.id(), docType, captureMode, livenessChallenge, file);
    }

    @GetMapping("/documents")
    public List<KycDocument> mine(@AuthenticationPrincipal AuthUser me) { return docs.findByUserIdOrderByCreatedAtAsc(me.id()); }

    @PostMapping("/submit")
    public AppUser submit(@AuthenticationPrincipal AuthUser me) { return kyc.submit(me.id()); }
}
