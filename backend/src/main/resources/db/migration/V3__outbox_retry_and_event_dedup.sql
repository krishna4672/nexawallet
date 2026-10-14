-- ==============================================================================
-- Flyway Database Migration: V3__outbox_retry_and_event_dedup.sql
-- NexaWallet Platform - Phase 4: Transactional Outbox & Kafka Event-Driven Architecture
-- PostgreSQL 16 Target
--
-- Adds bounded-retry/backoff bookkeeping to the existing outbox_events table
-- (created in V1) and introduces a durable processed-event registry used by
-- Kafka consumers to guarantee idempotent (at-least-once-safe) processing.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. OUTBOX RETRY/BACKOFF COLUMNS
-- next_retry_at: when the publisher may next attempt a PENDING event (NULL = eligible now).
-- last_error: sanitized, length-limited diagnostic of the most recent publish failure.
-- ------------------------------------------------------------------------------
ALTER TABLE outbox_events ADD COLUMN next_retry_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE outbox_events ADD COLUMN last_error VARCHAR(500);

-- ------------------------------------------------------------------------------
-- 2. PUBLISHING STATUS
-- A short-lived status marking a batch of events as claimed by a publisher
-- instance so concurrent instances never publish the same row twice during
-- normal processing (claim = SELECT ... FOR UPDATE SKIP LOCKED + this status flip,
-- all within one short transaction; the Kafka send itself happens afterwards
-- with no DB lock held).
-- ------------------------------------------------------------------------------
ALTER TABLE outbox_events DROP CONSTRAINT chk_outbox_status;
ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_status
    CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED'));

-- ------------------------------------------------------------------------------
-- 3. INDEXES FOR PENDING-EVENT POLLING AND AGGREGATE LOOKUP
-- ------------------------------------------------------------------------------
CREATE INDEX idx_outbox_events_pending_poll ON outbox_events(status, next_retry_at, created_at);
CREATE INDEX idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);

-- ------------------------------------------------------------------------------
-- 4. PROCESSED EVENTS TABLE (CONSUMER-SIDE IDEMPOTENCY REGISTRY)
-- Durable record of which (event_id, consumer_group) pairs have already been
-- handled, so at-least-once Kafka redelivery never double-applies side effects.
-- ------------------------------------------------------------------------------
CREATE TABLE processed_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    topic VARCHAR(150) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_processed_events_event_consumer UNIQUE (event_id, consumer_group)
);

CREATE INDEX idx_processed_events_event_id ON processed_events(event_id);
