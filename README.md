# HaulMate (codename Cherai) — Zimbabwe freight marketplace

Shippers post loads (1–30 t) with a recommended price; verified drivers accept or counter-offer; the
load is locked to the chosen driver, tracked live by phone GPS, paid via Paynow escrow, released on PIN-confirmed delivery.

| Part | Path | Stack |
|---|---|---|
| API, WebSocket, Paynow, admin panel | project root (`pom.xml`, `src/`) | Java 21, Spring Boot 3.3, Maven, PostgreSQL, Redis, Thymeleaf (StarAdmin 2 UI) |
| Background jobs | `worker/` | Python Celery on Redis: driver matching, KYC OCR/face/liveness, Firebase push |
| Deploy | `Jenkinsfile` | Same as MRPCZ: Jenkins → Docker on Contabo (`ppe-shared-net`), existing nginx → 127.0.0.1:8670 |

Public URL: https://cherai.nexigent-labs.co.zw · App port: **8670** (local and VPS)

## Database (one-time)
Same server and driver as MRPCZ: `jdbc:postgresql://pgadmin.nexigent-labs.co.zw:5432/haulmate`, user `haulmate`.
Run `scripts/create-db.sql` once as a superuser (pgAdmin or `docker exec call_center_demo psql -U postgres`).
Flyway creates the tables in schema `haulmate` on first start.

## Run locally
1. `docker compose up -d --build` (Redis on :6380 + Celery worker)
2. Run `HaulmateApplication` in IntelliJ (reads `.env` in the project root, port 8670), or `mvn spring-boot:run`
3. Tests: `mvn test`
- Admin panel: http://localhost:8670/admin (`ADMIN_PHONE` / `ADMIN_PASSWORD` from `.env`)
- API docs (for the mobile apps): http://localhost:8670/api/docs

## Deploy
Push to GitHub → Jenkins builds both images and runs `haulmate-app` (127.0.0.1:8670), `haulmate-worker`
and `haulmate-redis` on `ppe-shared-net`. Add a `cherai.nexigent-labs.co.zw` server block to the existing nginx → `127.0.0.1:8670`
(include WebSocket upgrade headers for `/ws`). Put the Firebase JSON in the `haulmate-data` volume at `secrets/firebase.json`.

## Paynow
Admin › Settings & Paynow holds TEST and LIVE credentials (encrypted with `APP_ENCRYPTION_KEY`) and the active mode.
Switching to LIVE needs no redeploy. Result URL to give Paynow: `https://cherai.nexigent-labs.co.zw/api/payments/paynow/result`.
Test mode: EcoCash test numbers 0771111111 (success), 0772222222 (delayed), 0773333333 (cancelled), 0774444444 (insufficient funds).

## Firebase
Local: `secrets/firebase.json`. VPS: `secrets/firebase.json` inside the `haulmate-data` Docker volume.

## Realtime contract (STOMP over `/ws`, header `Authorization: Bearer <jwt>`)
| Destination | Direction | Purpose |
|---|---|---|
| `/app/location` | driver → server | `{lat,lng,speedKmh,heading,ts}` every 5–10 s on a trip |
| `/topic/loads/{id}/location` | → shipper | live truck position, ETA, remaining km |
| `/topic/loads/{id}/status` | → both | offers, search radius progress, status changes, payment |
| `/app/loads/{id}/chat` · `/topic/loads/{id}/chat` | both | chat |
| `/app/loads/{id}/call` · `/user/queue/call` | both | WebRTC voice call signalling (ICE servers: `GET /api/calls/ice-servers`) |
| `/user/queue/alerts` | → user | new loads nearby, offer accepted, etc. (also sent as FCM push) |

## Trip lifecycle
`OPEN → ASSIGNED → EN_ROUTE_PICKUP → AT_PICKUP → LOADED → IN_TRANSIT → DELIVERED (PIN) → COMPLETED (rated)`

Driver search widens 10 → 25 → 50 → 150 → 400 km → nationwide, every 3 min (editable in Settings).

## Production checklist
- Replace the `CHANGE-ME` values for `JWT_SECRET`, `APP_ENCRYPTION_KEY`, `ADMIN_PASSWORD` in `.env` before the first deploy
- Install a TURN server (coturn) and enter it in Settings, or calls fail between mobile networks
- Optional: self-host OSRM with Zimbabwe OSM data for road distances (Settings › OSRM URL)
