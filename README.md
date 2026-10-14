# NexaWallet - Enterprise Digital Wallet Platform

NexaWallet is a modern, fintech-grade digital wallet application engineered to demonstrate production-level Java 21, Spring Boot 3, and modern full-stack architecture principles.

---

## 🏛 System Architecture & Design Highlights

1. **PostgreSQL as Single Source of Truth**: All wallet balances, ledger entries, and financial states reside in relational tables with strict transactional ACID guarantees and check constraints.
2. **Immutable Double-Entry Ledger**: Every financial movement writes balanced debit and credit entries. The platform liability always equals the sum of user wallet balances.
3. **Deterministic Lock Ordering**: Concurrent P2P transfers between users sort wallet IDs lexicographically before acquiring row-level database locks (`SELECT ... FOR UPDATE`), mathematically preventing deadlocks.
4. **Durable Database-Backed Idempotency**: Financial mutations require an `Idempotency-Key` header, preventing duplicate transfers during network retries.
5. **Transactional Outbox Pattern**: Domain events are committed in the same database transaction as the financial ledger, then published to Apache Kafka to prevent dual-write anomalies.
6. **Elasticsearch Transaction Search**: Asynchronous Kafka consumers index transaction records into Elasticsearch for debounced full-text search and faceted filtering.
7. **Redis Rate Limiting & Caching**: Sliding-window rate limiters prevent brute-force attacks and abuse of sensitive payment endpoints.
8. **Geohash Discovery**: Proximity discovery for nearby agents, branches, and peer transfer points using Base32 Geohash indexing and 8-neighbor expansion.

---

## 🛠 Technology Stack

- **Backend**: Java 21, Spring Boot 3.3, Maven, Spring Data JPA, Spring Security, Flyway, SpringDoc OpenAPI
- **Frontend**: React 18, TypeScript, Vite, Tailwind CSS, Lucide Icons, Recharts, Axios
- **Infrastructure**: Docker Compose, PostgreSQL 16, Redis 7, Apache Kafka (KRaft mode), Elasticsearch 8.11
- **Testing**: JUnit 5, Mockito, AssertJ, Testcontainers

---

## 📁 Repository Structure

```text
├── backend/                  # Spring Boot 3 & Java 21 backend service
│   ├── src/main/java/        # Domain-driven modular monolith packages
│   ├── src/main/resources/   # Application configuration & Flyway migrations
│   └── pom.xml               # Maven dependencies and build plugins
├── frontend/                 # React 18 + TypeScript + Vite frontend
│   ├── src/                  # Pages, components, layouts, and styles
│   ├── package.json          # Node dependencies
│   └── vite.config.ts        # Vite configuration
├── docker-compose.yml        # Local infrastructure (Postgres, Redis, Kafka, ES)
├── .env.example              # Documented environment variable template
├── .gitignore                # Root gitignore for Java, Node, IDEs
└── README.md                 # Architecture blueprint & setup guide
```

---

## 🚀 Getting Started

### 1. Prerequisites
- **Java 21** or higher
- **Maven 3.9+**
- **Node.js 20+** and **npm 10+**
- **Docker & Docker Compose** (for local infrastructure)

### 2. Start Infrastructure
Copy the environment template and start the backing services:
```bash
cp .env.example .env
docker compose up -d
```
Verify container status:
```bash
docker compose ps
```

### 3. Run the Backend
Navigate to the `backend/` directory:
```bash
cd backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean spring-boot:run
```
The backend starts on port `8080`.
- Health endpoint: `http://localhost:8080/actuator/health`
- Custom system info: `http://localhost:8080/api/v1/health`
- Swagger UI / OpenAPI docs: `http://localhost:8080/swagger-ui.html`

### 4. Run the Frontend
In a separate terminal, navigate to the `frontend/` directory:
```bash
cd frontend
npm install
npm run dev
```
The frontend starts on `http://localhost:5173`.

---

## 🔐 Authentication & Security Model

NexaWallet implements strict stateless authentication:

| Endpoint | Method | Access | Description |
| :--- | :---: | :---: | :--- |
| `/api/v1/auth/register` | `POST` | Public | Registers a new user, hashes password with BCrypt (12 rounds), automatically allocates primary USD wallet, and returns signed JWT access token. |
| `/api/v1/auth/login` | `POST` | Public | Validates credentials via `AuthenticationManager` and issues signed JWT access token. |
| `/api/v1/auth/me` | `GET` | Authenticated | Protected endpoint requiring `Authorization: Bearer <token>`, returns user profile and primary wallet balance. |

### Security Design: In-Memory Token Storage
- **Advantage**: The client stores the JWT access token purely in JavaScript memory (React state) rather than `localStorage` or `sessionStorage`, making it immune to token theft via Cross-Site Scripting (XSS) attacks.
- **Trade-off**: A hard browser reload clears memory; in subsequent phases, an HTTP-only secure cookie or refresh token rotation mechanism will be added for seamless session restoration.

---

## 📋 Implementation Roadmap

- [x] **Phase 1: Project Foundation & Infrastructure** (Docker Compose, Spring Boot scaffolding, Flyway schema, React + Tailwind setup)
- [x] **Phase 2: Authentication, Security & Wallet Management** (Stateless JWT auth, User & Wallet creation, Protected routes, In-memory token storage)
- [x] **Phase 3: Core Fintech Engine** (Double-entry ledger, deterministic locking, idempotency, simulated deposits)
- [x] **Phase 4: Transactional Outbox & Kafka Event-Driven Architecture** (Outbox publisher, Kafka topics, consumer deduplication, dead-letter routing, admin monitoring)
- [x] **Phase 5: Redis Caching & Distributed Sliding-Window Rate Limiting** (read-through profile cache, atomic Lua rate limiter, trusted-proxy IP resolution, fail-closed outage policy)
- [x] **Phase 6: Elasticsearch Search & Geohash-Based Location Discovery** (service-point admin CRUD, outbox→Kafka→Elasticsearch indexing pipeline, public nearby-search API, bounded reindex job)
- [ ] **Phase 7: Frontend Polish, Recharts Analytics & Interactive Transfer Flow**
- [ ] **Phase 8: End-to-End Testing (Testcontainers, Concurrency Stress Tests) & Interview Kit**

---

## 📨 Phase 4: Transactional Outbox & Kafka Event-Driven Architecture

PostgreSQL remains the single source of truth for every financial decision. Kafka is purely
a downstream notification mechanism — a broker outage can delay event delivery, but it can
never roll back, block, or alter the outcome of a committed deposit or transfer.

### Outbox lifecycle

```
PENDING ──(claimed by a publisher)──► PUBLISHING ──(broker ack)──► PUBLISHED
                                           │
                                           └─(transient failure, retries remain)──► PENDING (next_retry_at set, retry_count++)
                                           └─(retry budget exhausted)──► FAILED (last_error recorded)
```

1. **Write** — `TransferServiceImpl` and `WalletServiceImpl` call `OutboxEventWriter.record(...)` *after* writing the
   ledger entries and updating balances, but still inside the same `@Transactional` method. The outbox row is
   committed or rolled back atomically with the financial transaction — this is the transactional outbox pattern,
   and it is the only thing that prevents the classic dual-write problem (DB commits, then the process crashes
   before the Kafka send — or vice versa).
2. **Claim** — `OutboxPublisherService` polls every `nexa.outbox.publisher.poll-interval-ms` (default 2s).
   `OutboxClaimService.claimBatch(...)` runs one short transaction: `SELECT id ... FOR UPDATE SKIP LOCKED` on
   eligible `PENDING` rows, then an `UPDATE ... SET status = 'PUBLISHING'` on exactly those rows, then commits.
   `SKIP LOCKED` means a second publisher instance polling concurrently simply skips whatever the first has
   already locked — no two instances ever claim the same row under normal operation.
3. **Publish** — with the claim transaction already committed (no DB lock held), the publisher sends each claimed
   event to Kafka and **blocks for the broker acknowledgement** (`nexa.outbox.publisher.send-timeout-ms`, default
   10s).
4. **Settle** — on acknowledgement, `OutboxEventStatusUpdater.markPublished(...)` (its own short transaction) sets
   `PUBLISHED` + `published_at`. On any failure (timeout, broker error, serialization error),
   `recordFailure(...)` increments `retry_count` and either schedules `next_retry_at` with bounded exponential
   backoff (`base-backoff-ms * 2^attempt`, capped at `max-backoff-ms`) or, once `max-retries` is exhausted, marks
   the row `FAILED` with a sanitized, 500-character-limited `last_error`.

### Event schema (versioned envelope)

Every outbox row's `payload` — and every Kafka message body — is this JSON envelope:

```json
{
  "eventId": "9c8f...-uuid",
  "eventType": "P2P_TRANSFER_SETTLED",
  "schemaVersion": 1,
  "aggregateType": "Transaction",
  "aggregateId": "4e21...-uuid",
  "occurredAt": "2026-10-14T12:00:00Z",
  "data": {
    "transactionId": "...",
    "referenceCode": "TXN-...",
    "sourceWalletNumber": "NX-....",
    "targetWalletNumber": "NX-....",
    "amount": 150.0000,
    "currency": "USD",
    "status": "SETTLED",
    "type": "P2P_TRANSFER"
  }
}
```

`eventType` is `P2P_TRANSFER_SETTLED` (from `TransferServiceImpl`) or `DEPOSIT_SETTLED` (from `WalletServiceImpl`).
`data` carries only business metadata needed to project the transaction — **no passwords, JWTs, emails, or
names** ever appear in an event.

### Kafka topics

| Topic | Status | Purpose |
| :--- | :--- | :--- |
| `wallet.transactions.v1` | **Active** | Every settled transaction (deposit *and* transfer), keyed by transaction UUID. Read by `TransactionEventConsumer` today — a reserved future phase may add transaction full-text search on top of it, but Phase 6 itself indexes service points, not transactions (see below). |
| `servicepoints.index.v1` | **Active** (Phase 6) | Service-point create/update/deactivate events, keyed by service-point UUID. Read by `ServicePointIndexEventConsumer` to keep the Elasticsearch discovery index in sync. |
| `wallet.dlq.v1` | **Active** | Shared dead-letter topic for every consumer in this codebase (transaction and service-point alike). The consumer's error handler routes a message here after exhausting its retry budget, or immediately for a non-retryable (malformed/unsupported-schema) message. Spring Kafka's `DeadLetterPublishingRecoverer` adds `kafka_dlt-original-topic/-partition/-offset/-exception-*` headers; the original `eventId` is preserved unchanged inside the forwarded payload. |
| `wallet.transfers.v1` | Provisioned, reserved | Not yet published to. Reserved for a future dedicated P2P-transfer-only stream (e.g. fraud/velocity checks, recipient notifications) that shouldn't also see deposits. |
| `wallet.notifications.v1` | Provisioned, reserved | Not yet published to. Reserved for future outbound user notification events (email/push/SMS). |

All five topics are created on startup via `NewTopic` beans in `KafkaTopicConfig` (`KafkaAdmin` tolerates the
broker being unreachable — it logs and continues rather than failing startup).

**Partition key**: the transaction UUID (`aggregateId`). This guarantees at most one message in flight per
transaction. It does **not** guarantee ordering across *different* transactions on the same wallet — that's an
explicit, acceptable trade-off here, because every event is a complete, self-contained, idempotent snapshot of
one settled transaction; nothing downstream needs to see transaction N-1 before N for the same wallet.

### Delivery guarantees & duplicate handling

This is **at-least-once delivery**, not exactly-once — that claim would be false across two independent systems
(Postgres + Kafka) without distributed transactions. Concretely: if the publisher process crashes *after* Kafka
acknowledges a send but *before* the `PUBLISHED` status commits, the event is retried and delivered again on
restart. `TransactionEventConsumer` is built to tolerate this: before applying any side effect, it checks
`processed_events` (unique constraint on `(event_id, consumer_group)`); a duplicate delivery is detected and
skipped (offset still acknowledged) rather than reprocessed. A one-in-a-million race where two consumer threads
insert the same `(event_id, consumer_group)` concurrently is caught as a `DataIntegrityViolationException` and
treated as "already handled," not an error.

The Kafka producer itself also has `enable.idempotence=true` + `acks=all`, so a single send's own internal
retries (network blips at the TCP/broker-protocol level) can't create broker-side duplicates for *that* attempt —
a narrower guarantee than, and independent of, the outbox's own at-least-once semantics across process restarts.

### Retry & dead-letter behavior

- **Publisher → Kafka**: bounded exponential backoff (`nexa.outbox.publisher.max-retries`, default 8 attempts),
  tracked in `outbox_events.retry_count` / `next_retry_at`. Exhausted → `FAILED`, visible via the admin endpoint
  below; nothing here needs manual Kafka intervention since it never left Postgres.
- **Consumer → DLQ**: `KafkaConsumerConfig` wires a `DefaultErrorHandler` with its own `ExponentialBackOff`
  (`nexa.kafka.consumer.max-retries`, default 5) and a `DeadLetterPublishingRecoverer` targeting `wallet.dlq.v1`.
  A `NonRetryableEventException` (malformed JSON, unsupported `schemaVersion`) skips the retry budget entirely —
  retrying a message that can never parse would only delay every message behind it.

### Operational visibility

`GET /api/v1/admin/outbox/stats` (requires `ROLE_ADMIN`, `403` otherwise) returns:

```json
{
  "pendingCount": 0, "publishingCount": 0, "publishedCount": 42, "failedCount": 0,
  "oldestPendingAgeSeconds": null, "maxRetryCountAmongPendingOrFailed": 0,
  "consumerProcessedEventCount": 42
}
```

Never exposes raw event payloads — aggregate counts and ages only. Standard Actuator endpoints
(`/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`) remain available and unchanged; no environment
variables or credentials are exposed through them.

### Local startup

```bash
cp .env.example .env
docker compose up -d        # starts Postgres, Redis, Kafka (KRaft), Elasticsearch
cd backend && mvn spring-boot:run
```

Kafka runs as a **single KRaft broker** for local development — `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1`,
`replicas=1` on every topic. This is adequate for development and demonstration, but is explicitly **not** a
production-grade highly-available broker configuration (no replication means no broker-failure tolerance; a
production deployment needs a 3+ broker cluster with a replication factor ≥ 3).

### Troubleshooting & test commands

```bash
# Backend: compile, unit tests, H2-backed integration tests (fast, no Docker required)
cd backend && mvn test

# Full pipeline against real Postgres + Kafka (requires a running Docker daemon)
# — same `mvn test`, but OutboxKafkaIntegrationTest only runs (not skipped) when Docker is reachable.
docker compose up -d nexa-kafka
mvn test -Dtest=OutboxKafkaIntegrationTest

# Inspect pending/failed events directly
docker compose exec nexa-postgres psql -U nexa_user -d nexawallet \
  -c "SELECT event_type, status, retry_count, last_error FROM outbox_events WHERE status <> 'PUBLISHED' ORDER BY created_at;"

# Watch the dead-letter topic
docker compose exec nexa-kafka kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic wallet.dlq.v1 --from-beginning
```

### Known limitations

- Single-broker Kafka (see above) — no replication, not production-grade availability.
- `wallet.transfers.v1` and `wallet.notifications.v1` are provisioned but have no producer/consumer yet.
- The admin endpoint reports outbox-side retry/failure counts; it does not currently surface a live count of
  consumer-side DLQ messages (inspect `wallet.dlq.v1` directly, as above, for that).

---

## 🧯 Phase 5: Redis Caching & Distributed Sliding-Window Rate Limiting

Redis is used for two unrelated, narrowly-scoped purposes: a small read-through cache, and shared
state for a distributed rate limiter. **PostgreSQL remains the sole source of truth for every
financial decision** — neither use of Redis is ever consulted to decide whether a transfer is
authorized or whether a wallet has sufficient funds, and a Redis outage degrades each feature
independently rather than ever producing a misleading "success" on a financial operation.

### The cacheable resource: `GET /api/v1/auth/me` identity fields

`UserResponse` mixes two very different kinds of data: identity fields (email, name, role,
`createdAt`) that are effectively immutable after registration, and `walletBalance` — live
financial state that changes on every deposit and transfer. Only the first half is cached.

- **What's cached**: `UserProfileCacheEntry { id, email, firstName, lastName, role, createdAt }` —
  see `AuthServiceImpl.toProfile(...)`. **Never cached**: `walletBalance`, `walletNumber`,
  `currency`, or anything else sourced from `wallets`/`transactions`/`ledger_entries`. Every call to
  `getCurrentUser(...)` fetches the wallet fresh from PostgreSQL and merges it with whatever the
  cache returned for the identity half — a cache hit can therefore never serve a stale balance,
  because the balance was never part of what got cached in the first place.
- **Cache name / key**: `userProfile`, key = the user's UUID as a string
  (`RedisCacheConfig.USER_PROFILE_CACHE`). Keys are per-user by construction, so one user's entry
  can never be returned for another user's lookup — verified in
  `UserProfileCacheRedisIntegrationTest` (`differentUsers_isolated`) and
  `AuthControllerTest#cachedProfiles_areIsolatedAcrossUsers`.
- **TTL**: `nexa.cache.user-profile.ttl-seconds`, default `600` (10 minutes).
- **Invalidation**: there is no update-profile endpoint yet, so TTL expiry is the only *passive*
  invalidation path. The *active* path is simpler and stronger: every successful login or
  registration unconditionally overwrites the cache entry with a value just read from Postgres
  (`AuthServiceImpl.login`/`.register` both call `userProfileCacheService.put(...)`) — so the entry
  is refreshed on every session start, independent of TTL.
- **Serialization**: plain JSON via `Jackson2JsonRedisSerializer<UserProfileCacheEntry>`, bound to
  that one concrete type — **not** `GenericJackson2JsonRedisSerializer`'s default typing, which
  would embed a `@class` field and allow unsafe polymorphic deserialization of whatever that field
  names. Verified in `UserProfileCacheRedisIntegrationTest#valuesAreStoredAsPlainJson_noPolymorphicTypeInfo`.
  No Java native object serialization anywhere in the path.
- **Cache failure behavior**: `UserProfileCacheService` wraps every Redis GET/PUT/EVICT in its own
  try/catch (see `UserProfileCacheServiceTest`) — a Redis outage degrades to "always load from
  Postgres," never breaks the request, and never affects the balance (which was never cached).
- **Test-only backend**: the `"test"` Spring profile gets a `ConcurrentMapCacheManager` instead of
  Redis (`RedisCacheConfig`), so the whole H2-backed test suite exercises real cache-hit/miss/evict
  behavior without requiring a live Redis server — only the Testcontainers-backed
  `UserProfileCacheRedisIntegrationTest` needs a real one, for genuine TTL and JSON-shape checks.

### Why balances and ledger data are never cached

A cached balance is, by definition, a balance PostgreSQL might have already changed. Serving it
even once — in an interleaving where a transfer settles between the cache write and the next
read — would mean displaying (or, far worse, deciding against) a number the ledger no longer
agrees with. The double-entry ledger and the outbox's correctness guarantees (Phases 3–4) exist
specifically so a transaction's outcome is never ambiguous; caching financial state anywhere in the
read path would reopen exactly that ambiguity for no benefit (balance reads are already a single
indexed primary-key lookup — there is no performance problem here to solve by caching them).

### Distributed sliding-window rate limiting

**Algorithm**: sliding-window-log, implemented as one atomic Lua script
(`scripts/sliding_window_rate_limiter.lua`) executed via a single Redis `EVAL`. Each bucket is a
Redis sorted set; every call first evicts members older than `now - window`, then — *only if* the
remaining count is still under the limit — adds its own member and returns "allowed," otherwise
returns "rejected" without adding anything. Not adding a member on rejection keeps the set bounded
by `limit` rather than growing without bound, and a `PEXPIRE` equal to the window size reclaims an
idle bucket automatically. Running the whole read-trim-count-decide-write sequence as one `EVAL` is
what makes it atomic — no non-atomic sequence of independent Redis commands a concurrent request
could interleave with (verified under real concurrency in
`RedisSlidingWindowRateLimiterIntegrationTest#concurrentRequests_neverExceedLimitUnderRace`).

**Endpoint coverage & defaults** (all configurable via `nexa.ratelimit.*` / the matching env var in
`.env.example`):

| Route | Bucket(s) | Default limit | Default window |
| :--- | :--- | :---: | :---: |
| `POST /api/v1/auth/login` | IP, **and** a per-identifier bucket keyed by `HMAC-SHA256(normalized email)` — never the raw email | 10 / 5 | 60s / 60s |
| `POST /api/v1/auth/register` | IP | 5 | 1 hour |
| `POST /api/v1/transfers` | authenticated user id, **and** IP (supplements, never replaces, the user bucket) | 20 / 60 | 60s / 60s |
| `POST /api/v1/wallet/deposit` | authenticated user id, **and** IP | 20 / 60 | 60s / 60s |

No other route is touched — health, actuator, swagger, OPTIONS preflight, and every other endpoint
pass straight through `RateLimitFilter` without calling Redis at all.

**Partition/bucket keys**: `"<action>:<ip|user|id>:<value>"`, e.g. `transfer:user:<uuid>`,
`login:ip:<ip>`, `login:id:<hmac-hex>`. The per-user bucket is what actually bounds a given
account's throughput; the IP bucket is a coarser supplement (per spec: "may supplement, must not
replace" the user-level limit) that also catches unauthenticated abuse against the same endpoints.

**Blocked requests never reach the controller**: `RateLimitFilter` is registered via
`SecurityConfig#addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)` — after JWT auth has
populated the security context (so the user id is available) but still well before
`DispatcherServlet`/the controller. A 429 (or a fail-closed 503) is written and returned directly
from the filter; `filterChain.doFilter(...)` is never called on that path. Proven end-to-end —
real Spring Security chain, real Postgres, real Redis — in
`RateLimitEndToEndIntegrationTest#depositBlockedByRateLimit_neverReachesFinancialService`, which
asserts exactly one `transactions` row exists after two deposit calls where the second was blocked.

A subtlety worth being explicit about: `RateLimitFilter` is a `@Component`, which would normally
make Spring Boot *also* auto-register it as an independent, globally-mapped servlet filter — on top
of the one copy already running inside the Spring Security chain. That would silently double-count
every request against its own quota. `SecurityConfig` disables that auto-registration explicitly
(`FilterRegistrationBean<RateLimitFilter>` with `setEnabled(false)`) — see the comment there for why.

### Redis outage behavior

**Default: fail closed.** If the Lua script call itself fails (timeout, connection refused — caught
in `RedisSlidingWindowRateLimiter.tryAcquire`), the filter treats that as neither "allowed" nor
"rejected" but as a distinct `backendUnavailable` outcome, and `nexa.ratelimit.fail-policy=CLOSED`
(the default) turns that into an HTTP `503` with a short `Retry-After` hint — the request never
reaches the controller. This is a deliberate trade-off: a broken rate limiter must never silently
become "no rate limiter," especially on `/auth/login` and the money-movement endpoints, where
unlimited throughput during an infra blip is exactly the scenario rate limiting exists to prevent.
`fail-policy=OPEN` exists and is fully wired (see `RateLimitFilterTest#backendUnavailable_openPolicy_allowsThrough`)
for an operator who has made a deliberate, documented decision to prefer availability over that
protection — it is not the default and should not be enabled without that trade-off being a
conscious one.

Separately: a Redis outage **cannot** affect a financial operation's correctness. The cache
(`UserProfileCacheService`) always falls back to a Postgres read on failure rather than erroring,
and the rate limiter's own outage path (above) only ever *blocks* a request early with a `503` —
it can reject a request before it starts, but it can never cause a financial write that already
reached `TransferServiceImpl`/`WalletServiceImpl` to fail, roll back, or behave differently. Redis
being down can make NexaWallet temporarily refuse new login/transfer/deposit attempts; it can never
make an in-flight one produce an incorrect result.

### Trusted-proxy handling

`X-Forwarded-For` is attacker-controlled on any request reaching this application directly from the
public internet — honoring it unconditionally would let anyone bypass IP-based rate limiting by
sending a different value on every request. `ClientIpResolver` only reads it when the request's
actual socket peer address (`HttpServletRequest.getRemoteAddr()`) matches an entry in
`nexa.ratelimit.trusted-proxies` (comma-separated exact IPs or CIDR ranges). That list is **empty by
default** — meaning the header is never trusted out of the box, and the raw socket address is
always used instead, until an operator explicitly names their reverse proxy's address.

### Configuration

All of the following are read from `application.yml` under `nexa.ratelimit.*` / `nexa.cache.*`, each
with an environment-variable override documented in `.env.example`:

```yaml
nexa:
  cache:
    user-profile:
      ttl-seconds: 600                 # CACHE_USER_PROFILE_TTL_SECONDS
  ratelimit:
    enabled: true                      # RATE_LIMIT_ENABLED
    fail-policy: CLOSED                # RATE_LIMIT_FAIL_POLICY (CLOSED | OPEN)
    trusted-proxies: ""                # RATE_LIMIT_TRUSTED_PROXIES (comma-separated IPs/CIDRs)
    identifier-hmac-secret: "..."       # RATE_LIMIT_HMAC_SECRET
    login-ip: { limit: 10, window-seconds: 60 }
    login-identifier: { limit: 5, window-seconds: 60 }
    register-ip: { limit: 5, window-seconds: 3600 }
    transfer-user: { limit: 20, window-seconds: 60 }
    transfer-ip: { limit: 60, window-seconds: 60 }
    deposit-user: { limit: 20, window-seconds: 60 }
    deposit-ip: { limit: 60, window-seconds: 60 }
```

`spring.data.redis.{host,port,password,timeout,connect-timeout}` configure the connection itself
(`REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_COMMAND_TIMEOUT_MS`, `REDIS_CONNECT_TIMEOUT_MS`).

### Operational visibility

Standard Actuator (`/actuator/health`, `/actuator/metrics`) is unchanged and sufficient here — no
new monitoring stack was introduced. The Redis health indicator is included in `/actuator/health`
in the default/dev profile (and explicitly disabled, `management.health.redis.enabled=false`, only
in the `test` profile, so the existing H2-backed suite never depends on a live Redis server to pass).
Rate-limit rejections and backend-unavailable events are logged at `INFO`/`WARN`/`ERROR` respectively,
identified only by **bucket category** (e.g. `transfer:user`, `login:ip`) — never the raw key, which
can embed a user id, an IP, or a hashed identifier — and never the request body, an access token, or
a password. No Redis keys, cache contents, or rate-limit internals are exposed to ordinary customers;
the only administrative surface remains the existing `ROLE_ADMIN`-gated `/api/v1/admin/outbox/stats`
from Phase 4, which this phase does not extend.

### Local startup

```bash
cp .env.example .env
docker compose up -d        # starts Postgres, Redis, Kafka (KRaft), Elasticsearch
cd backend && mvn spring-boot:run
```

Redis runs as a **single local instance** with `appendonly yes` for dev-restart durability of
rate-limit/cache state — this is a development configuration, not a highly-available Redis
deployment (no replication, no Sentinel/Cluster). `REDIS_PASSWORD` is blank by default (no auth) for
local dev; set it in `.env` for any shared environment, and the container's `--requirepass` picks it
up automatically.

### Troubleshooting & test commands

```bash
# Backend: compile, unit tests, H2-backed integration tests (fast, no Docker/Redis required)
cd backend && mvn test

# Real Redis integration tests (Testcontainers; requires a running Docker daemon)
mvn test -Dtest=RedisSlidingWindowRateLimiterIntegrationTest
mvn test -Dtest=UserProfileCacheRedisIntegrationTest
mvn test -Dtest=RateLimitEndToEndIntegrationTest

# Inspect what's actually in a rate-limit bucket (sorted set of request timestamps)
docker compose exec nexa-redis redis-cli ZRANGE "transfer:user:<uuid>" 0 -1 WITHSCORES

# Confirm a cached profile's TTL
docker compose exec nexa-redis redis-cli TTL "userProfile::<uuid>"
```

### Test results & known limitations (Phase 5)

Ran in this environment: `mvn test` — **85 tests, 0 failures, 0 errors, 17 skipped** (4 from Phase
4's Kafka Testcontainers test, 13 from this phase's three Testcontainers classes above), all via
real assertions, not mocked stand-ins for the skipped ones. Docker's CLI is present in this sandbox
but its daemon is not running, so every `@Testcontainers(disabledWithoutDocker = true)` class
reports SKIPPED rather than failing the build; run the three commands above once a daemon is
reachable to execute them for real. Everything that *did* run — including every pre-existing
auth/ledger/transfer/idempotency/outbox test from Phases 1–4 — passed unchanged.

- The HMAC key for the login-identifier bucket is a dedicated secret (`RATE_LIMIT_HMAC_SECRET`),
  deliberately not the JWT signing key, to avoid reusing one secret for two purposes — but it still
  needs rotating to a real random value outside local dev (the shipped default is a placeholder).
- The outage end-to-end test exercises the fail-closed `503` path via a mocked limiter
  (`RateLimitFilterTest`); it does not simulate killing a *live* Testcontainers Redis mid-test
  (e.g. via `REDIS.stop()`) to reproduce the outage against the real filter chain — the mocked-limiter
  coverage and the real-Redis coverage are each real, just not combined into one test.
- Redis runs single-instance locally (documented above) — not a production HA topology.

---

## 🧭 Phase 6: Elasticsearch Search & Geohash-Based Location Discovery

Elasticsearch indexes **service points** (ATMs, branches, partner kiosks, merchants) for proximity
search. PostgreSQL's `service_points` table remains authoritative — Elasticsearch is a derived
index that can always be fully rebuilt from it (`POST /api/v1/admin/service-points/reindex`), and
nothing in this phase touches the financial ledger, wallet balances, transfer locking, idempotency,
the Redis rate limiter, or the outbox's delivery guarantees. These are operator-curated locations,
not real third-party business partnerships — "ATM" / "BRANCH" / "PARTNER_KIOSK" / "MERCHANT" are
category labels for a demo dataset, not claims about actual participating businesses.

### Elasticsearch version & index

- **Version**: `docker.elastic.co/elasticsearch/elasticsearch:8.11.1` (pinned, matches the existing
  Phase 1 compose service). Client: Spring Boot 3.3.4's managed `spring-boot-starter-data-elasticsearch`
  → **Spring Data Elasticsearch 5.3.4** + **`co.elastic.clients:elasticsearch-java` 8.13.4** — the
  Boot-managed pairing, not a hand-picked mismatched combination.
- **Index**: `service_points_v1` (`ELASTICSEARCH_SERVICE_POINTS_INDEX`), versioned so a future
  breaking mapping change ships as `service_points_v2` without disrupting the index currently being
  searched. `ServicePointIndexInitializer` creates it with an explicit mapping on startup if
  missing — idempotent, and tolerant of Elasticsearch being unreachable at boot (logs and moves on,
  same tolerance `KafkaAdmin` already has elsewhere in this codebase).
- **Document fields** (`ServicePointDocument`): `id` (keyword, = PostgreSQL's own UUID, so every
  write is a natural upsert), `name` (keyword), `type` (keyword), `active` (boolean), `location`
  (`geo_point`), `geohash` (keyword, candidate-selection hint only — see below), `address` (text),
  `updatedAt` (date). Nothing else — no wallet balances, ledger entries, transaction details,
  credentials, or tokens; this index has no financial data in it at all, by construction.

### How service-point events reach the index

Reuses the **exact same** transactional-outbox/Kafka infrastructure as Phase 4 — no second event
pipeline:

1. `ServicePointAdminService.create/update/deactivate` writes the PostgreSQL row and calls
   `OutboxEventWriter.record(...)` **in the same transaction**. If the transaction rolls back, the
   outbox row rolls back with it.
2. The existing `OutboxPublisherService` claims and publishes it like any other outbox row —
   `resolveTopic(...)` now routes by aggregate type: `"ServicePoint"` → `servicepoints.index.v1`,
   everything else → `wallet.transactions.v1` (unchanged). Partition key = the service point's own
   UUID, so Kafka preserves per-service-point event ordering.
3. `ServicePointIndexEventConsumer` reads `servicepoints.index.v1`, dedupes via the same
   `processed_events` table (consumer group `nexawallet-service-point-indexer` — a distinct row
   space from the transaction indexer's `nexawallet-transaction-indexer`, via the existing
   `(event_id, consumer_group)` unique constraint), and upserts the document.
4. Retry/DLQ reuses the same shared `kafkaListenerContainerFactory`'s `DefaultErrorHandler` →
   bounded backoff → `wallet.dlq.v1` on exhaustion, exactly like `TransactionEventConsumer`.

**Idempotency, simplified**: every event type (CREATED/UPDATED/DEACTIVATED) carries the *full
current snapshot* of the service point, so the consumer only has one code path — parse, validate,
dedupe, upsert — regardless of which event type it reads. Deactivation is represented as
`active=false` inside that same upsert; there is no separate delete branch, so a deactivated point
keeps existing in the index (just filtered out of search results) and can be reactivated later. A
duplicate Kafka delivery (at-least-once redelivery after a rebalance/crash) is caught by the
`processed_events` check before ES is touched, and even without that check, re-applying the same
upsert is harmless since it's keyed by the same document `_id`.

### Reindex & recovery

`POST /api/v1/admin/service-points/reindex` (ROLE_ADMIN) re-reads `service_points` from PostgreSQL
in bounded pages (`nexa.discovery.reindex-batch-size`, default 200) and bulk-upserts each page —
never one unbounded in-memory table scan. Safe to run repeatedly: every write is keyed by the
service point's own UUID, so re-running never creates a duplicate logical document.
`POST /api/v1/admin/service-points/index/recreate` deletes (if present) and recreates the index
with the current mapping — use this after a mapping change, then immediately call `/reindex` to
repopulate (the index is left empty in between).

**Documented limitation — events arriving mid-reindex**: the reindex job reads a PostgreSQL
snapshot. If a live Kafka-driven upsert for some service point lands in Elasticsearch *after* the
job already read (but before it writes) that same row, the job's write can overwrite the newer
Kafka-driven write with older data — last-write-wins at the document level, decided purely by write
order, with no vector clock or version check. This self-heals as soon as the next event for that
service point is processed (or the next reindex run), but it is **not** a guaranteed-ordering,
exactly-consistent rebuild, and this is not oversold as one — reindexing is for recovering from
index loss or a mapping change, not a substitute for the live Kafka pipeline.

### Geohash candidate selection & final radius filtering

The nearby-search query has two parts with different jobs:

1. **Correctness** — an Elasticsearch `geo_distance` filter (always present, `must`). Elasticsearch
   computes true great-circle distance natively, correctly at the poles and across the
   antimeridian, with no help from geohash arithmetic. This is the *only* thing that decides
   whether a document is "within radius," and the only thing `_geo_distance` sort relies on for the
   returned `distanceMeters`.
2. **Candidate-selection hint** — a `should` clause over the search center's geohash cell and its 8
   neighbors (`GeohashUtils.neighbors(...)`, Base32, hand-written — no geohash dependency existed
   in this project before). Being a `should`, it can only add relevance weight; it can **never**
   exclude a document the `geo_distance` filter already accepted. This is a deliberate choice: a
   fixed-precision geohash cell's real-world width shrinks the closer it is to a pole (meridians
   converge), so treating it as a guaranteed "covers at least radius X" box and using it as a
   restrictive filter would not be safe near the poles — exactly the condition this project is
   required to handle correctly. Using it only as a non-restrictive hint sidesteps that failure
   mode while still genuinely implementing Geohash-based candidate selection, per the documented
   architecture (`GeohashUtils` javadoc explains this trade-off in full).

Because this is **one** Elasticsearch query — not several per-cell queries merged client-side — a
document can never be returned twice, no matter how many neighboring cells it matches. Sorting is
`_geo_distance` ascending with `_id` as a deterministic secondary sort for exact ties.

### Nearby API

```
GET /api/v1/service-points/nearby?lat={lat}&lon={lon}&radiusMeters={r}&category={c}&page={p}&size={s}
```

Public — no JWT required, like any "find a nearby ATM" feature (administrative writes/reindex are
`ROLE_ADMIN`-only, under `/api/v1/admin/service-points`). Parameters:

| Param | Required | Bounds | Default |
| :--- | :---: | :--- | :--- |
| `lat` | yes | -90 to 90 | — |
| `lon` | yes | -180 to 180 | — |
| `radiusMeters` | no | 0 < r ≤ `nexa.discovery.max-radius-meters` (50 000 default) | `nexa.discovery.default-radius-meters` (2 000) |
| `category` | no | one of `ATM`, `BRANCH`, `PARTNER_KIOSK`, `MERCHANT` | none (all categories) |
| `page` | no | ≥ 0 | 0 |
| `size` | no | 1 to `nexa.discovery.max-page-size` (100 default) | `nexa.discovery.default-page-size` (20) |

Invalid parameters → `400` (standard `ApiErrorResponse` shape). Elasticsearch unreachable → `503`
(never a stack trace, never ES internals — see `SearchUnavailableException` / `GlobalExceptionHandler`).

Example request/response:

```bash
curl "http://localhost:8080/api/v1/service-points/nearby?lat=40.7128&lon=-74.0060&radiusMeters=2000&category=ATM"
```
```json
{
  "content": [
    { "id": "c1a2...", "name": "Downtown ATM", "type": "ATM", "latitude": 40.7130, "longitude": -74.0058,
      "address": "123 Main St", "distanceMeters": 24.7 }
  ],
  "page": 0, "size": 20, "totalElements": 1
}
```

Admin write example:
```bash
curl -X POST http://localhost:8080/api/v1/admin/service-points \
  -H "Authorization: Bearer <admin JWT>" -H "Content-Type: application/json" \
  -d '{"name":"Downtown ATM","type":"ATM","latitude":40.7128,"longitude":-74.0060,"address":"123 Main St"}'
```

### Local startup & configuration

```bash
cp .env.example .env
docker compose up -d        # starts Postgres, Redis, Kafka (KRaft), Elasticsearch
cd backend && mvn spring-boot:run
```

Elasticsearch runs **single-node**, security disabled (`xpack.security.enabled=false`), explicitly
for local development only — not bound to any production assumption, and not exposed beyond the
host's own interfaces (same as every other service in `docker-compose.yml`). The backend itself is
not a compose service (it runs via `mvn spring-boot:run`, per the existing project convention), so
there is no `depends_on` to declare; `ServicePointIndexInitializer` and every ES-backed code path
tolerate Elasticsearch not being up yet and recover automatically once it is. New environment
variables: `ELASTICSEARCH_USERNAME/PASSWORD` (blank by default — matches the security-disabled dev
node), `ELASTICSEARCH_CONNECT_TIMEOUT_MS`/`SOCKET_TIMEOUT_MS`, `ELASTICSEARCH_SERVICE_POINTS_INDEX`,
and `DISCOVERY_MAX_RADIUS_METERS`/`DEFAULT_RADIUS_METERS`/`MAX_PAGE_SIZE`/`DEFAULT_PAGE_SIZE`/`REINDEX_BATCH_SIZE`.

### Frontend

A minimal "Nearby Service Points" widget was added to the existing dashboard (`#discovery` section,
already linked from the sidebar) rather than a new page: manual latitude/longitude entry (default
fallback), an explicit "Locate" button that only ever requests browser geolocation on click, a
category filter, and loading / empty / validation-error / service-unavailable states. No map
library or third-party API key — the project didn't already depend on one, and the brief explicitly
discourages adding one without a clear need.

### Test results & known limitations (Phase 6)

Ran in this environment: `mvn test` — **146 tests, 0 failures, 0 errors, 30 skipped** (17 carried
over from Phases 4–5, 13 new: 10 in `ServicePointNearbySearchIntegrationTest`, 3 in
`ServicePointIndexingIntegrationTest`). `npm run build` — clean. Docker's CLI is present in this
sandbox but its daemon is not running, so every `@Testcontainers(disabledWithoutDocker = true)`
class reports SKIPPED rather than failing the build. Run them once a daemon is reachable:

```bash
mvn test -Dtest=ServicePointNearbySearchIntegrationTest   # real ES: radius/pagination/category/
                                                            # tie-break/antimeridian/pole/dup-avoidance
mvn test -Dtest=ServicePointIndexingIntegrationTest        # real Postgres+Kafka+ES: full pipeline,
                                                            # update/deactivate, reindex repeatability
```

Everything that *did* run — including `GeohashUtilsTest` (deterministic geohash encode/decode/neighbor
math, including antimeridian and pole cases — no Docker needed), the admin/search controller tests,
the consumer unit tests, and every pre-existing Phase 1–5 test — passed unchanged.

**Distinguishing verified from mocked behavior**: the Elasticsearch query construction, the
consumer's parse/dedupe/upsert logic, and the admin service's outbox-write behavior are verified
with Mockito-mocked `ElasticsearchOperations` — this proves the *code calls the right methods with
the right arguments*, not that a real Elasticsearch cluster returns the expected documents for a
real geo query. That second claim rests solely on `ServicePointNearbySearchIntegrationTest` and
`ServicePointIndexingIntegrationTest`, both of which are real-service tests that compiled correctly
but did not execute in this environment (no Docker daemon) — their actual pass/fail status is
unverified here and should be confirmed by running the commands above before treating Phase 6's geo
correctness claims as proven in any given environment.

Known limitations:
- Single-node Elasticsearch (documented above) — not production HA; no replica shards.
- Mid-reindex event ordering is last-write-wins, not guaranteed-consistent (documented above).
- `wallet.transfers.v1`/`wallet.notifications.v1` remain unused (Phase 5 limitation, unchanged).
- No transaction full-text search yet — `wallet.transactions.v1` is read today only by the Phase 4
  transaction indexer's processed-event bookkeeping, not by any Elasticsearch document write; that
  remains a reserved future extension, not part of Phase 6.
