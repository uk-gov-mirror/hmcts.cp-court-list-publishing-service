-- V1003__sjp_publish_status_nullable_keys_and_unique_indexes.sql
--
-- SJP publish-status rows differ from every other list type in two ways:
--   * SJP is national, so there is no court centre.
--   * The SJP flow publishes JSON to CaTH and produces no file.
-- Both columns therefore become nullable, with CHECK constraints keeping them mandatory
-- for every non-SJP list type. (The alternative - a sentinel court centre UUID - encodes
-- "not applicable" as a real-looking value that outlives anyone's memory of what it meant.)
--
-- Uniqueness needs care. CourtListPublishStatusService treats the row key as
-- (court_centre_id, publish_date, court_list_type) and enforces it with read-then-write,
-- so concurrent publishes can both miss and both insert. A single unique index over those
-- three columns does NOT fix this once court_centre_id is nullable: PostgreSQL treats NULLs
-- as distinct by default, so duplicate SJP rows would still be accepted - failing open,
-- silently, on exactly the rows this is meant to protect.
--
-- Two partial indexes avoid NULL comparison semantics entirely and state each case exactly:
--   * SJP rows are keyed by (publish_date, court_list_type). The SJP_* court list types are
--     SJP-only and already fuse audience, request type and language, so court centre adds
--     nothing to the key.
--   * All other rows keep the original three-column key.
--
-- NOTE: the constraints below will fail if existing rows violate them. Check first:
--   SELECT count(*) FROM court_list_publish_status WHERE court_centre_id IS NULL OR file_status IS NULL;
--   SELECT court_centre_id, publish_date, court_list_type, count(*)
--     FROM court_list_publish_status GROUP BY 1,2,3 HAVING count(*) > 1;

ALTER TABLE court_list_publish_status ALTER COLUMN court_centre_id DROP NOT NULL;
ALTER TABLE court_list_publish_status ALTER COLUMN file_status     DROP NOT NULL;

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
