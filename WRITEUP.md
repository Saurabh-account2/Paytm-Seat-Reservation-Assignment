# WRITEUP — Seat Reservation at Scale

## The Atomic Decision

The core correctness problem is: "500 people grab seat A12 at once — exactly one wins."

A naïve read-then-write ("is A12 free? → ok, take it") will double-sell under load because the state can change between the read and the write. The solution is to push the decision into a single atomic step.

### Mechanism: `SELECT … FOR UPDATE` + row-level locking

```sql
SELECT * FROM seats
WHERE show_id = ? AND seat_number IN (?)
ORDER BY seat_number
FOR UPDATE
```

This acquires exclusive row-level locks on the requested seats inside a PostgreSQL transaction. While one transaction holds the lock on seat A12, every other transaction targeting A12 blocks at this point. When the lock holder commits (confirming the seat) or rolls back, the next transaction proceeds, sees the updated state (`status = 'confirmed'`), and returns a clean 409.

**Why `ORDER BY seat_number`:** For multi-seat requests (e.g., ["A12", "A13"]), acquiring locks in a deterministic alphabetical order prevents deadlocks. Without ordering, Transaction 1 locking A13→A12 and Transaction 2 locking A12→A13 would deadlock. With ordering, both acquire A12 first, then A13 — one waits, no cycle.

**Why this over `UPDATE … WHERE status = 'available'`:** A conditional UPDATE returns an affected-row count but not *which* seats failed. With SELECT FOR UPDATE, I can inspect each seat's current state and return a precise error ("seat A12 is already confirmed") rather than a generic "something failed." This also naturally supports the all-or-nothing check before writing.

### All-or-Nothing Semantics

If a user requests `["A12", "A13"]` and only A12 is available, the entire request fails atomically — no partial booking. This is enforced by checking all locked rows before writing any. The transaction rolls back, releasing the locks, and the user gets a 409 listing which seats were unavailable.

## Idempotency

### Where the key is stored

The `idempotency_key` column on the `reservations` table has a `UNIQUE` constraint.

### How exactly-once is enforced

1. **Before locking seats**, the service checks: `SELECT * FROM reservations WHERE idempotency_key = ?`
2. **If found with matching show + seats** → return the existing reservation (HTTP 200 — idempotent replay). No seats are modified.
3. **If found with different seats** → return HTTP 409 (`idempotency_conflict`).
4. **If not found** → proceed with reservation. The `INSERT` into `reservations` is guarded by the `UNIQUE` constraint. If a concurrent request with the same key wins the insert, the loser catches the `DataIntegrityViolationException`, fetches the winner's record, and returns it (replay) or 409 (conflict).

### Same-key, different-body handling

Detected at step 3 above. The first request's seats are the record of truth; any subsequent request with the same key but different seats gets a clean 409.

## Per-User Limit Under Concurrency

The subtle problem: if user U fires 10 parallel requests for *different* seats on a show with limit=4, each request might pass the per-user-limit check before any commit, resulting in 10 confirmed seats.

### Solution: `pg_advisory_xact_lock(hash(showId, userId))`

Before any seat or limit checks, the service acquires a PostgreSQL advisory lock keyed on `hash(showId, userId)`. This serialises all requests from the same user for the same show, while requests from different users remain fully concurrent. The advisory lock is transaction-scoped — it's released automatically on commit/rollback.

This means: user U's 10 parallel requests execute one at a time. The first 4 succeed; the remaining 6 see `currentCount = 4` and get declined with `per_user_limit`.

## Holds & Expiry

The current implementation confirms seats immediately (status = `confirmed`). A cancellation endpoint (`POST /reservations/{id}/cancel`) releases the seats back to `available`.

The architecture supports time-boxed holds via the `held_until` column on the `seats` table and a `HoldExpiryService` that runs every 30 seconds to sweep expired holds:

```sql
UPDATE seats SET status = 'available', user_id = NULL, ...
WHERE status = 'held' AND held_until < now()
```

To activate hold mode, the reserve flow would set `status = 'held'` and `held_until = now() + TTL` instead of `status = 'confirmed'`, and a separate confirm endpoint would transition to `confirmed`.

## Consistency vs Availability Under Partition

This is a CP system. PostgreSQL with row-level locking and advisory locks provides strong consistency — no seat is ever double-sold, even under network jitter or retries. The trade-off is availability: if the database is unreachable, the service returns a `503` (readiness check fails, no requests are accepted). This is the correct choice for a financial transaction (selling seats for money): it's better to decline all sales for 30 seconds than to accidentally sell the same seat twice.

The readiness endpoint (`/actuator/health/readiness`) checks DB connectivity and fails closed — a load balancer should stop routing traffic to an instance that can't reach its database.

## Observability — What I'd Get Paged For at 2am

| Alert | Condition | Why |
|-------|-----------|-----|
| **5xx spike** | `rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 1` | Any 5xx is a bug — declines should be 4xx |
| **Reconciliation drift** | `seats_available + manual query of held+confirmed != total_seats` | Invariant violation = data corruption |
| **Readiness down** | Readiness probe failing > 30s | DB connection lost |
| **Reservation latency** | `p99(reserve_latency) > 2s` | Lock contention or connection pool exhaustion |
| **Connection pool saturation** | `hikaricp_connections_active / hikaricp_connections_max > 0.8` | Need to scale pool or instances |

In a production setup, I'd add:
- Grafana dashboards with the above metrics
- PagerDuty integration for the readiness and 5xx alerts
- Distributed tracing (OpenTelemetry) for multi-service debugging
- Dead-letter logging for any unhandled exceptions

## AI Usage

**AI tools used:** Claude (Anthropic) for code generation, architecture review, and documentation.

### Directed vs Decided

| Aspect | Who decided | How AI helped |
|--------|-------------|---------------|
| Architecture (advisory locks, SELECT FOR UPDATE, all-or-nothing) | Me — based on my understanding of PostgreSQL concurrency | Claude generated the implementation after I specified the approach |
| Idempotency design (UNIQUE constraint + race handling) | Me — standard pattern from distributed systems work | Claude helped with the DataIntegrityViolationException catch block |
| Per-user limit via advisory lock | Me — identified the concurrent-different-seats race | Claude implemented the hash function and lock acquisition |
| Tech stack (Spring Boot 3.3, Java 21, Flyway, Micrometer) | Me — familiar from production work | Claude scaffolded the project structure |
| Burst test design | Me — designed the test scenarios | Claude wrote the asyncio implementation |
| Structured logging, correlation IDs | Joint — standard observability practice | Claude wired the MDC filter |
| Dockerfile multi-stage build | Claude suggested | I reviewed and tuned JVM flags |

## What I'd Do Next

1. **Rate limiting** — Per-IP and per-user rate limits (Spring Cloud Gateway or bucket4j) to prevent abuse before it hits the database.
2. **Distributed locking** — If scaling to multiple application instances behind a load balancer, replace `pg_advisory_xact_lock` with Redis-based distributed locks (Redisson) for cross-instance serialization. (The current approach works because advisory locks are PostgreSQL-global, but Redis would reduce DB contention.)
3. **Event sourcing** — Emit domain events (`SeatReserved`, `SeatReleased`) to Kafka for downstream systems (payment, notification, analytics) without coupling.
4. **Waitlist** — When a seat is taken, offer to join a waitlist. If the holder cancels or the hold expires, notify the next user.
5. **Optimistic locking for reads** — Add a `version` column to seats for optimistic concurrency on non-critical updates (metadata changes).
6. **Load testing at scale** — k6 or Gatling with 20,000+ concurrent users, measure p99 latency, and tune connection pool + thread pool sizing.
7. **Circuit breaker** — Resilience4j circuit breaker on the DB connection to fail fast during outages rather than queuing requests.
8. **Seat map UI** — WebSocket-based real-time seat availability map for a frontend consumer.
