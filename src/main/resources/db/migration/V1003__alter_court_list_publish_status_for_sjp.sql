-- V1003__alter_court_list_publish_status_for_sjp.sql
--
-- Reuses court_list_publish_status for SJP publish tracking instead of a separate table.
-- SJP publish-status rows differ from every other list type in two ways:
--   * SJP is national, so there is no court centre.
--   * The SJP flow publishes JSON to CaTH and produces no file.
-- Both columns therefore become nullable, with CHECK constraints keeping them mandatory
-- for every non-SJP list type.
--
-- Uniqueness needs care once court_centre_id is nullable: PostgreSQL treats NULLs as
-- distinct by default, so a single unique index over (court_centre_id, publish_date,
-- court_list_type) would silently accept duplicate SJP rows. Two partial indexes avoid
-- NULL comparison semantics entirely:
--   * SJP rows are keyed by (publish_date, court_list_type). The SJP_* court list types are
--     SJP-only and already fuse audience, request type and language, so court centre adds
--     nothing to the key.
--   * All other rows keep the original three-column key.

ALTER TABLE court_list_publish_status ALTER COLUMN court_centre_id DROP NOT NULL;
ALTER TABLE court_list_publish_status ALTER COLUMN file_status DROP NOT NULL;

ALTER TABLE court_list_publish_status
    ADD CONSTRAINT ck_court_centre_id_required_for_non_sjp
    CHECK (court_centre_id IS NOT NULL OR starts_with(court_list_type, 'SJP_'));

ALTER TABLE court_list_publish_status
    ADD CONSTRAINT ck_file_status_required_for_non_sjp
    CHECK (file_status IS NOT NULL OR starts_with(court_list_type, 'SJP_'));

CREATE UNIQUE INDEX IF NOT EXISTS ux_court_list_publish_status_sjp
    ON court_list_publish_status (publish_date, court_list_type)
    WHERE court_centre_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_court_list_publish_status_non_sjp
    ON court_list_publish_status (court_centre_id, publish_date, court_list_type)
    WHERE court_centre_id IS NOT NULL;
