package zw.haulmate.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import zw.haulmate.domain.*;
import zw.haulmate.domain.Types.*;
import zw.haulmate.repo.*;
import java.time.Instant;
import java.util.*;

/**
 * KYC flow:
 *  1. App captures ID / passport / licence (live camera preferred, upload allowed) -> POST /api/kyc/documents
 *  2. App runs an on-device liveness challenge (ML Kit: blink, turn head) and uploads SELFIE + LIVENESS_FRAMEs
 *  3. POST /api/kyc/submit -> Celery worker runs OCR, MRZ/ID parsing, expiry checks, face match, liveness checks
 *  4. Worker marks docs AUTO_PASSED / AUTO_FLAGGED; admin approves/rejects in the panel (or auto-approve if enabled)
 */
@Service
public class KycService {
    private final KycRepo docs;
    private final UserRepo users;
    private final StorageService storage;
    private final CeleryClient celery;
    private final SimpMessagingTemplate ws;
    private final NotificationService notify;

    public KycService(KycRepo docs, UserRepo users, StorageService storage, CeleryClient celery,
                      SimpMessagingTemplate ws, NotificationService notify) {
        this.docs = docs; this.users = users; this.storage = storage; this.celery = celery; this.ws = ws; this.notify = notify;
    }

    public KycDocument upload(long userId, DocType type, CaptureMode mode, String challenge, MultipartFile file) {
        KycDocument d = new KycDocument();
        d.setUserId(userId);
        d.setDocType(type);
        d.setCaptureMode(mode == null ? CaptureMode.UPLOAD : mode);
        d.setLivenessChallenge(challenge);
        d.setFilePath(storage.save(file, "kyc/" + userId));
        return docs.save(d);
    }

    @Transactional
    public AppUser submit(long userId) {
        AppUser u = users.findById(userId).orElseThrow();
        List<KycDocument> list = docs.findByUserIdOrderByCreatedAtAsc(userId);
        Set<DocType> have = EnumSet.noneOf(DocType.class);
        list.forEach(d -> have.add(d.getDocType()));
        if (!have.contains(DocType.NATIONAL_ID) && !have.contains(DocType.PASSPORT))
            throw ApiException.bad("Upload a national ID or passport");
        if (!have.contains(DocType.SELFIE)) throw ApiException.bad("Live selfie is required");
        if (u.isDriverLike() && u.getRole() == Role.DRIVER && !have.contains(DocType.DRIVERS_LICENCE))
            throw ApiException.bad("Driver's licence is required for drivers");
        u.setKycStatus(KycStatus.PENDING);
        users.save(u);
        celery.send("haulmate.kyc.process_user", List.of(userId));
        ws.convertAndSend("/topic/admin/kyc", (Object) Map.of("userId", userId, "name", u.getFullName(), "event", "submitted"));
        return u;
    }

    @Transactional
    public void decide(long userId, boolean approve, String note, long adminId) {
        AppUser u = users.findById(userId).orElseThrow();
        for (KycDocument d : docs.findByUserIdOrderByCreatedAtAsc(userId)) {
            if (d.getStatus() == DocStatus.APPROVED || d.getStatus() == DocStatus.REJECTED) continue;
            d.setStatus(approve ? DocStatus.APPROVED : DocStatus.REJECTED);
            d.setReviewerId(adminId);
            d.setReviewNote(note);
            d.setProcessedAt(Instant.now());
        }
        u.setKycStatus(approve ? KycStatus.VERIFIED : KycStatus.REJECTED);
        if (approve && u.getStatus() == UserStatus.PENDING_KYC) u.setStatus(UserStatus.ACTIVE);
        users.save(u);
        notify.notify(userId, approve ? "Verification approved" : "Verification needs attention",
                approve ? "Your account is verified. You can start using HaulMate." :
                        "Please re-submit your documents: " + (note == null ? "" : note), Map.of("type", "kyc"));
    }
}
