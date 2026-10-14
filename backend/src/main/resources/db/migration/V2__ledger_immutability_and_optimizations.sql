-- ==============================================================================
-- Flyway Database Migration: V2__ledger_immutability_and_optimizations.sql
-- NexaWallet Platform - Ledger Immutability & Query Optimization
-- PostgreSQL 16 Target
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. STRICT DATABASE-LEVEL IMMUTABILITY FOR LEDGER ENTRIES
-- Prohibits any UPDATE or DELETE operations on ledger_entries at the database engine level.
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prevent_ledger_entry_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are strictly immutable and append-only. Mutation disallowed.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_ledger_entry_update
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_entry_mutation();

-- ------------------------------------------------------------------------------
-- 2. CURRENCY RESTRICTION (INITIAL USD SUPPORT)
-- ------------------------------------------------------------------------------
ALTER TABLE wallets ADD CONSTRAINT chk_wallet_currency_usd CHECK (currency = 'USD');
ALTER TABLE transactions ADD CONSTRAINT chk_transaction_currency_usd CHECK (currency = 'USD');
ALTER TABLE ledger_accounts ADD CONSTRAINT chk_ledger_account_currency_usd CHECK (currency = 'USD');

-- ------------------------------------------------------------------------------
-- 3. COMPOSITE INDEXES FOR FAST TRANSACTION HISTORY PAGINATION
-- ------------------------------------------------------------------------------
CREATE INDEX idx_transactions_source_created ON transactions(source_wallet_id, created_at DESC);
CREATE INDEX idx_transactions_target_created ON transactions(target_wallet_id, created_at DESC);
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries(ledger_account_id, created_at DESC);
