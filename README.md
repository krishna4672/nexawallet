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
- [ ] **Phase 3: Core Fintech Engine** (Double-entry ledger, deterministic locking, idempotency, simulated deposits)
- [ ] **Phase 4: Transactional Outbox & Kafka Pipeline** (Outbox publisher, Kafka topics, consumer deduplication)
- [ ] **Phase 5: Redis Caching & Sliding-Window Rate Limiting**
- [ ] **Phase 6: Elasticsearch Full-Text Transaction Search & Geohash Discovery**
- [ ] **Phase 7: Frontend Polish, Recharts Analytics & Interactive Transfer Flow**
- [ ] **Phase 8: End-to-End Testing (Testcontainers, Concurrency Stress Tests) & Interview Kit**
