-- V1003__alter_court_list_publish_status_for_sjp.sql
-- Reuses court_list_publish_status for SJP publish tracking instead of a separate table.
-- SJP has no court-centre concept (it's a national list, not scoped to one court) and no
-- PDF generation step, so those columns become nullable and are left unpopulated for SJP rows.
-- payload_hash supports SJP's content-hash dedup (unused/null for the standard flow).

ALTER TABLE court_list_publish_status ALTER COLUMN court_centre_id DROP NOT NULL;
ALTER TABLE court_list_publish_status ALTER COLUMN file_status DROP NOT NULL;
ALTER TABLE court_list_publish_status ADD COLUMN payload_hash TEXT;
