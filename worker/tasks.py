"""HaulMate Celery worker: driver matching, KYC processing, push notifications.

Tasks are enqueued by the Java API (CeleryClient) on the Redis broker.
Realtime events go back to the API via Redis PUBLISH on 'haulmate:events',
which forwards them to WebSocket clients.
"""
import json
import logging
import os
import re
from datetime import datetime, date

import psycopg
import redis
from celery import Celery

import kyc_engine

app = Celery("haulmate")
app.config_from_object("celeryconfig")
log = logging.getLogger("haulmate")

def _db_kwargs():
    """Same variables as the Java app (.env): DB_URL=jdbc:postgresql://host:port/db, DB_USERNAME, DB_PASSWORD."""
    m = re.match(r"jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)", os.getenv("DB_URL", ""))
    host, port, name = (m.group(1), m.group(2) or "5432", m.group(3)) if m else ("localhost", "5432", "haulmate")
    return dict(host=host, port=port, dbname=name, user=os.getenv("DB_USERNAME", "haulmate"),
                password=os.getenv("DB_PASSWORD", ""), options=f"-c search_path={os.getenv('DB_SCHEMA', 'haulmate')}",
                autocommit=True)
R = redis.Redis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379/0"), decode_responses=True)
EVENTS = "haulmate:events"
CLASSES = ["PICKUP_1T", "LIGHT_3T", "MEDIUM_8T", "HEAVY_15T", "SUPER_30T"]

_fb = None


def db():
    return psycopg.connect(**_db_kwargs())


def setting(cur, key, default=""):
    cur.execute("select value from settings where key=%s", (key,))
    row = cur.fetchone()
    return row[0] if row and row[0] not in (None, "") else default


def publish(dest, payload, user=None):
    R.publish(EVENTS, json.dumps({"dest": dest, "user": user, "payload": payload}, default=str))


def can_carry(truck_class, required):
    try:
        t, r = CLASSES.index(truck_class), CLASSES.index(required)
    except ValueError:
        return False
    return t == r or t == r + 1


# ---------------------------------------------------------------- push
def firebase():
    global _fb
    if _fb is None:
        path = os.getenv("FIREBASE_CREDENTIALS", "/run/secrets/firebase.json")
        if os.path.exists(path):
            import firebase_admin
            from firebase_admin import credentials
            _fb = firebase_admin.initialize_app(credentials.Certificate(path))
        else:
            _fb = False
            log.warning("FIREBASE_CREDENTIALS not found; push notifications are logged only")
    return _fb


@app.task(name="haulmate.notify.push")
def push(user_id, title, body, data=None):
    data = {k: str(v) for k, v in (data or {}).items()}
    with db() as c, c.cursor() as cur:
        cur.execute("insert into notifications(user_id,title,body,data_json) values (%s,%s,%s,%s) returning id",
                    (user_id, title[:160], body[:500], json.dumps(data)))
        nid = cur.fetchone()[0]
        cur.execute("select fcm_token from users where id=%s", (user_id,))
        row = cur.fetchone()
        token = row[0] if row else None
        if not token or not firebase():
            log.info("push(%s) %s: %s", user_id, title, body)
            return
        from firebase_admin import messaging
        high = data.get("type") in ("call", "offer", "assigned", "new_load")
        msg = messaging.Message(
            token=token, data=data,
            notification=messaging.Notification(title=title, body=body),
            android=messaging.AndroidConfig(priority="high" if high else "normal",
                                            notification=messaging.AndroidNotification(channel_id="haulmate_" + data.get("type", "general"))),
            apns=messaging.APNSConfig(payload=messaging.APNSPayload(aps=messaging.Aps(sound="default"))))
        try:
            messaging.send(msg)
            cur.execute("update notifications set sent=true where id=%s", (nid,))
        except Exception as e:  # invalid/expired token
            log.warning("FCM send failed for user %s: %s", user_id, e)
            if "registration-token-not-registered" in str(e).lower() or "unregistered" in str(e).lower():
                cur.execute("update users set fcm_token=null where id=%s", (user_id,))


# ---------------------------------------------------------------- matching
@app.task(name="haulmate.matching.expand_search")
def expand_search(load_id, step):
    """Alert drivers in a widening radius: 10 km, 25, 50, 150, 400, then nationwide."""
    with db() as c, c.cursor() as cur:
        cur.execute("""select status, pickup_lat, pickup_lng, truck_class, refrigerated, offered_price, currency,
                              ref, pickup_address, dropoff_address, distance_km, weight_tonnes, shipper_id
                       from loads where id=%s""", (load_id,))
        row = cur.fetchone()
        if not row or row[0] != "OPEN":
            return
        (_, lat, lng, required, cold, price, cur_code, ref, frm, to, km, tonnes, shipper) = row
        steps = [int(x) for x in setting(cur, "matching.radius_steps_km", "10,25,50,150,400,1200").split(",")]
        wait = int(setting(cur, "matching.step_seconds", "180"))
        step = min(step, len(steps) - 1)
        radius = steps[step]

        found = R.geosearch("drivers:geo", longitude=lng, latitude=lat, radius=radius, unit="km",
                            sort="ASC", withdist=True) or []
        newly = 0
        for member, dist in found:
            h = R.hgetall(f"driver:{member}")
            if h.get("online") != "1" or h.get("busy") == "1" or not can_carry(h.get("truckClass"), required):
                continue
            if cold:
                cur.execute("select 1 from trucks where id=%s and body_type='REFRIGERATED'", (int(h.get("truckId", 0)),))
                if not cur.fetchone():
                    continue
            cur.execute("insert into load_alerts(load_id,driver_id,radius_km) values (%s,%s,%s) on conflict do nothing",
                        (load_id, int(member), radius))
            if cur.rowcount == 0:
                continue
            newly += 1
            payload = {"loadId": load_id, "ref": ref, "from": frm, "to": to, "distanceKm": km,
                       "pickupKmAway": round(float(dist), 1), "tonnes": float(tonnes),
                       "price": float(price), "currency": cur_code}
            publish("/queue/alerts", {"title": "New load nearby", "body": f"{frm} → {to}", "data": payload}, user=member)
            push.delay(int(member), f"New load {round(float(dist))} km away",
                       f"{frm} → {to} · {tonnes} t · {cur_code} {price}", {**payload, "type": "new_load"})

        cur.execute("update loads set search_radius_km=%s, drivers_alerted=drivers_alerted+%s where id=%s returning drivers_alerted",
                    (radius, newly, load_id))
        total = cur.fetchone()[0]
        label = "nationwide" if step == len(steps) - 1 else f"within {radius} km"
        publish(f"/topic/loads/{load_id}/status",
                {"type": "search", "loadId": load_id, "status": "OPEN",
                 "data": {"radiusKm": radius, "newlyAlerted": newly, "totalAlerted": total, "label": label}})
        if step == 0 or newly:
            publish("/topic/admin/loads", {"type": "search", "loadId": load_id, "data": {"radiusKm": radius, "alerted": total}})

        if step < len(steps) - 1:
            expand_search.apply_async((load_id, step + 1), countdown=wait)
        elif total == 0:
            push.delay(shipper, "No trucks found yet",
                       f"No matching trucks are online for {ref}. Try raising your price or wait — we keep the load visible.",
                       {"loadId": load_id, "type": "search"})


@app.task(name="haulmate.maintenance.expire_offers")
def expire_offers():
    with db() as c, c.cursor() as cur:
        cur.execute("""update offers set status='EXPIRED' where status='PENDING'
                       and created_at < now() - interval '2 hours'""")


# ---------------------------------------------------------------- KYC
@app.task(name="haulmate.kyc.process_user", bind=True, max_retries=3)
def process_user(self, user_id):
    root = os.getenv("STORAGE_DIR", "/data/uploads")
    with db() as c, c.cursor() as cur:
        threshold = float(setting(cur, "kyc.face_match_threshold", "0.36"))
        auto = setting(cur, "kyc.auto_approve", "false") == "true"
        cur.execute("select full_name, role from users where id=%s", (user_id,))
        full_name, role = cur.fetchone()
        cur.execute("""select id, doc_type, file_path, liveness_challenge from kyc_documents
                       where user_id=%s and status in ('PENDING','AUTO_FLAGGED') order by created_at""", (user_id,))
        docs = cur.fetchall()
        cur.execute("update kyc_documents set status='PROCESSING' where user_id=%s and status='PENDING'", (user_id,))

        selfie = next((os.path.join(root, d[2]) for d in docs if d[1] == "SELFIE"), None)
        frames = [(os.path.join(root, d[2]), d[3]) for d in docs if d[1] == "LIVENESS_FRAME"]
        all_ok = True
        updates_user = {}

        live = kyc_engine.liveness(selfie, frames) if selfie else {"passed": False, "reason": "no selfie"}

        for doc_id, doc_type, rel, _ in docs:
            path = os.path.join(root, rel)
            checks, extracted, ocr, score, live_ok = [], {}, None, None, None
            try:
                if doc_type in ("NATIONAL_ID", "PASSPORT", "DRIVERS_LICENCE", "VEHICLE_REG", "INSURANCE", "ZINARA_DISC"):
                    ocr = kyc_engine.ocr(path)
                    extracted = kyc_engine.parse(doc_type, ocr)
                    checks += kyc_engine.checks(doc_type, extracted, full_name)
                if doc_type in ("NATIONAL_ID", "PASSPORT", "DRIVERS_LICENCE") and selfie:
                    score = kyc_engine.face_match(path, selfie)
                    if score is None:
                        checks.append("FAIL: no face found on document")
                    elif score < threshold:
                        checks.append(f"FAIL: face match {score:.2f} < {threshold}")
                    else:
                        checks.append(f"OK: face match {score:.2f}")
                if doc_type == "SELFIE":
                    live_ok = live["passed"]
                    checks.append(("OK: " if live_ok else "FAIL: ") + "liveness " + live.get("reason", ""))
            except Exception as e:
                log.exception("KYC doc %s failed", doc_id)
                checks.append(f"FAIL: processing error {e}")

            ok = not any(x.startswith("FAIL") for x in checks)
            all_ok &= ok
            if doc_type == "NATIONAL_ID" and extracted.get("id_number"):
                updates_user["national_id_number"] = extracted["id_number"]
            if doc_type == "PASSPORT" and extracted.get("passport_number") and "national_id_number" not in updates_user:
                updates_user["national_id_number"] = extracted["passport_number"]
            if doc_type == "DRIVERS_LICENCE":
                if extracted.get("licence_number"):
                    updates_user["licence_number"] = extracted["licence_number"]
                if extracted.get("licence_class"):
                    updates_user["licence_class"] = extracted["licence_class"]
            cur.execute("""update kyc_documents set ocr_text=%s, extracted_json=%s, face_match_score=%s,
                           liveness_passed=%s, auto_checks=%s, status=%s, processed_at=now() where id=%s""",
                        (ocr, json.dumps(extracted, default=str) if extracted else None, score, live_ok,
                         "; ".join(checks) or None, "AUTO_PASSED" if ok else "AUTO_FLAGGED", doc_id))

        if not frames:
            all_ok = False
        for k, v in updates_user.items():
            cur.execute(f"update users set {k}=%s where id=%s", (v, user_id))
        if all_ok and auto:
            cur.execute("update users set kyc_status='VERIFIED', status='ACTIVE' where id=%s", (user_id,))
            cur.execute("update kyc_documents set status='APPROVED' where user_id=%s and status='AUTO_PASSED'", (user_id,))
            push.delay(user_id, "Verification approved", "Your account is verified. Welcome to HaulMate!", {"type": "kyc"})
        publish("/topic/admin/kyc", {"userId": user_id, "event": "processed", "autoPassed": all_ok})
