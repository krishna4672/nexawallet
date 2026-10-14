-- ==============================================================================
-- Flyway Database Migration: V4__service_points_updated_at.sql
-- NexaWallet Platform - Phase 6: Elasticsearch Search & Geohash Discovery
--
-- The service_points table (created in V1) already carries everything the search
-- index needs (name, type, geohash, latitude, longitude, address, is_active,
-- created_at). The only gap is change tracking: Phase 6 introduces an admin write
-- path (create/update/deactivate) whose outbox events need a reliable "this changed
-- at..." timestamp, matching the updated_at convention already used by every other
-- mutable entity in this codebase (see BaseEntity).
-- ==============================================================================

ALTER TABLE service_points ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
