-- ==============================================================================
-- Flyway Database Migration: V1__init_schema.sql
-- NexaWallet Platform - Initial Relational Schema
-- PostgreSQL 16 Target
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. USERS TABLE
-- Stores authenticated customers and administrators.
-- ------------------------------------------------------------------------------
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone_number VARCHAR(30),
    role VARCHAR(50) NOT NULL DEFAULT 'ROLE_CUSTOMER',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (role IN ('ROLE_CUSTOMER', 'ROLE_ADMIN', 'ROLE_AUDITOR'))
);

CREATE INDEX idx_users_email ON users(email);

-- ------------------------------------------------------------------------------
-- 2. WALLETS TABLE
-- Stores user balances and status.
-- Invariant: balance must be strictly non-negative.
-- ------------------------------------------------------------------------------
CREATE TABLE wallets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    wallet_number VARCHAR(36) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_wallets_number UNIQUE (wallet_number),
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_wallet_balance_non_negative CHECK (balance >= 0.0000),
    CONSTRAINT chk_wallet_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE INDEX idx_wallets_user_id ON wallets(user_id);
CREATE INDEX idx_wallets_number ON wallets(wallet_number);

-- ------------------------------------------------------------------------------
-- 3. LEDGER ACCOUNTS TABLE
-- Maps chart of accounts: user wallet accounts, system settlement, fee revenue.
-- ------------------------------------------------------------------------------
CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_type VARCHAR(50) NOT NULL,
    wallet_id UUID,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ledger_accounts_wallet FOREIGN KEY (wallet_id) REFERENCES wallets(id) ON DELETE RESTRICT,
    CONSTRAINT chk_ledger_account_type CHECK (account_type IN ('USER_WALLET', 'SYSTEM_SETTLEMENT', 'FEE_REVENUE'))
);

CREATE INDEX idx_ledger_accounts_wallet_id ON ledger_accounts(wallet_id);

-- ------------------------------------------------------------------------------
-- 4. TRANSACTIONS TABLE
-- High-level business intent representing transfers, deposits, or adjustments.
-- ------------------------------------------------------------------------------
CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_code VARCHAR(64) NOT NULL,
    source_wallet_id UUID,
    target_wallet_id UUID,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    type VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    description VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_transactions_ref_code UNIQUE (reference_code),
    CONSTRAINT fk_transactions_source_wallet FOREIGN KEY (source_wallet_id) REFERENCES wallets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_transactions_target_wallet FOREIGN KEY (target_wallet_id) REFERENCES wallets(id) ON DELETE RESTRICT,
    CONSTRAINT chk_transaction_amount_positive CHECK (amount > 0.0000),
    CONSTRAINT chk_transaction_type CHECK (type IN ('P2P_TRANSFER', 'DEPOSIT_SIMULATED', 'WITHDRAWAL_SIMULATED', 'FEE_DEDUCTION')),
    CONSTRAINT chk_transaction_status CHECK (status IN ('PENDING', 'SETTLED', 'FAILED', 'REVERSED'))
);

CREATE INDEX idx_transactions_source_wallet ON transactions(source_wallet_id);
CREATE INDEX idx_transactions_target_wallet ON transactions(target_wallet_id);
CREATE INDEX idx_transactions_created_at ON transactions(created_at);

-- ------------------------------------------------------------------------------
-- 5. LEDGER ENTRIES TABLE (DOUBLE-ENTRY LEDGER)
-- Immutable journal entries: Every financial movement writes balanced debits and credits.
-- Note: In this Phase 1, ledger entries are append-only by application convention.
-- Stronger database-level immutability triggers and transaction balancing assertions
-- will be installed in Phase 3 (Core Fintech Engine).
-- ------------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    ledger_account_id UUID NOT NULL,
    direction VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts(id) ON DELETE RESTRICT,
    CONSTRAINT chk_entry_amount_positive CHECK (amount > 0.0000),
    CONSTRAINT chk_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_entries_account_id ON ledger_entries(ledger_account_id);

-- ------------------------------------------------------------------------------
-- 6. IDEMPOTENCY KEYS TABLE
-- Prevents duplicate financial mutations from network retries or double clicks.
-- ------------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    user_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    transaction_id UUID,
    response_payload TEXT,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_idempotency_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE SET NULL,
    CONSTRAINT chk_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys(expires_at);

-- ------------------------------------------------------------------------------
-- 7. TRANSACTIONAL OUTBOX EVENTS TABLE
-- Guarantees reliable at-least-once Kafka event publishing without 2PC.
-- ------------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_outbox_events_status_created ON outbox_events(status, created_at);

-- ------------------------------------------------------------------------------
-- 8. REFRESH TOKENS TABLE
-- Long-lived refresh tokens with revocation support for secure JWT rotation.
-- ------------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

-- ------------------------------------------------------------------------------
-- 9. AUDIT LOGS TABLE
-- Security and compliance audit trail for critical user and administrative actions.
-- ------------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID,
    action VARCHAR(100) NOT NULL,
    resource VARCHAR(100) NOT NULL,
    resource_id VARCHAR(100),
    ip_address VARCHAR(45),
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_logs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);

-- ------------------------------------------------------------------------------
-- 10. SERVICE POINTS TABLE (GEOHASH DISCOVERY)
-- Physical cash-in/cash-out kiosks, branches, and partner merchant points.
-- ------------------------------------------------------------------------------
CREATE TABLE service_points (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    type VARCHAR(50) NOT NULL,
    geohash VARCHAR(12) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    address VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_service_point_type CHECK (type IN ('ATM', 'BRANCH', 'PARTNER_KIOSK', 'MERCHANT'))
);

CREATE INDEX idx_service_points_geohash ON service_points(geohash);
