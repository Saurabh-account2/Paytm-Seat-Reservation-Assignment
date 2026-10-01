# Seat Reservation at Scale

A high-concurrency seat reservation service built for the Paytm Money Backend Engineering take-home exercise. Designed to never double-sell a seat, never violate per-user limits, and never double-charge an idempotent retry — even under a 20,000-request stampede.

## Tech Stack

- **Java 21** + **Spring Boot 3.3**
- **PostgreSQL 16** (single source of truth)
- **Flyway** (schema migrations)
- **Micrometer + Prometheus** (metrics)
- **Logstash Logback Encoder** (structured JSON logs)
- **Docker + Docker Compose**

## Quick Start (Docker Compose)

```bash
# Clone and start
git clone <repo-url> && cd seat-reservation
docker compose up --build -d

# Verify health
curl http://localhost:8080/actuator/health/readiness

# Run the burst test
pip install aiohttp
python3 burst.py http://localhost:8080
```

## Quick Start (Local Dev)

```bash
# Start PostgreSQL
docker run -d --name seatdb \
  -e POSTGRES_DB=seatdb -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 postgres:16-alpine

# Build and run
./mvnw spring-boot:run
```

## API Reference

### 1. Create a Show (Admin)

```bash
curl -X POST http://localhost:8080/shows \
  -H "Authorization: Bearer admin-secret" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "friday-night",
    "seats": ["A1","A2","A3","A4","A5"],
    "price_paise": 25000,
    "per_user_limit": 4
  }'
```

### 2. Reserve Seats (Authenticated User)

```bash
curl -X POST http://localhost:8080/shows/{show_id}/reserve \
  -H "Authorization: Bearer user-123" \
  -H "Content-Type: application/json" \
  -d '{
    "seats": ["A1"],
    "idempotency_key": "unique-key-abc"
  }'
```

**Responses:**
- `201` — Reservation confirmed
- `200` — Idempotent replay (same key, same seats)
- `409` — Seat taken / per-user limit / idempotency conflict
- `404` — Show or seat not found

### 3. Cancel a Reservation

```bash
curl -X POST http://localhost:8080/reservations/{reservation_id}/cancel \
  -H "Authorization: Bearer user-123"
```

### 4. Show State

```bash
curl http://localhost:8080/shows/{show_id}
```

Returns per-seat status and counts. `available + held + confirmed == total_seats` always holds.

## Observability

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health/liveness` | Liveness probe |
| `GET /actuator/health/readiness` | Readiness (checks DB) |
| `GET /actuator/prometheus` | Prometheus metrics |

### Key Metrics

- `reservations_total{reason="confirmed"}` — successful reservations
- `reservations_total{reason="seat_taken"}` — declined: seat unavailable
- `reservations_total{reason="per_user_limit"}` — declined: limit exceeded
- `reservations_total{reason="idempotent_replay"}` — idempotent retries
- `seats_available{show_id="...",show_name="..."}` — available seat gauge

### Structured Logs

Every log line is JSON with `correlationId` and `userId` fields. The `X-Correlation-Id` header is echoed back (or generated if absent).

## Burst Test

```bash
# Against local
python3 burst.py http://localhost:8080

# Against deployed URL
python3 burst.py https://your-service.onrender.com
```

The script runs 5 correctness tests:

1. **Hot-seat storm** — 500 users → 1 seat → exactly 1 winner
2. **Spread storm** — 200 users → random seats → zero 5xx
3. **Per-user limit** — 10 parallel from 1 user (limit=4) → at most 4
4. **Idempotency** — same key × 50 → 1 confirm + 49 replays
5. **Key reuse** — same key, different seats → 409

Prints reconciliation invariant after each test.

## Deployment

### Render

1. Create a PostgreSQL instance on Render
2. Create a Web Service pointing to this repo
3. Set environment variables:
   - `DATABASE_URL` — `jdbc:postgresql://<host>:5432/<db>`
   - `DB_USERNAME` / `DB_PASSWORD`
   - `ADMIN_TOKEN` — your admin secret
4. Build command: `./mvnw package -DskipTests`
5. Start command: `java -jar target/*.jar`

### Railway / Fly.io

Similar — use the `Dockerfile` for container deployment.

## Architecture

See [WRITEUP.md](./WRITEUP.md) for the detailed technical write-up.
